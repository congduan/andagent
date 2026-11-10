package com.dc.andagent.tools

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * R122 — 蓝牙 HID 设备模式（bt_hid_register / bt_hid_send / bt_hid_unregister）。
 *
 * Android 9 / API 28+ 的 [BluetoothHidDevice]（BluetoothProfile.HID_DEVICE）让本机对外
 * 声明为 HID 设备：registerApp 提交 SDP 记录 + 报告描述符 → 目标机在系统蓝牙里主动
 * 连接本机 HID 服务 → sendReport 经 interrupt channel 注入报告。普通应用可用、无需新增
 * 权限（本仓已声明 BLUETOOTH/BLUETOOTH_ADMIN/BLUETOOTH_CONNECT）。
 *
 * 协议事实（javap android-34/android.jar + 官方 javadoc + AOSP CtsVerifier
 * HidDeviceActivity 实证，见 /tmp/andagent-r122-progress.md）：
 *  - `BluetoothHidDevice.Callback`（简报写 AppCallback，实际类名是 Callback）。
 *  - `registerApp(sdp, inQos, outQos, Executor, Callback)`；`BluetoothHidDeviceAppSdpSettings`
 *    在 compileSdk 34 只有 5 参构造 `(name, description, provider, subclass, byte[] descriptors)`，
 *    **没有公开 Builder**；`BluetoothHidDeviceAppQosSettings` 为 6 参构造。
 *  - `sendReport(device, id, data)` 的 id 是描述符里的 Report ID，data 不含 Report ID 字节。
 *  - registerApp 的返回值只代表请求被接受，真实注册状态由 Callback.onAppStatusChanged 跟踪；
 *    unregisterApp 同理。
 *
 * API<28：直接在 [BtHidTool.execute] 返回明确的 unsupported，不触碰任何 API 28 类。
 */
val BT_HID_TOOLS = listOf("bt_hid_register", "bt_hid_send", "bt_hid_unregister")

/**
 * 三个工具实例共享的进程内单例。字段刻意只用低版本就存在的类型（BluetoothProfile /
 * BluetoothAdapter / BluetoothDevice），避免 API 26/27 加载本类时解析到 API 28 的
 * BluetoothHidDevice；真实 proxy 在 [@SuppressLint("NewApi")] 的运行时层里按需向下转型。
 */
object BtHidRegistry {
    @Volatile var proxy: BluetoothProfile? = null
    @Volatile var adapter: BluetoothAdapter? = null
    @Volatile var mode: String = "combo"
    @Volatile var sdpName: String = "AndAgent HID"
    @Volatile var registered: Boolean = false
    /** 代际计数器：register/release/unregister 每轮先自增，让上一轮提交给蓝牙栈的
     *  listener/callback 闭包凭 gen != generation 自行失效（Codex review 必修 3）。
     *  普通 volatile 字段即可，保持纯 JVM 可单测、不依赖 Android 原子类型。 */
    @Volatile var generation: Long = 0L

    private val connected = LinkedHashMap<String, BluetoothDevice>()

    @Synchronized fun onConnected(d: BluetoothDevice) { connected[d.address.uppercase()] = d }

    @Synchronized fun onDisconnected(d: BluetoothDevice) { connected.remove(d.address.uppercase()) }

    @Synchronized fun clear() { connected.clear() }

    @Synchronized fun snapshot(): List<BluetoothDevice> = connected.values.toList()

    /** MAC 精确匹配或名称模糊匹配；只在本机已连接的 HID host 里找。 */
    @Synchronized fun resolve(ref: String): BluetoothDevice? {
        val key = ref.trim()
        if (BluetoothAdapter.checkBluetoothAddress(key)) {
            connected[key.uppercase()]?.let { return it }
        }
        return connected.values.firstOrNull { (it.name ?: "").contains(key, ignoreCase = true) }
            ?: connected.values.firstOrNull { it.address.equals(key, ignoreCase = true) }
    }
}

@SuppressLint("MissingPermission", "NewApi")
class BtHidTool(private val context: Context, override val name: String) : Tool {

    override val description: String = when (name) {
        "bt_hid_register" ->
            "把本机注册为蓝牙 HID 设备（Android 9 / API 28+ 的 BluetoothHidDevice），让已配对的电脑/手机/平板把本机识别为键盘、鼠标或键鼠组合。name 是蓝牙 SDP 里显示的设备名；mode=keyboard|mouse|combo 决定报告描述符（键盘 8 字节 boot protocol 报告 + 鼠标 3 键/相对位移/滚轮，combo 用 Report ID 1/2 区分）。注册成功只代表 SDP 服务就绪，目标设备仍需在系统蓝牙里主动连接本机的 HID 服务（Android 作为 HID device 通常由对端发起连接）；未连接时 bt_hid_send 会明确报错。API<28 返回 unsupported。用 bt_hid_unregister 注销恢复本机 HID host 角色。"
        "bt_hid_send" ->
            "向已连接本机 HID 服务的目标设备注入输入报告（BluetoothHidDevice.sendReport）。target 填已配对设备的 MAC 或名称（模糊匹配）；type=key|key_down|key_up|text|mouse_move|mouse_click：key 按下后立即释放，key_down/key_up 分离按下与释放（key_up 发全零释放报告），text 逐字符按 HID Usage ID 键入（仅 ASCII，中文/emoji 会被拒绝），mouse_move 用相对位移 dx/dy(-127..127) 与 wheel，mouse_click=按键按下+释放两次报告。keycode 是 HID Keyboard/Keypad Usage ID（0x04-0xDF，如 a=0x04、Enter=0x28、方向键 0x4F-0x52、F1-F12=0x3A-0x45，报告字段 1 字节 0..255）；modifiers 为逗号分隔 ctrl/shift/alt/gui（及 rctrl/rshift/ralt/rgui 右修饰键）。发送前检查 getConnectionState/getConnectedDevices，未连接返回明确错误，不虚报成功。iOS/Windows/macOS 对 combo 报告兼容性不一，连上但无输入时可在对端蓝牙设置里选择「输入设备」用途。破坏性：改变目标设备输入状态。"
        "bt_hid_unregister" ->
            "注销本机蓝牙 HID 设备注册（BluetoothHidDevice.unregisterApp），断开当前 HID 连接并恢复本机 HID host 角色。未注册时如实说明。"
        else -> "蓝牙 HID 设备工具"
    }

    override val parameters = when (name) {
        "bt_hid_register" -> schemaOf(
            "name" to strParam("SDP 设备名（显示在目标机蓝牙列表），默认 AndAgent HID"),
            "mode" to strParam("keyboard|mouse|combo，默认 combo"),
        )
        "bt_hid_send" -> schemaOf(
            "target" to strParam("已配对目标设备的 MAC 或名称（模糊匹配）"),
            "type" to strParam("key|key_down|key_up|text|mouse_move|mouse_click"),
            "keycode" to intParam("HID Keyboard/Keypad Usage ID（0..255，见描述）"),
            "modifiers" to strParam("逗号分隔修饰键 ctrl|shift|alt|gui|rctrl|rshift|ralt|rgui"),
            "text" to strParam("type=text 的 ASCII 文本（非 ASCII/中文拒绝）"),
            "dx" to intParam("mouse_move 的 X 相对位移 -127..127（默认 0）"),
            "dy" to intParam("mouse_move 的 Y 相对位移 -127..127（默认 0）"),
            "wheel" to intParam("mouse_move 的滚轮增量 -127..127（默认 0）"),
            "button" to strParam("mouse_click 的按键 left|right|middle（默认 left）"),
            required = listOf("target", "type"),
        )
        "bt_hid_unregister" -> schemaOf()
        else -> schemaOf()
    }

    override val destructive: Boolean = name == "bt_hid_send"

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < 28) {
            return@withContext "ERROR: 蓝牙 HID 设备模式不支持（BluetoothHidDevice 需 Android 9 / API 28+，本机 API ${Build.VERSION.SDK_INT}）"
        }
        runCatching { BtHidRuntime.execute(context, name, args) }
            .getOrElse { "ERROR: ${it.message ?: it.javaClass.simpleName}" }
    }
}

/** 所有 API 28 框架调用集中在此，只在 SDK_INT>=28 时被加载/执行。 */
@SuppressLint("MissingPermission", "NewApi")
private object BtHidRuntime {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    // 用 Any? 持有回调强引用（callbackRef 与 callback 的类型都不进公共签名）。真正的
    // API 26/27 隔离边界是 BtHidTool.execute 的 SDK_INT>=28 检查——检查通过才进入本 object。
    @Volatile private var callbackRef: Any? = null

    /** 本轮局部 proxy holder（必修 4）：listener.onServiceConnected 即便在 register
     *  超时返回后仍把迟到的 proxy 写进来，由迟到回调凭代际检查自行 close。 */
    private class ProxyHolder {
        @Volatile var proxy: BluetoothProfile? = null
    }

    private val lifecycleLock = Object()

    fun execute(context: Context, name: String, args: JsonObject): String =
        synchronized(lifecycleLock) {
            when (name) {
                "bt_hid_register" -> register(context, args)
                "bt_hid_send" -> send(args)
                "bt_hid_unregister" -> unregister()
                else -> "ERROR: 未知工具 $name"
            }
        }

    private fun permissionError(context: Context): String? =
        if (Build.VERSION.SDK_INT >= 31 &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) "ERROR: 缺少 BLUETOOTH_CONNECT 权限（拒绝或永久拒绝，可在系统设置开启）"
        else null

    /* --------------------------- proxy 生命周期 --------------------------- */

    /** 自增代际：register/release/unregister 每轮先自增，使旧轮次已提交给蓝牙栈的
     *  listener/callback 闭包因 gen != generation 自行失效（Codex review 必修 3）。*/
    private fun bumpGeneration() {
        BtHidRegistry.generation = BtHidRegistry.generation + 1
    }

    /** 复位全局状态（不 close proxy）。 */
    private fun resetState() {
        callbackRef = null
        BtHidRegistry.registered = false
        BtHidRegistry.proxy = null
        BtHidRegistry.clear()
    }

    /** 放弃本轮：自增代际让本轮迟到回调失效 + 复位全局状态。本轮 proxy 的 close 由调用方
     *  用局部 adapter 显式完成（必修 4b），绝不经过 BtHidRegistry.adapter。 */
    private fun abandonRound() {
        bumpGeneration()
        resetState()
    }

    /**
     * 统一释放上一轮：先自增代际使旧回调失效，再 unregisterApp + closeProfileProxy
     * （用 registry 里上一轮合法的 adapter/proxy）+ 复位。用于重复 register 前置清理与
     * unregister 收尾；register 本轮的失败路径一律不走这里（必修 4b，改由局部 adapter 关闭）。
     */
    private fun releaseHidProxy() {
        bumpGeneration()
        val hid = BtHidRegistry.proxy as? BluetoothHidDevice
        if (hid != null) {
            runCatching { hid.unregisterApp() }
            BtHidRegistry.adapter?.let {
                runCatching { it.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid) }
            }
        }
        resetState()
    }

    /* ------------------------------ register ------------------------------ */

    private fun register(context: Context, args: JsonObject): String {
        val mode = (args.strArg("mode") ?: "combo").lowercase()
        if (mode !in setOf("keyboard", "mouse", "combo")) return "ERROR: mode 仅支持 keyboard|mouse|combo"
        val sdpName = args.strArg("name") ?: "AndAgent HID"
        permissionError(context)?.let { return it }

        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: return "ERROR: 蓝牙不可用（无蓝牙硬件）"
        if (!adapter.isEnabled) return "ERROR: 蓝牙未开启，请先在系统设置中开启蓝牙"

        val descriptor = BtHidCodec.descriptorFor(mode)
            ?: return "ERROR: 未找到模式 $mode 的报告描述符"
        if (!BtHidCodec.descriptorWellFormed(descriptor)) return "ERROR: 报告描述符自检失败"

        // 重复注册：先注销旧应用注册、归还旧 proxy（HID device 角色全局唯一）。
        // releaseHidProxy 内部先自增代际，使上一轮提交给蓝牙栈的 listener/callback 全部
        // 失效（必修 3）；这里关闭的是 registry 里上一轮合法的 adapter/proxy。
        releaseHidProxy()

        // 本轮代际：releaseHidProxy 自增过，取当前值；listener 与 callback 都捕获它，
        // 回调首行 gen != generation 即自行失效（必修 3）。
        val gen = BtHidRegistry.generation

        // 本轮局部 holder（必修 4）：listener.onServiceConnected 即便在 register 超时返回后
        // 仍把迟到的 proxy 写进来；配合代际检查由迟到回调自行 close，杜绝 Binder 泄漏。
        val holder = ProxyHolder()
        val proxyLatch = CountDownLatch(1)
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(type: Int, proxy: BluetoothProfile) {
                if (type != BluetoothProfile.HID_DEVICE) return
                holder.proxy = proxy
                if (gen != BtHidRegistry.generation) {
                    // 本轮已作废（超时/失败/新一轮）：迟到 proxy 无人使用，用本轮局部 adapter
                    // 自行 close，杜绝泄漏（必修 4c）。
                    runCatching { adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, proxy) }
                    return
                }
                proxyLatch.countDown()
            }

            override fun onServiceDisconnected(type: Int) {
                if (gen != BtHidRegistry.generation) return   // 迟到回调不碰新一轮状态（必修 3）
                if (type == BluetoothProfile.HID_DEVICE) {
                    BtHidRegistry.proxy = null
                    BtHidRegistry.registered = false
                    BtHidRegistry.clear()
                }
            }
        }
        val requested = runCatching {
            adapter.getProfileProxy(context, listener, BluetoothProfile.HID_DEVICE)
        }.getOrDefault(false)
        if (!requested) {
            abandonRound()
            // R132：区分失败路径。requested=false = 蓝牙栈同步拒绝（ROM 未实现/禁用
            // HID device 角色——ColorOS/HyperOS 部分版本对第三方 app 白名单限制，
            // 社区有 OPPO/华为系 ROM 同报反馈）。
            return "ERROR: 系统蓝牙栈拒绝 HID_DEVICE profile 申请（本机 ROM 可能禁用了第三方 HID device 角色，如 ColorOS/EMUI 部分版本有白名单限制）；可尝试重启蓝牙后重试，若仍失败则本机无法充当 HID 设备"
        }
        // R134：6s 超时对 Android 14+ 的模块化蓝牙栈偏紧（服务懒加载 + 后台限制
        // 后首次绑定常超 6s，Android 17/ColorOS 真机实证）。放宽到 15s。
        if (!proxyLatch.await(15, TimeUnit.SECONDS)) {
            // 超时：本轮作废并自增代际；迟到的 onServiceConnected 会凭 gen 不匹配自行 close
            // 那个 proxy（必修 4c）。Codex review 必修 2：存在边界竞态——回调恰在 await 返回
            // 超时前后写入 holder 并通过代际检查但尚未 countDown（或 countDown 与超时判定
            // 交错），此时迟到 close 不会发生、abandonRound 也不管 proxy → Binder 泄漏。
            // 兜底：abandonRound 后复查 holder，仍有 proxy 就用本轮局部 adapter 显式 close
            // （与 onServiceConnected 的 close 幂等：closeProfileProxy 对同一实例重复调用安全）。
            // R134b：第二轮等待（再 15s）——Android 14+ 蓝牙服务懒加载/后台限流下
            // 首个 15s 不够的案例存在；迟到回调凭 gen 检查仍会写入 holder 并 countDown
            // （此时 latch 已归零无妨，直接检查 holder）。第二轮仍空才真正放弃。
            if (!proxyLatch.await(15, TimeUnit.SECONDS) && holder.proxy == null) {
                abandonRound()
                holder.proxy?.let {
                    runCatching { adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, it) }
                }
                return "ERROR: 获取 BluetoothHidDevice proxy 超时（30s）——请保持 AndAgent 在前台、重启手机蓝牙后重试一次；若仍超时，重启手机后试"
            }
            // 第二轮期间迟到回调已写入 holder（gen 校验在回调内已放行本轮）
            holder.proxy?.let { proxy ->
                if (gen == BtHidRegistry.generation) proxyLatch.countDown()
            }
        }
        // 本轮 proxy 全程由局部 holder 持有，绝不先写 BtHidRegistry。
        val hid = holder.proxy as? BluetoothHidDevice
        if (hid == null) {
            // 类型不符：显式用本轮局部 adapter close 后再返回（必修 4a，原实现直接 return 泄漏）。
            holder.proxy?.let { runCatching { adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, it) } }
            abandonRound()
            return "ERROR: 取得的 profile proxy 不是 BluetoothHidDevice"
        }

        val subclass = when (mode) {
            "keyboard" -> BluetoothHidDevice.SUBCLASS1_KEYBOARD
            "mouse" -> BluetoothHidDevice.SUBCLASS1_MOUSE
            else -> BluetoothHidDevice.SUBCLASS1_COMBO
        }
        val sdp = BluetoothHidDeviceAppSdpSettings(
            sdpName,
            "AndAgent Bluetooth HID ($mode)",
            "AndAgent",
            subclass,
            descriptor,
        )
        val qos = BluetoothHidDeviceAppQosSettings(
            BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
            800, 9, 0, 11250, BluetoothHidDeviceAppQosSettings.MAX,
        )

        val appLatch = CountDownLatch(1)
        val appRegistered = AtomicBoolean(false)
        val callback = object : BluetoothHidDevice.Callback() {
            override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
                if (gen != BtHidRegistry.generation) return   // 迟到回调不改全局状态（必修 3）
                appRegistered.set(registered)
                BtHidRegistry.registered = registered
                appLatch.countDown()
            }

            override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
                if (gen != BtHidRegistry.generation) return   // 迟到回调不混入新一轮连接表（必修 3）
                if (device == null) return
                if (state == BluetoothProfile.STATE_CONNECTED) BtHidRegistry.onConnected(device)
                else if (state == BluetoothProfile.STATE_DISCONNECTED) BtHidRegistry.onDisconnected(device)
            }

            override fun onGetReport(device: BluetoothDevice?, type: Byte, id: Byte, bufferSize: Int) {
                if (gen != BtHidRegistry.generation) return   // 迟到回调不回复（必修 3）
                if (device == null) return
                val proxy = BtHidRegistry.proxy as? BluetoothHidDevice ?: return
                // 只对本机声明过的 INPUT 报告回复（键盘 8B / 鼠标 4B，按 Report ID 区分）。
                // 描述符还声明了 1B LED OUTPUT 报告——对 OUTPUT/FEATURE 的 GET_REPORT 本应用
                // 对应值回复，但本工具不维护 LED 状态镜像，按 Codex review 必修 3 显式
                // reportError(unsupported)，不给主机一个错误长度的伪答案。
                if (type != BluetoothHidDevice.REPORT_TYPE_INPUT) {
                    runCatching { proxy.reportError(device, BluetoothHidDevice.ERROR_RSP_UNSUPPORTED_REQ) }
                    return
                }
                // Codex review 建议 2：按已声明的 Report ID + 当前模式校验——本模式没声明的
                // ID、或与本机角色不符的请求，回 protocol error 而不是错误长度的伪答案。
                val requestedMouse = id.toInt() == BtHidCodec.REPORT_ID_MOUSE
                val requestedKeyboard = id.toInt() == BtHidCodec.REPORT_ID_KEYBOARD
                val mode = BtHidRegistry.mode
                val matchesMode = when (mode) {
                    "keyboard" -> requestedKeyboard
                    "mouse" -> requestedMouse
                    else -> requestedKeyboard || requestedMouse
                }
                if (!matchesMode) {
                    runCatching { proxy.reportError(device, BluetoothHidDevice.ERROR_RSP_UNSUPPORTED_REQ) }
                    return
                }
                val empty = if (requestedMouse) BtHidCodec.mouseReport() else BtHidCodec.keyboardReport()
                runCatching { proxy.replyReport(device, type, id, empty) }
            }

            override fun onVirtualCableUnplug(device: BluetoothDevice?) {
                if (gen != BtHidRegistry.generation) return   // 迟到回调不改连接表（必修 3）
                device?.let { BtHidRegistry.onDisconnected(it) }
            }
        }
        callbackRef = callback

        val accepted = runCatching { hid.registerApp(sdp, null, qos, executor, callback) }.getOrDefault(false)
        if (!accepted) {
            // 必修 4b：显式用本轮局部 adapter close，绝不经过 BtHidRegistry.adapter
            // （失败路径 registry.adapter 仍是旧值/null，旧实现关不掉本次 proxy）。
            runCatching { adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid) }
            abandonRound()
            return "ERROR: registerApp 被系统拒绝（蓝牙栈未就绪、权限不足或已有其他 App 占用 HID device 角色）"
        }
        val confirmed = appLatch.await(5, TimeUnit.SECONDS)
        if (!confirmed || !appRegistered.get()) {
            runCatching { hid.unregisterApp() }
            runCatching { adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid) }
            abandonRound()
            return "ERROR: HID 应用注册未完成（onAppStatusChanged 未确认 registered=true）；请确认蓝牙已开启且本机支持 HID device 角色后重试"
        }

        // 成功：才把本轮 adapter/proxy 写入 registry（必修 4）。
        runCatching { hid.getConnectedDevices().forEach { BtHidRegistry.onConnected(it) } }
        BtHidRegistry.proxy = hid
        BtHidRegistry.adapter = adapter
        BtHidRegistry.mode = mode
        BtHidRegistry.sdpName = sdpName

        return buildJsonObject {
            put("status", "OK")
            put("action", "register")
            put("name", sdpName)
            put("mode", mode)
            put("descriptorBytes", descriptor.size)
            put("reportIdKeyboard", BtHidCodec.REPORT_ID_KEYBOARD)
            put("reportIdMouse", BtHidCodec.REPORT_ID_MOUSE)
            put("registered", BtHidRegistry.registered)
            put(
                "note",
                "SDP 服务已注册。目标设备必须已与本机配对，并在其系统蓝牙设置里主动连接本机的 HID 服务（Android 作为 HID device 通常由目标发起连接；部分目标连接后需在目标侧选「输入设备」用途）。连接建立前 bt_hid_send 会明确报错。",
            )
        }.toString()
    }

    /* ------------------------------ unregister ---------------------------- */

    private fun unregister(): String {
        val hid = BtHidRegistry.proxy as? BluetoothHidDevice
        if (hid == null) return "未注册：当前没有活动的蓝牙 HID 设备注册（无需注销）"
        bumpGeneration()   // 先自增，使本轮迟到回调立即失效（必修 3）
        val ok = runCatching { hid.unregisterApp() }.getOrDefault(false)
        releaseHidProxy()
        return buildJsonObject {
            put("status", if (ok) "OK" else "PARTIAL")
            put("action", "unregister")
            put("unregisterAccepted", ok)
            put("note", "已注销并恢复本机 HID host 角色；真实状态以 onAppStatusChanged 为准")
        }.toString()
    }

    /* -------------------------------- send -------------------------------- */

    private fun send(args: JsonObject): String {
        val type = (args.strArg("type") ?: "").lowercase()
        if (type !in setOf("key", "key_down", "key_up", "text", "mouse_move", "mouse_click")) {
            return "ERROR: type 仅支持 key|key_down|key_up|text|mouse_move|mouse_click"
        }
        val target = args.strArg("target") ?: return "ERROR: 缺少 target"

        val hid = BtHidRegistry.proxy as? BluetoothHidDevice
            ?: return "ERROR: 尚未注册蓝牙 HID 设备：先 bt_hid_register(name, mode)"
        if (!BtHidRegistry.registered) {
            return "ERROR: HID 应用未注册完成（onAppStatusChanged 未确认），请重新 bt_hid_register"
        }

        val connected = runCatching { hid.getConnectedDevices() }.getOrDefault(emptyList())
        BtHidRegistry.clear()
        connected.forEach { BtHidRegistry.onConnected(it) }
        if (connected.isEmpty()) {
            return "ERROR: 目标设备未连接（需先在系统蓝牙配对并连接本机 HID 服务；Android 作为 HID device 通常由目标设备发起连接，部分目标连接后需要在目标侧选「输入设备」用途）"
        }
        val hostList = connected.joinToString { d -> d.address + "(" + (d.name ?: "?") + ")" }
        val device = BtHidRegistry.resolve(target)
            ?: return "ERROR: 未找到目标设备：$target（当前已连接 HID host：$hostList）"
        if (runCatching { hid.getConnectionState(device) }.getOrDefault(-1) != BluetoothProfile.STATE_CONNECTED) {
            return "ERROR: 目标设备 ${device.address} 的 HID 连接状态不是 STATE_CONNECTED；请在对端重新连接本机 HID 服务"
        }

        val isMouseType = type in setOf("mouse_move", "mouse_click")
        val mode = BtHidRegistry.mode
        if (mode == "keyboard" && isMouseType) {
            return "ERROR: 当前注册为 keyboard 模式，无法发送鼠标报告；请 bt_hid_register(mode=combo|mouse) 重新注册"
        }
        if (mode == "mouse" && !isMouseType) {
            return "ERROR: 当前注册为 mouse 模式，无法发送键盘报告；请 bt_hid_register(mode=combo|keyboard) 重新注册"
        }

        val modifiers = BtHidCodec.parseModifiers(args.strArg("modifiers"))
            ?: return "ERROR: modifiers 含未知修饰键（支持 ctrl|shift|alt|gui|rctrl|rshift|ralt|rgui）"
        val reportId = if (isMouseType) BtHidCodec.REPORT_ID_MOUSE else BtHidCodec.REPORT_ID_KEYBOARD

        val reports = mutableListOf<ByteArray>()
        var sentChars = 0
        when (type) {
            "key", "key_down", "key_up" -> {
                if (type == "key_up") {
                    reports += BtHidCodec.keyboardReport(0, 0)   // 全零释放
                } else {
                    val keycode = args.intArg("keycode")
                        ?: return "ERROR: type=$type 需要 keycode"
                    if (!BtHidCodec.validKeycode(keycode)) {
                        return "ERROR: keycode 越界（0..255）：$keycode"
                    }
                    reports += BtHidCodec.keyboardReport(modifiers, keycode)
                    if (type == "key") reports += BtHidCodec.keyboardReport(0, 0)
                }
            }
            "text" -> {
                val text = args.strArg("text") ?: return "ERROR: type=text 需要 text"
                if (text.length > 200) return "ERROR: text 过长（上限 200 字符）"
                val usages = BtHidCodec.textToUsages(text)
                    ?: return "ERROR: text 含无法编码为 HID Usage 的字符（仅支持可打印 ASCII，中文/emoji 请用剪贴板或输入法）"
                for ((usage, shift) in usages) {
                    reports += BtHidCodec.keyboardReport(modifiers or shift, usage)
                    reports += BtHidCodec.keyboardReport(0, 0)
                }
                sentChars = text.length
            }
            "mouse_move" -> {
                reports += BtHidCodec.mouseReport(
                    dx = args.intArg("dx") ?: 0,
                    dy = args.intArg("dy") ?: 0,
                    wheel = args.intArg("wheel") ?: 0,
                )
            }
            "mouse_click" -> {
                val button = BtHidCodec.mouseButtonBit(args.strArg("button") ?: "left")
                    ?: return "ERROR: button 仅支持 left|right|middle"
                reports += BtHidCodec.mouseReport(buttons = button)
                reports += BtHidCodec.mouseReport(buttons = 0)
            }
        }

        var sent = 0
        var lastFailure = ""
        for (report in reports) {
            val ok = runCatching { hid.sendReport(device, reportId, report) }.getOrDefault(false)
            if (!ok) {
                lastFailure = "sendReport 被系统拒绝（连接可能已断开或 Report ID 与描述符不匹配）"
                break
            }
            sent++
        }
        if (lastFailure.isNotEmpty()) return "ERROR: $lastFailure（已成功发送 $sent/${reports.size} 条报告）"

        return buildJsonObject {
            put("status", "OK")
            put("action", "send")
            put("type", type)
            put("target", device.address)
            put("targetName", device.name ?: "")
            put("mode", mode)
            put("reportId", reportId)
            put("reportsSent", sent)
            if (sentChars > 0) put("charsTyped", sentChars)
            put("lastReportHex", BtHidCodec.hex(reports.last()))
            put("note", "key=按下+释放；key_down/key_up 分离；mouse_click=按下+释放两次 sendReport")
        }.toString()
    }
}
