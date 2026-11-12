@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package com.dc.andagent.tools

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import com.dc.andagent.bridge.PermissionBroker
import com.dc.andagent.usb.EspFlashCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * R74 收口刀 —— BLE GATT 通用客户端 + 经典蓝牙 SPP 串口 + USB bulk 原始传输。
 *
 *  a) ble_gatt              单工具多 action：services / read / write / notify_start / notify_read / notify_stop
 *  b) ble_gatt_descriptors  descriptor 读写（CCCD 等高级用法）
 *  c) bt_spp_*             经典蓝牙 RFCOMM 串口会话（open/read/write/close，ConcurrentHashMap 单点持有）
 *  d) usb_bulk / usb_bulk_close 零驱动原始 USB bulk / control 传输（先 usb_list 探测，claim 失败明确报错）
 *
 * 复用 R86 基建：BleUuids.expand/short（16/32/128 位 UUID）、bt_battery 的 GATT 阻塞读模式
 * （CountDownLatch + 8s/6s/6s 超时 + finally disconnect/close + TRANSPORT_LE）、
 * BleAdvCodec.parseHex / EspFlashCodec.hex。权限走 PermissionBroker 动态申请 BLUETOOTH_CONNECT
 * （ConnectivityTools.kt 的 btPermission 为 private，逻辑等价，在此内联，见战报）。
 */
val BLE_GATT_SPP_TOOLS = listOf(
    "ble_gatt",
    "ble_gatt_descriptors",
    "bt_spp_open",
    "bt_spp_read",
    "bt_spp_write",
    "bt_spp_close",
    "usb_bulk",
    "usb_bulk_close",
)

private const val BT_CONNECT = "android.permission.BLUETOOTH_CONNECT"

/* ---------------------------- pure helpers ---------------------------- */

/** GATT characteristic properties / permissions 位解码（纯，取值对齐 Android SDK 常量）。 */
object GattCodec {
    const val PROPERTY_BROADCAST = 0x01
    const val PROPERTY_READ = 0x02
    const val PROPERTY_WRITE_NO_RESPONSE = 0x04
    const val PROPERTY_WRITE = 0x08
    const val PROPERTY_NOTIFY = 0x10
    const val PROPERTY_INDICATE = 0x20
    const val PROPERTY_SIGNED_WRITE = 0x40
    const val PROPERTY_EXTENDED_PROPS = 0x80

    const val PERMISSION_READ = 0x01
    const val PERMISSION_READ_ENCRYPTED = 0x02
    const val PERMISSION_READ_ENCRYPTED_MITM = 0x04
    const val PERMISSION_WRITE = 0x10
    const val PERMISSION_WRITE_ENCRYPTED = 0x20
    const val PERMISSION_WRITE_ENCRYPTED_MITM = 0x40
    const val PERMISSION_WRITE_SIGNED = 0x80
    const val PERMISSION_WRITE_SIGNED_MITM = 0x100

    private val PROPERTY_BITS = listOf(
        PROPERTY_BROADCAST to "broadcast",
        PROPERTY_READ to "read",
        PROPERTY_WRITE_NO_RESPONSE to "write_no_response",
        PROPERTY_WRITE to "write",
        PROPERTY_NOTIFY to "notify",
        PROPERTY_INDICATE to "indicate",
        PROPERTY_SIGNED_WRITE to "signed_write",
        PROPERTY_EXTENDED_PROPS to "extended_props",
    )
    private val PERMISSION_BITS = listOf(
        PERMISSION_READ to "read",
        PERMISSION_READ_ENCRYPTED to "read_encrypted",
        PERMISSION_READ_ENCRYPTED_MITM to "read_encrypted_mitm",
        PERMISSION_WRITE to "write",
        PERMISSION_WRITE_ENCRYPTED to "write_encrypted",
        PERMISSION_WRITE_ENCRYPTED_MITM to "write_encrypted_mitm",
        PERMISSION_WRITE_SIGNED to "write_signed",
        PERMISSION_WRITE_SIGNED_MITM to "write_signed_mitm",
    )

    fun decodeProperties(bits: Int): List<String> =
        PROPERTY_BITS.filter { bits and it.first != 0 }.map { it.second }

    fun decodePermissions(bits: Int): List<String> =
        PERMISSION_BITS.filter { bits and it.first != 0 }.map { it.second }
}

/** Client Characteristic Config (0x2902) 值与开关构造（纯）。 */
object CccdCodec {
    const val UUID = "00002902-0000-1000-8000-00805f9b34fb"
    /** 0x0001 little-endian：启用通知。 */
    fun enable(): ByteArray = byteArrayOf(0x01, 0x00)
    /** 0x0000 little-endian：停用通知/指示。 */
    fun disable(): ByteArray = byteArrayOf(0x00, 0x00)
    /** 0x0002 little-endian：启用指示。 */
    fun indicate(): ByteArray = byteArrayOf(0x02, 0x00)
}

/** 经典蓝牙 SPP 默认 UUID 与校验（纯，展开复用 BleUuids）。 */
object SppCodec {
    const val DEFAULT_UUID = "00001101-0000-1000-8000-00805f9b34fb"

    /** 展开 16/32/128 位为规范 128 位小写文本；非法输入返回 null。 */
    fun parseUuid(raw: String?): String? {
        val expanded = BleUuids.expand(raw) ?: return null
        return runCatching { UUID.fromString(expanded).toString() }.getOrNull()
    }
}

/** 字节三视图 + hex 编解码 + USB 控制字段解析（纯；复用既有 parseHex/hex，不重复造）。 */
data class ValueViews(val hex: String, val utf8: String, val base64: String, val length: Int)

object HexValueCodec {
    fun hex(bytes: ByteArray): String = EspFlashCodec.hex(bytes)
    fun parseHex(raw: String?): ByteArray? = BleAdvCodec.parseHex(raw)
    fun utf8(bytes: ByteArray): String = String(bytes, Charsets.UTF_8)
    fun base64(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)
    fun views(bytes: ByteArray): ValueViews = ValueViews(hex(bytes), utf8(bytes), base64(bytes), bytes.size)

    /** 十进制或 0x 十六进制整数（USB request_type/request/value/index/endpoint_num）。 */
    fun parseIntField(raw: String?): Int? {
        val r = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return when {
            r.startsWith("0x", true) -> r.substring(2).toIntOrNull(16)
            r.all { it.isDigit() } -> r.toIntOrNull()
            else -> r.toIntOrNull(16)
        }
    }

    /** bmRequestType bit7 = 数据方向 IN。 */
    fun isControlIn(requestType: Int): Boolean = (requestType and 0x80) != 0
}

private fun JsonObjectBuilder.putViews(v: ValueViews) {
    put("hex", v.hex)
    put("utf8", v.utf8)
    put("base64", v.base64)
    put("length", v.length)
}

private val CCCD_UUID: UUID = UUID.fromString(CccdCodec.UUID)

/* --------------------------- session tables --------------------------- */

/** 经典蓝牙 SPP 会话：一个 socket + 单读线程 + 入站字节队列（单点持有）。 */
internal class SppSession(val mac: String, val socket: BluetoothSocket) {
    val queue = LinkedBlockingQueue<ByteArray>()
    @Volatile var closed = false
    private val outLock = Any()

    init {
        Thread {
            val buf = ByteArray(1024)
            while (!closed) {
                val n = try { socket.inputStream.read(buf) } catch (_: Exception) { -1 }
                if (n <= 0) break
                queue.offer(buf.copyOf(n))
            }
        }.apply { isDaemon = true; start() }
    }

    fun write(bytes: ByteArray): Int = synchronized(outLock) {
        socket.outputStream.write(bytes)
        socket.outputStream.flush()
        bytes.size
    }

    fun close() {
        closed = true
        runCatching { socket.close() }
    }
}

internal object SppSessions {
    val sessions = ConcurrentHashMap<String, SppSession>()
}

internal data class NotifiedValue(val bytes: ByteArray, val atMs: Long)

/** BLE 通知会话：保持 GATT 连接，回调把值塞进队列；active=false 后不再收集（防泄漏）。 */
internal class GattNotifySession(
    val mac: String,
    val characteristicUuid: String,
    val gatt: BluetoothGatt,
    val characteristic: BluetoothGattCharacteristic?,
    val cccd: BluetoothGattDescriptor?,
    val queue: LinkedBlockingQueue<NotifiedValue> = LinkedBlockingQueue(),
    val active: AtomicBoolean = AtomicBoolean(true),
)

internal object GattNotifySessions {
    val sessions = ConcurrentHashMap<String, GattNotifySession>()
}

/** USB bulk 会话：一次 openDevice + claimInterface，跨 read/write/transact 复用，close 释放。 */
internal class UsbBulkSession(
    val deviceId: Int,
    val connection: UsbDeviceConnection,
    val iface: UsbInterface,
)

internal object UsbBulkSessions {
    val sessions = ConcurrentHashMap<Int, UsbBulkSession>()
}

/* -------------------------------- tool -------------------------------- */

@SuppressLint("MissingPermission")
class BleGattSppTool(
    private val context: Context,
    private val permissions: PermissionBroker,
    override val name: String,
) : Tool {

    override val description: String = when (name) {
        "ble_gatt" ->
            "BLE GATT 通用客户端：action=services 连设备列服务/特征（uuid/properties 位/权限）；read 读特征值（hex+UTF-8+base64 三视图）；" +
                "write 写特征值（value_hex|value_text，write_type=write|write_no_rsp，destructive）；notify_start 写 CCCD 0x2902=0x0001 并注册回调，" +
                "notify_read 返回窗口内通知值（多包给 count+间隔），notify_stop 写 0x0000 并注销。连接/发现/读超时 8s/6s/6s，用完 finally disconnect+close。"
        "ble_gatt_descriptors" ->
            "BLE GATT descriptor 读写（Client Characteristic Config 0x2902 等高级用法）：action=read 读值，write 写值（value 十六进制，destructive）。" +
                "经 service/characteristic 定位 descriptor；省略 descriptor 时默认 2902。"
        "bt_spp_open" ->
            "经典蓝牙 SPP 串口：createRfcommSocketToServiceRecord（secure=false 走 insecure 变体）连接设备，12s 连接超时（超时关闭 socket 取消阻塞的 connect），" +
                "返回 handle。会话由 ConcurrentHashMap 单点持有，供 bt_spp_read/write 复用。"
        "bt_spp_read" -> "读取 SPP 串口字节（默认 1s 窗口、4096 字节），返回 hex + UTF-8 + base64 三视图；窗口内无数据如实 NO_DATA。"
        "bt_spp_write" -> "向 SPP 串口发送 data_hex 或 data_text（destructive）。"
        "bt_spp_close" -> "关闭 SPP 会话并释放 socket。"
        "usb_bulk" ->
            "零驱动原始 USB 访问（先 usb_list 探测）：action=read 从 bulk IN 端点读，write 向 bulk OUT 端点写 data_hex（destructive），" +
                "transact 发控制传输 control_request(request_type/request/request_value/index，data_hex 可选，destructive)。" +
                "内部一次 openDevice+claimInterface 并跨调用复用；claim 不到接口明确 ERROR 不虚报成功。用完 usb_bulk_close 释放。"
        "usb_bulk_close" -> "释放 usb_bulk claim 的接口与 connection：指定 device_id/vid_pid 只关该设备，省略则释放全部会话。"
        else -> "BLE GATT / SPP / USB bulk 工具"
    }

    override val parameters: JsonObject = when (name) {
        "ble_gatt" -> schemaOf(
            "action" to strParam("services（默认）| read | write | notify_start | notify_read | notify_stop"),
            "mac" to strParam("设备 MAC 地址"),
            "service" to strParam("服务 UUID，支持 16/32/128 位（read/write/notify_start 必填）"),
            "characteristic" to strParam("特征 UUID，支持 16/32/128 位（read/write/notify_start/notify_read/notify_stop 必填）"),
            "value_hex" to strParam("action=write 的十六进制值；与 value_text 二选一"),
            "value_text" to strParam("action=write 的 UTF-8 文本；与 value_hex 二选一"),
            "write_type" to strParam("write（默认，需应答）| write_no_rsp（无应答）"),
            "timeout_ms" to intParam("action=notify_read 的等待窗口毫秒，默认 3000，范围 0..60000"),
        )
        "ble_gatt_descriptors" -> schemaOf(
            "action" to strParam("read（默认）| write"),
            "mac" to strParam("设备 MAC 地址"),
            "service" to strParam("服务 UUID，支持 16/32/128 位"),
            "characteristic" to strParam("特征 UUID，支持 16/32/128 位"),
            "descriptor" to strParam("descriptor UUID，默认 2902（Client Characteristic Config）"),
            "value" to strParam("action=write 的十六进制值（如 CCCD 开启 0100）"),
        )
        "bt_spp_open" -> schemaOf(
            "mac" to strParam("设备 MAC 地址"),
            "uuid" to strParam("SPP 服务 UUID，默认 00001101-0000-1000-8000-00805f9b34fb"),
            "secure" to boolParam("true（默认）走 createRfcommSocketToServiceRecord；false 走 insecure 变体"),
        )
        "bt_spp_read" -> schemaOf(
            "handle" to strParam("bt_spp_open 返回的 handle"),
            "timeout_ms" to intParam("等待毫秒，默认 1000，范围 0..60000"),
            "max_bytes" to intParam("最大字节数，默认 4096，范围 1..65536"),
            required = listOf("handle"),
        )
        "bt_spp_write" -> schemaOf(
            "handle" to strParam("bt_spp_open 返回的 handle"),
            "data_hex" to strParam("十六进制字节；与 data_text 二选一"),
            "data_text" to strParam("UTF-8 文本；与 data_hex 二选一"),
            required = listOf("handle"),
        )
        "bt_spp_close" -> schemaOf(
            "handle" to strParam("bt_spp_open 返回的 handle"),
            required = listOf("handle"),
        )
        "usb_bulk" -> schemaOf(
            "action" to strParam("read（默认）| write | transact"),
            "device_id" to intParam("usb_list 的 deviceId；与 vid_pid 二选一"),
            "vid_pid" to strParam("VID:PID，如 1a86:7523；多台同款时用 device_id"),
            "endpoint_num" to intParam("read/write 的端点号（见 usb_list 的 endpointDetails.number）"),
            "max_bytes" to intParam("action=read 最大字节数，默认 4096，范围 1..65536"),
            "timeout_ms" to intParam("read/transact 超时毫秒，默认 1000"),
            "data_hex" to strParam("action=write 的十六进制字节；transact OUT 方向的可选数据"),
            "request_type" to intParam("action=transact 的 bmRequestType，如 0xC0(IN)/0x40(OUT)，支持十进制或 0x 十六进制"),
            "request" to intParam("action=transact 的 bRequest"),
            "request_value" to intParam("action=transact 的 wValue，默认 0"),
            "index" to intParam("action=transact 的 wIndex，默认 0"),
            "length" to intParam("action=transact 且 IN 方向时读回字节数，默认 4096"),
        )
        "usb_bulk_close" -> schemaOf(
            "device_id" to intParam("可选：只释放指定 deviceId 的会话；省略则释放全部"),
            "vid_pid" to strParam("可选：按 VID:PID 只释放指定设备会话；省略则释放全部"),
        )
        else -> schemaOf()
    }

    // destructive 是 Tool 级属性（无 per-action 通道）：含写动作的工具整体过确认闸，
    // 与 R86 的 ble_advertise/bt_audio_route 多 action 工具一致（见战报）。
    override val destructive: Boolean = name in setOf("ble_gatt", "ble_gatt_descriptors", "bt_spp_write", "usb_bulk")

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        runCatching {
            when (name) {
                "ble_gatt" -> bleGatt(args)
                "ble_gatt_descriptors" -> bleGattDescriptors(args)
                "bt_spp_open" -> btSppOpen(args)
                "bt_spp_read" -> btSppRead(args)
                "bt_spp_write" -> btSppWrite(args)
                "bt_spp_close" -> btSppClose(args)
                "usb_bulk" -> usbBulk(args)
                "usb_bulk_close" -> usbBulkClose(args)
                else -> "ERROR: 未知工具 $name"
            }
        }.getOrElse { "ERROR: $name 执行失败：${it.message ?: it.javaClass.simpleName}" }
    }

    private val btAdapter get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private val usbMgr get() = context.getSystemService(Context.USB_SERVICE) as? UsbManager

    private suspend fun ensureConnect(): String? {
        if (btAdapter == null) return "ERROR: 蓝牙不可用（无蓝牙硬件）"
        if (Build.VERSION.SDK_INT >= 31 && !permissions.ensure(listOf(BT_CONNECT))) {
            return "ERROR: 缺少 BLUETOOTH_CONNECT 权限（拒绝或永久拒绝，可在系统设置开启）"
        }
        return null
    }

    private fun requireEnabled(): String? {
        val adapter = btAdapter ?: return "ERROR: 蓝牙不可用（无蓝牙硬件）"
        if (!adapter.isEnabled) return "ERROR: 蓝牙未开启，请先在系统设置中开启蓝牙"
        return null
    }

    private fun resolveUuid(raw: String?): UUID? =
        BleUuids.expand(raw)?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    private fun deviceFor(macRaw: String?): Pair<BluetoothDevice?, String?> {
        val mac = macRaw?.uppercase() ?: return null to "ERROR: 缺少 mac"
        if (!BluetoothAdapter.checkBluetoothAddress(mac)) return null to "ERROR: MAC 地址格式无效"
        val device = runCatching { btAdapter?.getRemoteDevice(mac) }.getOrNull()
            ?: return null to "ERROR: 无法获取设备 $mac（蓝牙不可用）"
        return device to null
    }

    private fun notifyKey(mac: String, charUuid: UUID): String = mac.uppercase() + "|" + charUuid.toString()

    private fun closeGatt(gatt: BluetoothGatt?) {
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
    }

    /* -------------------- 一次性 GATT 连接 + 发现 -------------------- */

    /** 连设备并发现服务；返回 (gatt, error)。调用方负责 finally close。 */
    private fun connectDiscover(device: BluetoothDevice, cb: OneShotGattCallback): Pair<BluetoothGatt?, String?> {
        val gatt = runCatching { device.connectGatt(context, false, cb, BluetoothDevice.TRANSPORT_LE) }.getOrNull()
            ?: return null to "GATT 连接无法启动（设备可能仅支持经典蓝牙，或蓝牙栈忙）"
        if (!cb.connectLatch.await(8, TimeUnit.SECONDS)) return gatt to "GATT 连接超时（8s）"
        if (cb.connectState.get() != BluetoothProfile.STATE_CONNECTED) {
            return gatt to "GATT 未连接（status=${cb.connectStatus.get()}，常见原因：设备不支持 BLE 或已被其他主机连接）"
        }
        if (!runCatching { gatt.discoverServices() }.getOrDefault(false)) return gatt to "无法发起服务发现"
        if (!cb.discoverLatch.await(6, TimeUnit.SECONDS)) return gatt to "服务发现超时（6s）"
        if (cb.discoverStatus.get() != BluetoothGatt.GATT_SUCCESS) {
            return gatt to "服务发现失败（status=${cb.discoverStatus.get()}）"
        }
        return gatt to null
    }

    private class OneShotGattCallback : BluetoothGattCallback() {
        val connectLatch = CountDownLatch(1)
        val discoverLatch = CountDownLatch(1)
        val resultLatch = CountDownLatch(1)
        val connectState = AtomicInteger(-1)
        val connectStatus = AtomicInteger(-1)
        val discoverStatus = AtomicInteger(-1)
        val resultStatus = AtomicInteger(-1)
        @Volatile var resultValue: ByteArray? = null
        private val delivered = AtomicBoolean(false)

        private fun signal(status: Int, value: ByteArray?) {
            if (delivered.compareAndSet(false, true)) {
                resultStatus.set(status)
                resultValue = value?.copyOf()
                resultLatch.countDown()
            }
        }

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            connectState.set(newState); connectStatus.set(status); connectLatch.countDown()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            discoverStatus.set(status); discoverLatch.countDown()
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) =
            signal(status, characteristic.value)

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) =
            signal(status, value)

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) =
            signal(status, characteristic.value)

        @Suppress("DEPRECATION")
        override fun onDescriptorRead(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) =
            signal(status, descriptor.value)

        // API 33+ 重载：形参顺序是 (gatt, descriptor, status, value)，与 characteristic 版相反。
        override fun onDescriptorRead(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int, value: ByteArray) =
            signal(status, value)

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) =
            signal(status, descriptor.value)
    }

    /* ----------------------------- ble_gatt ---------------------------- */

    private suspend fun bleGatt(args: JsonObject): String {
        ensureConnect()?.let { return it }
        requireEnabled()?.let { return it }
        return when ((args.strArg("action") ?: "services").lowercase()) {
            "services" -> gattServices(args)
            "read" -> gattRead(args)
            "write" -> gattWrite(args)
            "notify_start" -> gattNotifyStart(args)
            "notify_read" -> gattNotifyRead(args)
            "notify_stop" -> gattNotifyStop(args)
            else -> "ERROR: action 仅支持 services|read|write|notify_start|notify_read|notify_stop"
        }
    }

    private fun gattServices(args: JsonObject): String {
        val (device, err) = deviceFor(args.strArg("mac")); err?.let { return it }
        val cb = OneShotGattCallback()
        val (gatt, cerr) = connectDiscover(device!!, cb)
        if (cerr != null) { closeGatt(gatt); return "ERROR: $cerr" }
        return try {
            buildJsonObject {
                put("status", "OK")
                put("action", "services")
                put("mac", device.address)
                put("serviceCount", gatt!!.services.size)
                putJsonArray("services") {
                    gatt.services.forEach { svc ->
                        add(buildJsonObject {
                            put("uuid", BleUuids.short(svc.uuid.toString()))
                            put("uuidFull", svc.uuid.toString())
                            put("type", if (svc.type == BluetoothGattService.SERVICE_TYPE_PRIMARY) "primary" else "secondary")
                            putJsonArray("characteristics") {
                                svc.characteristics.forEach { c ->
                                    add(buildJsonObject {
                                        put("uuid", BleUuids.short(c.uuid.toString()))
                                        put("uuidFull", c.uuid.toString())
                                        put("properties", c.properties)
                                        putJsonArray("propertyNames") {
                                            GattCodec.decodeProperties(c.properties).forEach { add(JsonPrimitive(it)) }
                                        }
                                        put("permissions", c.permissions)
                                        putJsonArray("permissionNames") {
                                            GattCodec.decodePermissions(c.permissions).forEach { add(JsonPrimitive(it)) }
                                        }
                                        putJsonArray("descriptors") {
                                            c.descriptors.forEach { d ->
                                                add(buildJsonObject {
                                                    put("uuid", BleUuids.short(d.uuid.toString()))
                                                    put("uuidFull", d.uuid.toString())
                                                })
                                            }
                                        }
                                    })
                                }
                            }
                        })
                    }
                }
                put("note", "properties/permissions 为原始位掩码；propertyNames/permissionNames 为解码后的可读名")
            }.toString()
        } finally {
            closeGatt(gatt)
        }
    }

    private fun gattRead(args: JsonObject): String {
        val (device, err) = deviceFor(args.strArg("mac")); err?.let { return it }
        val serviceUuid = resolveUuid(args.strArg("service")) ?: return "ERROR: service UUID 不合法或缺失"
        val charUuid = resolveUuid(args.strArg("characteristic")) ?: return "ERROR: characteristic UUID 不合法或缺失"
        val cb = OneShotGattCallback()
        val (gatt, cerr) = connectDiscover(device!!, cb)
        if (cerr != null) { closeGatt(gatt); return "ERROR: $cerr" }
        return try {
            val service = gatt!!.getService(serviceUuid)
                ?: return "ERROR: 未找到服务 ${BleUuids.short(serviceUuid.toString())}（先用 action=services 列出）"
            val char = service.getCharacteristic(charUuid)
                ?: return "ERROR: 服务里没有特征 ${BleUuids.short(charUuid.toString())}"
            if (!runCatching { gatt.readCharacteristic(char) }.getOrDefault(false)) return "ERROR: 读取特征被拒绝"
            if (!cb.resultLatch.await(6, TimeUnit.SECONDS)) return "ERROR: 读取特征超时（6s）"
            if (cb.resultStatus.get() != BluetoothGatt.GATT_SUCCESS) {
                return "ERROR: 读取特征失败（status=${cb.resultStatus.get()}）"
            }
            val value = cb.resultValue ?: ByteArray(0)
            buildJsonObject {
                put("status", "OK")
                put("action", "read")
                put("mac", device.address)
                put("service", BleUuids.short(serviceUuid.toString()))
                put("characteristic", BleUuids.short(charUuid.toString()))
                putViews(HexValueCodec.views(value))
            }.toString()
        } finally {
            closeGatt(gatt)
        }
    }

    private fun gattWrite(args: JsonObject): String {
        val (device, err) = deviceFor(args.strArg("mac")); err?.let { return it }
        val serviceUuid = resolveUuid(args.strArg("service")) ?: return "ERROR: service UUID 不合法或缺失"
        val charUuid = resolveUuid(args.strArg("characteristic")) ?: return "ERROR: characteristic UUID 不合法或缺失"
        val text = args.strArg("value_text")
        val hex = args.strArg("value_hex")
        if ((text == null) == (hex == null)) return "ERROR: value_hex 与 value_text 必须且只能填写一个"
        val bytes = if (text != null) text.toByteArray(Charsets.UTF_8)
            else HexValueCodec.parseHex(hex) ?: return "ERROR: value_hex 不是合法十六进制"
        if (bytes.isEmpty()) return "ERROR: 写入值为空"
        val writeTypeName = (args.strArg("write_type") ?: "write").lowercase()
        val writeType = when (writeTypeName) {
            "write" -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            "write_no_rsp", "write_no_response" -> BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            else -> return "ERROR: write_type 仅支持 write|write_no_rsp"
        }
        val cb = OneShotGattCallback()
        val (gatt, cerr) = connectDiscover(device!!, cb)
        if (cerr != null) { closeGatt(gatt); return "ERROR: $cerr" }
        return try {
            val service = gatt!!.getService(serviceUuid) ?: return "ERROR: 未找到服务 ${BleUuids.short(serviceUuid.toString())}"
            val char = service.getCharacteristic(charUuid) ?: return "ERROR: 服务里没有特征 ${BleUuids.short(charUuid.toString())}"
            char.value = bytes
            char.writeType = writeType
            if (!runCatching { gatt.writeCharacteristic(char) }.getOrDefault(false)) return "ERROR: 写入特征被拒绝"
            if (!cb.resultLatch.await(6, TimeUnit.SECONDS)) return "ERROR: 写入特征超时（6s）"
            val st = cb.resultStatus.get()
            buildJsonObject {
                put("status", if (st == BluetoothGatt.GATT_SUCCESS) "OK" else "ERROR")
                put("action", "write")
                put("mac", device.address)
                put("service", BleUuids.short(serviceUuid.toString()))
                put("characteristic", BleUuids.short(charUuid.toString()))
                put("writeType", writeTypeName)
                put("written", bytes.size)
                put("gattStatus", st)
            }.toString()
        } finally {
            closeGatt(gatt)
        }
    }

    private fun gattNotifyStart(args: JsonObject): String {
        val (device, err) = deviceFor(args.strArg("mac")); err?.let { return it }
        val serviceUuid = resolveUuid(args.strArg("service")) ?: return "ERROR: service UUID 不合法或缺失"
        val charUuid = resolveUuid(args.strArg("characteristic")) ?: return "ERROR: characteristic UUID 不合法或缺失"
        val key = notifyKey(device!!.address, charUuid)
        if (GattNotifySessions.sessions.containsKey(key)) {
            return "ERROR: 该 (mac,characteristic) 已有活跃通知会话；先 notify_stop"
        }

        val connectLatch = CountDownLatch(1)
        val discoverLatch = CountDownLatch(1)
        val cccdLatch = CountDownLatch(1)
        val connectState = AtomicInteger(-1)
        val connectStatus = AtomicInteger(-1)
        val discoverStatus = AtomicInteger(-1)
        val cccdStatus = AtomicInteger(-1)
        val setupError = AtomicReference<String?>(null)
        val charRef = AtomicReference<BluetoothGattCharacteristic?>(null)
        val cccdRef = AtomicReference<BluetoothGattDescriptor?>(null)
        val queue = LinkedBlockingQueue<NotifiedValue>()
        val active = AtomicBoolean(true)

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                connectState.set(newState); connectStatus.set(status); connectLatch.countDown()
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                discoverStatus.set(status)
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    val service = gatt.getService(serviceUuid)
                    if (service == null) {
                        setupError.set("未找到服务 ${BleUuids.short(serviceUuid.toString())}")
                    } else {
                        val char = service.getCharacteristic(charUuid)
                        if (char == null) {
                            setupError.set("服务里没有特征 ${BleUuids.short(charUuid.toString())}")
                        } else {
                            val cccd = char.getDescriptor(CCCD_UUID)
                            if (cccd == null) {
                                setupError.set("该特征没有 CCCD 0x2902 描述符，无法启用通知（写特征本身是错的）")
                            } else {
                                charRef.set(char); cccdRef.set(cccd)
                                gatt.setCharacteristicNotification(char, true)
                                cccd.value = CccdCodec.enable()
                                if (!runCatching { gatt.writeDescriptor(cccd) }.getOrDefault(false)) {
                                    setupError.set("写 CCCD 0x0001 被拒绝")
                                    cccdLatch.countDown()
                                }
                            }
                        }
                    }
                }
                discoverLatch.countDown()
            }

            override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                cccdStatus.set(status); cccdLatch.countDown()
            }

            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                collect(characteristic.value)
            }

            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
                collect(value)
            }

            fun collect(raw: ByteArray?) {
                if (active.get() && raw != null) queue.offer(NotifiedValue(raw.copyOf(), System.currentTimeMillis()))
            }
        }

        val gatt = runCatching { device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE) }.getOrNull()
            ?: return "ERROR: GATT 连接无法启动"
        var stored = false
        return try {
            if (!connectLatch.await(8, TimeUnit.SECONDS)) return "ERROR: GATT 连接超时（8s）"
            if (connectState.get() != BluetoothProfile.STATE_CONNECTED) {
                return "ERROR: GATT 未连接（status=${connectStatus.get()}）"
            }
            if (!runCatching { gatt.discoverServices() }.getOrDefault(false)) return "ERROR: 无法发起服务发现"
            if (!discoverLatch.await(6, TimeUnit.SECONDS)) return "ERROR: 服务发现超时（6s）"
            setupError.get()?.let { return "ERROR: $it" }
            if (!cccdLatch.await(6, TimeUnit.SECONDS)) return "ERROR: 写 CCCD 0x0001 超时（6s）"
            if (cccdStatus.get() != BluetoothGatt.GATT_SUCCESS) {
                return "ERROR: 写 CCCD 0x0001 失败（status=${cccdStatus.get()}）"
            }
            GattNotifySessions.sessions[key] = GattNotifySession(
                device.address, charUuid.toString(), gatt, charRef.get(), cccdRef.get(), queue, active,
            )
            stored = true
            buildJsonObject {
                put("status", "OK")
                put("action", "notify_start")
                put("mac", device.address)
                put("service", BleUuids.short(serviceUuid.toString()))
                put("characteristic", BleUuids.short(charUuid.toString()))
                put("cccd", "0x0001")
                put("descriptor", "0x2902")
                put("note", "通知已启用并注册回调；用 notify_read 收值，notify_stop 写 0x0000 并注销（回调收集随之停止）")
            }.toString()
        } finally {
            if (!stored) closeGatt(gatt)
        }
    }

    private fun gattNotifyRead(args: JsonObject): String {
        val mac = args.strArg("mac")?.uppercase() ?: return "ERROR: 缺少 mac"
        val charUuid = resolveUuid(args.strArg("characteristic")) ?: return "ERROR: characteristic UUID 不合法或缺失"
        val key = notifyKey(mac, charUuid)
        val session = GattNotifySessions.sessions[key]
            ?: return "ERROR: 没有活跃的通知会话；先 notify_start"
        val timeout = (args.intArg("timeout_ms") ?: 3000).coerceIn(0, 60000)
        val values = mutableListOf<NotifiedValue>()
        session.queue.poll(timeout.toLong(), TimeUnit.MILLISECONDS)?.let { values.add(it) }
        session.queue.drainTo(values)
        return buildJsonObject {
            put("status", if (values.isEmpty()) "NO_DATA" else "OK")
            put("action", "notify_read")
            put("mac", mac)
            put("characteristic", BleUuids.short(charUuid.toString()))
            put("count", values.size)
            putJsonArray("values") {
                values.forEach { v ->
                    add(buildJsonObject { putViews(HexValueCodec.views(v.bytes)); put("atMs", v.atMs) })
                }
            }
            putJsonArray("intervalsMs") {
                for (i in 1 until values.size) add(JsonPrimitive(values[i].atMs - values[i - 1].atMs))
            }
            if (values.isEmpty()) {
                put("note", "窗口 ${timeout}ms 内未收到通知；确认对端在发送、CCCD 已启用，或增大 timeout_ms")
            }
        }.toString()
    }

    private fun gattNotifyStop(args: JsonObject): String {
        val mac = args.strArg("mac")?.uppercase() ?: return "ERROR: 缺少 mac"
        val charUuid = resolveUuid(args.strArg("characteristic")) ?: return "ERROR: characteristic UUID 不合法或缺失"
        val key = notifyKey(mac, charUuid)
        val session = GattNotifySessions.sessions.remove(key)
            ?: return "ERROR: 没有活跃的通知会话；可能已 notify_stop"
        // 先停收集（防泄漏），再写 CCCD 0x0000、注销、关 GATT。
        session.active.set(false)
        try {
            session.characteristic?.let { runCatching { session.gatt.setCharacteristicNotification(it, false) } }
            session.cccd?.let { d ->
                runCatching {
                    d.value = CccdCodec.disable()
                    session.gatt.writeDescriptor(d)
                }
            }
        } finally {
            closeGatt(session.gatt)
        }
        return buildJsonObject {
            put("status", "OK")
            put("action", "notify_stop")
            put("mac", mac)
            put("characteristic", BleUuids.short(charUuid.toString()))
            put("cccd", "0x0000")
            put("note", "已写 CCCD 0x0000、注销回调并关闭 GATT；回调值收集已停止（notify_read 不再返回新值）")
        }.toString()
    }

    /* ------------------------ ble_gatt_descriptors --------------------- */

    private suspend fun bleGattDescriptors(args: JsonObject): String {
        ensureConnect()?.let { return it }
        requireEnabled()?.let { return it }
        val action = (args.strArg("action") ?: "read").lowercase()
        if (action !in setOf("read", "write")) return "ERROR: action 仅支持 read|write"
        val (device, err) = deviceFor(args.strArg("mac")); err?.let { return it }
        val serviceUuid = resolveUuid(args.strArg("service")) ?: return "ERROR: service UUID 不合法或缺失"
        val charUuid = resolveUuid(args.strArg("characteristic")) ?: return "ERROR: characteristic UUID 不合法或缺失"
        val descUuid = resolveUuid(args.strArg("descriptor") ?: "2902") ?: return "ERROR: descriptor UUID 不合法"
        val writeBytes = if (action == "write") {
            HexValueCodec.parseHex(args.strArg("value")) ?: return "ERROR: value 不是合法十六进制（descriptor 写入用 hex，如 0100）"
        } else null
        val cb = OneShotGattCallback()
        val (gatt, cerr) = connectDiscover(device!!, cb)
        if (cerr != null) { closeGatt(gatt); return "ERROR: $cerr" }
        return try {
            val service = gatt!!.getService(serviceUuid) ?: return "ERROR: 未找到服务 ${BleUuids.short(serviceUuid.toString())}"
            val char = service.getCharacteristic(charUuid) ?: return "ERROR: 未找到特征 ${BleUuids.short(charUuid.toString())}"
            val desc = char.getDescriptor(descUuid)
                ?: return "ERROR: 该特征没有描述符 ${BleUuids.short(descUuid.toString())}（CCCD 为 2902）"
            if (action == "read") {
                if (!runCatching { gatt.readDescriptor(desc) }.getOrDefault(false)) return "ERROR: 读取 descriptor 被拒绝"
                if (!cb.resultLatch.await(6, TimeUnit.SECONDS)) return "ERROR: 读取 descriptor 超时（6s）"
                if (cb.resultStatus.get() != BluetoothGatt.GATT_SUCCESS) {
                    return "ERROR: 读取 descriptor 失败（status=${cb.resultStatus.get()}）"
                }
                buildJsonObject {
                    put("status", "OK")
                    put("action", "read")
                    put("mac", device.address)
                    put("descriptor", BleUuids.short(descUuid.toString()))
                    putViews(HexValueCodec.views(cb.resultValue ?: ByteArray(0)))
                }.toString()
            } else {
                desc.value = writeBytes!!
                if (!runCatching { gatt.writeDescriptor(desc) }.getOrDefault(false)) return "ERROR: 写入 descriptor 被拒绝"
                if (!cb.resultLatch.await(6, TimeUnit.SECONDS)) return "ERROR: 写入 descriptor 超时（6s）"
                buildJsonObject {
                    put("status", if (cb.resultStatus.get() == BluetoothGatt.GATT_SUCCESS) "OK" else "ERROR")
                    put("action", "write")
                    put("mac", device.address)
                    put("descriptor", BleUuids.short(descUuid.toString()))
                    put("written", writeBytes.size)
                    put("gattStatus", cb.resultStatus.get())
                }.toString()
            }
        } finally {
            closeGatt(gatt)
        }
    }

    /* ------------------------------- bt_spp ---------------------------- */

    private suspend fun btSppOpen(args: JsonObject): String {
        ensureConnect()?.let { return it }
        requireEnabled()?.let { return it }
        val mac = args.strArg("mac")?.uppercase() ?: return "ERROR: 缺少 mac"
        if (!BluetoothAdapter.checkBluetoothAddress(mac)) return "ERROR: MAC 地址格式无效"
        val uuidText = SppCodec.parseUuid(args.strArg("uuid") ?: SppCodec.DEFAULT_UUID)
            ?: return "ERROR: uuid 不是合法 UUID"
        val secure = args.boolArg("secure") != false
        val device = runCatching { btAdapter?.getRemoteDevice(mac) }.getOrNull() ?: return "ERROR: 蓝牙不可用"
        val socket = runCatching {
            val u = UUID.fromString(uuidText)
            if (secure) device.createRfcommSocketToServiceRecord(u)
            else device.createInsecureRfcommSocketToServiceRecord(u)
        }.getOrElse {
            // createRfcommSocketToServiceRecord 是公开 SDK API（javap 实证），targetSdk28 无需反射；
            // 若个别 ROM 仍抛异常，如实标注并引导 insecure 变体，不静默吞掉。
            return "ERROR: 创建 RFCOMM socket 失败：${it.message ?: it.javaClass.simpleName}（可试 secure=false 走 insecure 变体）"
        }
        val done = CountDownLatch(1)
        val err = AtomicReference<Throwable?>(null)
        Thread {
            try { socket.connect() } catch (t: Throwable) { err.set(t) } finally { done.countDown() }
        }.apply { isDaemon = true; start() }
        if (!done.await(12, TimeUnit.SECONDS)) {
            runCatching { socket.close() }
            return "ERROR: SPP 连接超时（12s，已关闭 socket 以取消阻塞的 connect）"
        }
        val connectErr = err.get()
        if (connectErr != null) {
            runCatching { socket.close() }
            return "ERROR: SPP 连接失败：${connectErr.message ?: connectErr.javaClass.simpleName}"
        }
        if (!socket.isConnected) {
            runCatching { socket.close() }
            return "ERROR: SPP 未连接（对端可能未开机/未开放该 UUID/拒绝配对）"
        }
        val handle = UUID.randomUUID().toString()
        SppSessions.sessions[handle] = SppSession(mac, socket)
        return buildJsonObject {
            put("status", "OK")
            put("handle", handle)
            put("mac", mac)
            put("uuid", uuidText)
            put("secure", secure)
            put("note", "会话单点持有；用 bt_spp_read/write 收发，bt_spp_close 释放")
        }.toString()
    }

    private fun btSppRead(args: JsonObject): String {
        val handle = args.strArg("handle") ?: return "ERROR: 缺少 handle"
        val session = SppSessions.sessions[handle] ?: return "ERROR: handle 不存在或已关闭"
        val timeout = (args.intArg("timeout_ms") ?: 1000).coerceIn(0, 60000)
        val max = (args.intArg("max_bytes") ?: 4096).coerceIn(1, 65536)
        val out = ByteArrayOutputStream()
        session.queue.poll(timeout.toLong(), TimeUnit.MILLISECONDS)?.let { first ->
            out.write(first, 0, minOf(first.size, max))
        }
        while (out.size() < max) {
            val chunk = session.queue.poll() ?: break
            out.write(chunk, 0, minOf(chunk.size, max - out.size()))
        }
        val bytes = out.toByteArray()
        return buildJsonObject {
            put("status", if (bytes.isEmpty()) "NO_DATA" else "OK")
            put("handle", handle)
            put("mac", session.mac)
            putViews(HexValueCodec.views(bytes))
            if (bytes.isEmpty()) put("note", "窗口 ${timeout}ms 内无数据；对端可能未发送或会话已断开")
        }.toString()
    }

    private fun btSppWrite(args: JsonObject): String {
        val handle = args.strArg("handle") ?: return "ERROR: 缺少 handle"
        val session = SppSessions.sessions[handle] ?: return "ERROR: handle 不存在或已关闭"
        val text = args.strArg("data_text")
        val hex = args.strArg("data_hex")
        if ((text == null) == (hex == null)) return "ERROR: data_hex 与 data_text 必须且只能填写一个"
        val bytes = if (text != null) text.toByteArray(Charsets.UTF_8)
            else HexValueCodec.parseHex(hex) ?: return "ERROR: data_hex 不是合法十六进制"
        if (bytes.isEmpty()) return "ERROR: 写入数据为空"
        val written = runCatching { session.write(bytes) }
            .getOrElse { return "ERROR: SPP 写入失败：${it.message ?: it.javaClass.simpleName}" }
        return buildJsonObject {
            put("status", "OK")
            put("handle", handle)
            put("written", written)
        }.toString()
    }

    private fun btSppClose(args: JsonObject): String {
        val handle = args.strArg("handle") ?: return "ERROR: 缺少 handle"
        val session = SppSessions.sessions.remove(handle) ?: return "ERROR: handle 不存在或已关闭"
        runCatching { session.close() }
        return buildJsonObject { put("status", "OK"); put("handle", handle) }.toString()
    }

    /* ------------------------------ usb_bulk --------------------------- */

    private fun usbBulk(args: JsonObject): String {
        val mgr = usbMgr ?: return "ERROR: USB 服务不可用"
        val action = (args.strArg("action") ?: "read").lowercase()
        if (action !in setOf("read", "write", "transact")) return "ERROR: action 仅支持 read|write|transact"
        val id = args.intArg("device_id")
        val pair = args.strArg("vid_pid")?.lowercase()?.removePrefix("0x")?.replace("0x", "")
        if (id == null && pair == null) return "ERROR: 需要 device_id 或 vid_pid；先 usb_list 探测"
        val matches = mgr.deviceList.values.filter { d ->
            if (id != null) d.deviceId == id else "%04x:%04x".format(d.vendorId, d.productId) == pair
        }
        if (matches.isEmpty()) return "ERROR: 未找到 USB 设备；先 usb_list 探测"
        if (matches.size > 1) return "ERROR: 多个设备匹配 VID:PID，请使用 device_id"
        val device = matches.single()
        val session = try {
            usbBulkSession(device)
        } catch (e: IllegalArgumentException) {
            return "ERROR: ${e.message}"
        }
        return when (action) {
            "read" -> usbBulkRead(device, session, args)
            "write" -> usbBulkWrite(device, session, args)
            else -> usbBulkTransact(session, args)
        }
    }

    private fun usbBulkSession(device: UsbDevice): UsbBulkSession = synchronized(UsbBulkSessions.sessions) {
        val existing = UsbBulkSessions.sessions[device.deviceId]
        if (existing != null) return@synchronized existing
        val mgr = usbMgr ?: throw IllegalArgumentException("USB 服务不可用")
        if (!mgr.hasPermission(device)) {
            throw IllegalArgumentException("USB 设备尚未授权；先 otg_request(permission=usb)（deviceId=${device.deviceId}）")
        }
        val conn = mgr.openDevice(device) ?: throw IllegalArgumentException("UsbManager.openDevice 失败；确认授权后重试")
        val iface = (0 until device.interfaceCount).map { device.getInterface(it) }.firstOrNull()
        if (iface == null) {
            conn.close()
            throw IllegalArgumentException("设备没有可用接口")
        }
        if (!conn.claimInterface(iface, true)) {
            conn.close()
            throw IllegalArgumentException("claimInterface 失败：接口 ${iface.id} 被占用或系统拒绝；无法建立 bulk 会话（绝不虚报成功）")
        }
        val s = UsbBulkSession(device.deviceId, conn, iface)
        UsbBulkSessions.sessions[device.deviceId] = s
        s
    }

    private fun findEndpoint(device: UsbDevice, num: Int, inDir: Boolean): UsbEndpoint? {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            for (e in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(e)
                if (ep.endpointNumber == num && (ep.direction == UsbConstants.USB_DIR_IN) == inDir) return ep
            }
        }
        return null
    }

    private fun usbBulkRead(device: UsbDevice, session: UsbBulkSession, args: JsonObject): String {
        val num = HexValueCodec.parseIntField(args.strArg("endpoint_num"))
            ?: return "ERROR: endpoint_num 缺失或非法"
        val max = (args.intArg("max_bytes") ?: 4096).coerceIn(1, 65536)
        val timeout = (args.intArg("timeout_ms") ?: 1000).coerceIn(1, 60000)
        val ep = findEndpoint(device, num, true)
            ?: return "ERROR: 未找到 endpoint_num=$num 的 IN 端点；先看 usb_list 的 endpointDetails（direction=in）"
        val buf = ByteArray(max)
        val n = runCatching { session.connection.bulkTransfer(ep, buf, max, timeout) }.getOrDefault(-1)
        val got = if (n > 0) buf.copyOf(n) else ByteArray(0)
        return buildJsonObject {
            put("status", if (n >= 0) "OK" else "ERROR")
            put("action", "read")
            put("deviceId", device.deviceId)
            put("endpoint", "0x%02x".format(ep.address))
            putViews(HexValueCodec.views(got))
            if (n < 0) put("error", "bulkTransfer 返回 $n（超时/断开/端点不可读）")
        }.toString()
    }

    private fun usbBulkWrite(device: UsbDevice, session: UsbBulkSession, args: JsonObject): String {
        val num = HexValueCodec.parseIntField(args.strArg("endpoint_num"))
            ?: return "ERROR: endpoint_num 缺失或非法"
        val data = HexValueCodec.parseHex(args.strArg("data_hex"))
            ?: return "ERROR: data_hex 不是合法十六进制"
        if (data.isEmpty()) return "ERROR: data_hex 为空"
        val timeout = (args.intArg("timeout_ms") ?: 1000).coerceIn(1, 60000)
        val ep = findEndpoint(device, num, false)
            ?: return "ERROR: 未找到 endpoint_num=$num 的 OUT 端点；先看 usb_list 的 endpointDetails（direction=out）"
        val n = runCatching { session.connection.bulkTransfer(ep, data, data.size, timeout) }.getOrDefault(-1)
        return buildJsonObject {
            put("status", if (n >= 0) "OK" else "ERROR")
            put("action", "write")
            put("deviceId", device.deviceId)
            put("endpoint", "0x%02x".format(ep.address))
            put("written", n.coerceAtLeast(0))
            put("requested", data.size)
            if (n < 0) put("error", "bulkTransfer 返回 $n")
        }.toString()
    }

    private fun usbBulkTransact(session: UsbBulkSession, args: JsonObject): String {
        val reqType = HexValueCodec.parseIntField(args.strArg("request_type"))
            ?: return "ERROR: request_type 缺失或非法（如 0xC0）"
        val req = HexValueCodec.parseIntField(args.strArg("request"))
            ?: return "ERROR: request 缺失或非法"
        val reqValue = HexValueCodec.parseIntField(args.strArg("request_value")) ?: 0
        val index = HexValueCodec.parseIntField(args.strArg("index")) ?: 0
        val data = HexValueCodec.parseHex(args.strArg("data_hex")) ?: ByteArray(0)
        val length = HexValueCodec.parseIntField(args.strArg("length")) ?: 4096
        val timeout = (args.intArg("timeout_ms") ?: 1000).coerceIn(1, 60000)
        val isIn = HexValueCodec.isControlIn(reqType)
        val buffer = if (isIn) ByteArray(length.coerceIn(1, 65536)) else data
        val n = runCatching {
            session.connection.controlTransfer(reqType, req, reqValue, index, buffer, buffer.size, timeout)
        }.getOrDefault(-1)
        return buildJsonObject {
            put("status", if (n >= 0) "OK" else "ERROR")
            put("action", "transact")
            put("requestType", "0x%02x".format(reqType))
            put("request", req)
            put("requestValue", reqValue)
            put("index", index)
            put("direction", if (isIn) "in" else "out")
            put("transferred", n.coerceAtLeast(0))
            if (isIn) putViews(HexValueCodec.views(if (n > 0) buffer.copyOf(n) else ByteArray(0)))
            if (n < 0) put("error", "controlTransfer 返回 $n（claim 失败或传输错误；绝不虚报成功）")
            put("note", "控制传输走已 claim 的接口；claimInterface 失败会在建会话时直接返回 ERROR")
        }.toString()
    }

    private fun usbBulkClose(args: JsonObject): String {
        val mgr = usbMgr
        val id = args.intArg("device_id")
        val pair = args.strArg("vid_pid")?.lowercase()?.removePrefix("0x")?.replace("0x", "")
        var targetId: Int? = id
        if (targetId == null && pair != null && mgr != null) {
            val matches = mgr.deviceList.values.filter { "%04x:%04x".format(it.vendorId, it.productId) == pair }
            if (matches.size == 1) targetId = matches.single().deviceId
        }
        var closed = 0
        synchronized(UsbBulkSessions.sessions) {
            val ids = if (targetId != null) listOf(targetId) else UsbBulkSessions.sessions.keys.toList()
            for (did in ids) {
                val s = UsbBulkSessions.sessions.remove(did) ?: continue
                runCatching { s.connection.releaseInterface(s.iface) }
                runCatching { s.connection.close() }
                closed++
            }
        }
        return buildJsonObject {
            put("status", "OK")
            put("closed", closed)
            put("remaining", UsbBulkSessions.sessions.size)
            put("note", if (targetId == null) "未指定有效 device_id/vid_pid，已释放全部 bulk 会话" else "释放指定设备的 claim 接口与 connection")
        }.toString()
    }
}
