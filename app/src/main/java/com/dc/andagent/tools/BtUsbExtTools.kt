package com.dc.andagent.tools

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import com.dc.andagent.bridge.PermissionBroker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/**
 * R86 — 蓝牙扩展刀（硬件暴露补全 ③，接 R74 BLE 底座）。
 *
 *  a) ble_advertise   手机作 BLE 外设广播（服务 UUID / 厂商数据 / 服务数据 / 可连接 / 自动超时）
 *  b) bt_audio_route  媒体输出（A2DP active device）与通信路由（AudioManager）双通道切换
 *  c) bt_battery      标准 GATT Battery Service（0x180F/0x2A19）电量读取 + 缓存
 *  d) usb_list        USB host 枚举：VID/PID、厂商/产品/序列号、类名、接口与端点
 *  e) usb_midi        MidiManager 设备枚举 + 发送（note/CC/Program/Pitch/hex）+ 监听
 *
 * 与既有工具的分工：bt_scan/bt_paired_list/bt_connect 负责发现与配对，audio_route(R84)
 * 只切通信路由，otg_status/otg_transfer 只做 U 盘文件；本刀补「广播、媒体路由、标准电量、
 * USB 拓扑、MIDI 收发」。
 */
val BT_USB_EXT_TOOLS = listOf(
    "ble_advertise",
    "bt_audio_route",
    "bt_battery",
    "usb_list",
    "usb_midi",
)

/* ---------------------------- pure helpers ---------------------------- */

/** Bluetooth SIG base UUID tail for 16/32-bit short UUIDs (pure, testable). */
object BleUuids {
    private const val BASE_SUFFIX = "-0000-1000-8000-00805f9b34fb"
    private val HEX = Regex("^[0-9a-fA-F]+$")

    /** Expands 16/32-bit short forms to full 128-bit UUID text; null when malformed. */
    fun expand(raw: String?): String? {
        val trimmed = raw?.trim()?.removePrefix("0x")?.removePrefix("0X") ?: return null
        val compact = trimmed.replace("-", "")
        if (compact.isEmpty() || !HEX.matches(compact)) return null
        return when (compact.length) {
            4 -> "0000${compact.lowercase()}$BASE_SUFFIX"
            8 -> "${compact.lowercase()}$BASE_SUFFIX"
            32 -> {
                val h = compact.lowercase()
                "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-" +
                    "${h.substring(16, 20)}-${h.substring(20)}"
            }
            else -> null
        }
    }

    fun toParcelUuid(raw: String?): ParcelUuid? = expand(raw)?.let { runCatching { ParcelUuid.fromString(it) }.getOrNull() }

    /** Short display form: 16-bit UUIDs collapse to 4 hex chars, others keep the tail. */
    fun short(uuid: String): String {
        val lower = uuid.lowercase()
        return if (lower.length == 36 && lower.endsWith(BASE_SUFFIX) && lower.startsWith("0000")) lower.substring(4, 8) else lower
    }
}

/** BLE advertisement payload parsing + legacy 31-byte budget accounting (pure). */
object BleAdvCodec {
    const val MAX_LEGACY_BYTES = 31

    data class Payload(
        val serviceUuids: List<String> = emptyList(),
        val manufacturerId: Int? = null,
        val manufacturerData: ByteArray = ByteArray(0),
        val serviceDataUuid: String? = null,
        val serviceData: ByteArray = ByteArray(0),
    )

    /** Comma/space separated UUIDs (16/32/128-bit); throws IllegalArgumentException on bad input. */
    fun parseServiceUuids(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(',', ';', ' ')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { BleUuids.expand(it) ?: throw IllegalArgumentException("服务 UUID 不合法：$it") }
            .distinct()
    }

    /** `0x004C:0215AA` / `76:0215aa` → (companyId, bytes); throws on bad input. */
    fun parseManufacturer(raw: String?): Pair<Int, ByteArray>? {
        if (raw.isNullOrBlank()) return null
        val sep = raw.indexOfFirst { it == ':' || it == '=' }
        require(sep > 0) { "厂商数据格式应为 公司ID:hex，如 0x004C:0215aa" }
        val idText = raw.substring(0, sep).trim().removePrefix("0x").removePrefix("0X")
        val id = idText.toIntOrNull(16) ?: throw IllegalArgumentException("厂商 ID 不是合法十六进制：$idText")
        require(id in 0..0xFFFF) { "厂商 ID 必须在 0..65535" }
        val data = parseHex(raw.substring(sep + 1)) ?: throw IllegalArgumentException("厂商数据不是合法十六进制")
        return id to data
    }

    /** `180D:0102` → (expanded uuid, bytes); throws on bad input. */
    fun parseServiceData(raw: String?): Pair<String, ByteArray>? {
        if (raw.isNullOrBlank()) return null
        val sep = raw.indexOfFirst { it == ':' || it == '=' }
        require(sep > 0) { "服务数据格式应为 UUID:hex，如 180D:0102" }
        val uuid = BleUuids.expand(raw.substring(0, sep)) ?: throw IllegalArgumentException("服务数据 UUID 不合法")
        val data = parseHex(raw.substring(sep + 1)) ?: throw IllegalArgumentException("服务数据不是合法十六进制")
        return uuid to data
    }

    /** Hex text with spaces/colons/dashes/0x prefixes; null when empty or malformed. */
    fun parseHex(raw: String?): ByteArray? {
        val cleaned = raw?.replace(Regex("0[xX]"), "")?.replace(Regex("[\\s:,-]"), "") ?: return null
        if (cleaned.isEmpty() || cleaned.length % 2 != 0) return null
        if (!cleaned.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        return cleaned.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    /**
     * Legacy advertising payload size for the main advertisement packet.
     * Flags AD is 3 bytes; each AD structure costs len+type+data.
     */
    fun payloadBytes(p: Payload, includeTxPower: Boolean): Int {
        var total = 3
        if (includeTxPower) total += 3
        for (uuid in p.serviceUuids) total += if (BleUuids.short(uuid).length == 4) 4 else 18
        if (p.manufacturerId != null) total += 2 + 2 + p.manufacturerData.size
        if (p.serviceDataUuid != null) {
            val short = BleUuids.short(p.serviceDataUuid).length == 4
            total += 2 + (if (short) 2 else 16) + p.serviceData.size
        }
        return total
    }

    /** Name bytes for the scan response (AD overhead 2 bytes). */
    fun scanResponseBytes(nameBytes: Int, includeName: Boolean): Int = if (includeName) 2 + nameBytes else 0

    /** Human-readable validation: null = fits legacy 31-byte budget. */
    fun validate(p: Payload, includeTxPower: Boolean, nameBytes: Int, includeName: Boolean): String? {
        val adv = payloadBytes(p, includeTxPower)
        if (adv > MAX_LEGACY_BYTES) {
            return "广播数据 $adv 字节，超出传统广播 31 字节上限；请减少服务 UUID/厂商数据或改用不带名字的短载荷"
        }
        val scan = scanResponseBytes(nameBytes, includeName)
        if (scan > MAX_LEGACY_BYTES) return "扫描响应含设备名 $scan 字节，超出 31 字节上限；关闭 include_name 或改名"
        return null
    }
}

/** GATT Battery Level (0x2A19) decoding (pure). */
object BatteryCodec {
    data class Reading(val level: Int?, val note: String)

    fun parse(value: ByteArray?): Reading {
        if (value == null || value.isEmpty()) return Reading(null, "电量特征值为空")
        val raw = value[0].toInt() and 0xFF
        return when {
            raw == 0xFF -> Reading(null, "特征值 0xFF：设备报告电量未知")
            raw in 0..100 -> Reading(raw, "")
            else -> Reading(null, "特征值 $raw 超出 0..100 规范范围（保留值/固件异常）")
        }
    }
}

/** MIDI 1.0 byte-stream encoding/decoding (pure). */
object MidiCodec {
    private val NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    fun clamp(value: Int, min: Int, max: Int): Int = value.coerceIn(min, max)

    fun noteName(note: Int): String {
        val n = clamp(note, 0, 127)
        return "${NOTE_NAMES[n % 12]}${n / 12 - 1}"
    }

    fun noteOn(channel: Int, note: Int, velocity: Int): ByteArray = byteArrayOf(
        (0x90 or ((clamp(channel, 1, 16) - 1))).toByte(),
        clamp(note, 0, 127).toByte(),
        clamp(velocity, 0, 127).toByte(),
    )

    fun noteOff(channel: Int, note: Int, velocity: Int = 64): ByteArray = byteArrayOf(
        (0x80 or ((clamp(channel, 1, 16) - 1))).toByte(),
        clamp(note, 0, 127).toByte(),
        clamp(velocity, 0, 127).toByte(),
    )

    fun controlChange(channel: Int, controller: Int, value: Int): ByteArray = byteArrayOf(
        (0xB0 or ((clamp(channel, 1, 16) - 1))).toByte(),
        clamp(controller, 0, 127).toByte(),
        clamp(value, 0, 127).toByte(),
    )

    fun programChange(channel: Int, program: Int): ByteArray = byteArrayOf(
        (0xC0 or ((clamp(channel, 1, 16) - 1))).toByte(),
        clamp(program, 0, 127).toByte(),
    )

    /** 14-bit pitch bend，中心值 8192。 */
    fun pitchBend(channel: Int, value: Int): ByteArray {
        val v = clamp(value, 0, 16383)
        return byteArrayOf(
            (0xE0 or ((clamp(channel, 1, 16) - 1))).toByte(),
            (v and 0x7F).toByte(),
            ((v shr 7) and 0x7F).toByte(),
        )
    }

    /** Validates a raw hex MIDI message: status byte + expected data length, ≤64 bytes. */
    fun parseHex(raw: String?): ByteArray? {
        val bytes = BleAdvCodec.parseHex(raw) ?: return null
        if (bytes.isEmpty() || bytes.size > 64) return null
        if ((bytes[0].toInt() and 0x80) == 0) return null
        return bytes
    }

    fun describe(bytes: ByteArray): String {
        if (bytes.isEmpty()) return "empty"
        val status = bytes[0].toInt() and 0xFF
        val channel = (status and 0x0F) + 1
        fun data(i: Int): Int = if (i < bytes.size) bytes[i].toInt() and 0x7F else 0
        return when (status and 0xF0) {
            0x80 -> "note_off ch=$channel note=${data(1)}(${noteName(data(1))}) velocity=${data(2)}"
            0x90 -> if (data(2) == 0) "note_off ch=$channel note=${data(1)}(${noteName(data(1))}) velocity=0"
            else "note_on ch=$channel note=${data(1)}(${noteName(data(1))}) velocity=${data(2)}"
            0xA0 -> "poly_aftertouch ch=$channel note=${data(1)} pressure=${data(2)}"
            0xB0 -> "control_change ch=$channel controller=${data(1)} value=${data(2)}"
            0xC0 -> "program_change ch=$channel program=${data(1)}"
            0xD0 -> "channel_pressure ch=$channel pressure=${data(1)}"
            0xE0 -> {
                val bend = data(1) or (data(2) shl 7)
                "pitch_bend ch=$channel value=$bend"
            }
            else -> when (status) {
                0xF0 -> "sysex ${bytes.size - 1} bytes"
                0xF8 -> "clock"
                0xFA -> "start"
                0xFB -> "continue"
                0xFC -> "stop"
                0xFE -> "active_sensing"
                else -> "system status=0x${status.toString(16)}"
            }
        }
    }
}

/** USB descriptor naming + device-kind detection (pure). */
object UsbClasses {
    fun className(clazz: Int): String = when (clazz) {
        0x00 -> "per_interface"
        0x01 -> "audio"
        0x02 -> "cdc_communications"
        0x03 -> "hid"
        0x05 -> "physical"
        0x06 -> "image"
        0x07 -> "printer"
        0x08 -> "mass_storage"
        0x09 -> "hub"
        0x0A -> "cdc_data"
        0x0B -> "smart_card"
        0x0D -> "content_security"
        0x0E -> "video"
        0x0F -> "healthcare"
        0x10 -> "audio_video"
        0x11 -> "billboard"
        0x12 -> "usb_bridge"
        0xDC -> "diagnostic"
        0xE0 -> "wireless"
        0xEF -> "misc"
        0xFE -> "app_specific"
        0xFF -> "vendor_specific"
        else -> "class_0x${clazz.toString(16)}"
    }

    fun endpointType(type: Int): String = when (type) {
        UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "control"
        UsbConstants.USB_ENDPOINT_XFER_ISOC -> "isochronous"
        UsbConstants.USB_ENDPOINT_XFER_BULK -> "bulk"
        UsbConstants.USB_ENDPOINT_XFER_INT -> "interrupt"
        else -> "type_$type"
    }

    /** Known composite kinds worth naming to the model (MTP/PTP/ADB/MIDI/camera/serial...). */
    fun kind(classCode: Int, subclass: Int, protocol: Int): String? = when {
        classCode == 0x06 && subclass == 0x01 && protocol == 0x01 -> "mtp"
        classCode == 0x06 && subclass == 0x01 && protocol == 0x02 -> "ptp"
        classCode == 0xFF && subclass == 0x42 && protocol == 0x01 -> "adb"
        classCode == 0x01 && subclass == 0x03 -> "usb_midi"
        classCode == 0x01 && subclass == 0x01 -> "usb_audio"
        classCode == 0x0E && subclass == 0x01 -> "uvc_camera"
        classCode == 0x03 && subclass == 0x01 && protocol == 0x01 -> "hid_keyboard"
        classCode == 0x03 && subclass == 0x01 && protocol == 0x02 -> "hid_mouse"
        classCode == 0x08 && subclass == 0x06 -> "mass_storage_scsi"
        classCode == 0x02 || (classCode == 0x0A && subclass == 0x00) -> "cdc_serial"
        else -> null
    }

    fun deviceClassSummary(clazz: Int, subclass: Int, protocol: Int): String {
        val base = className(clazz)
        return kind(clazz, subclass, protocol)?.let { "$base($it)" } ?: base
    }
}

private fun btDeviceTypeName(type: Int): String = when (type) {
    BluetoothDevice.DEVICE_TYPE_CLASSIC -> "classic"
    BluetoothDevice.DEVICE_TYPE_LE -> "le"
    BluetoothDevice.DEVICE_TYPE_DUAL -> "dual"
    else -> "unknown"
}

private fun btProfileStateName(state: Int): String = when (state) {
    BluetoothProfile.STATE_CONNECTED -> "connected"
    BluetoothProfile.STATE_CONNECTING -> "connecting"
    BluetoothProfile.STATE_DISCONNECTING -> "disconnecting"
    BluetoothProfile.STATE_DISCONNECTED -> "disconnected"
    else -> "state_$state"
}

private fun audioTypeName(type: Int): String = when (type) {
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bt_a2dp"
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bt_sco"
    AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired_headset"
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired_headphones"
    AudioDeviceInfo.TYPE_USB_DEVICE -> "usb_device"
    AudioDeviceInfo.TYPE_USB_HEADSET -> "usb_headset"
    else -> if (Build.VERSION.SDK_INT >= 31) {
        when (type) {
            AudioDeviceInfo.TYPE_BLE_HEADSET -> "ble_headset"
            AudioDeviceInfo.TYPE_BLE_SPEAKER -> "ble_speaker"
            AudioDeviceInfo.TYPE_BLE_BROADCAST -> "ble_broadcast"
            else -> "type_$type"
        }
    } else "type_$type"
}

private fun isBluetoothAudioType(type: Int): Boolean {
    if (type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) return true
    return Build.VERSION.SDK_INT >= 31 && (
        type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
            type == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
            type == AudioDeviceInfo.TYPE_BLE_BROADCAST
        )
}

private fun advFailureText(code: Int): String = when (code) {
    AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "广播数据超长（DATA_TOO_LARGE）"
    AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "广播实例已用满（TOO_MANY_ADVERTISERS）"
    AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "已在广播中（ALREADY_STARTED）"
    AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "蓝牙栈内部错误（INTERNAL_ERROR）"
    AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "硬件不支持该广播配置（FEATURE_UNSUPPORTED）"
    else -> "未知错误码 $code"
}

/* -------------------------------- tool -------------------------------- */

@SuppressLint("MissingPermission")
class BtUsbExtTool(
    private val context: Context,
    private val permissions: PermissionBroker,
    override val name: String,
) : Tool {

    override val description: String = when (name) {
        "ble_advertise" ->
            "让手机作为 BLE 外设对外广播（配对引导、信标、自建服务）：可指定服务 UUID、厂商数据（iBeacon 等）、" +
                "服务数据、是否可连接、发射功率与自动停止时间。action=status 查当前广播（advertiserAvailable/" +
                "multipleAdvertisementsSupported），stop 立即停止。本机不支持 BLE 外设模式时如实报错。"
        "bt_audio_route" ->
            "蓝牙音频路由：action=list 列出已连接 A2DP 设备、系统蓝牙输出设备与当前 active device；" +
                "action=set 用 mac/name 切换媒体输出（A2DP active device，隐藏 API，失败时如实标注并给系统界面引导），" +
                "mode=communication 则切换通话/通信路由；action=reset 清除通信路由。"
        "bt_battery" ->
            "读取标准蓝牙电量（GATT Battery Service 0x180F / Battery Level 0x2A19）：action=list 列已配对设备与缓存电量，" +
                "action=read 按 mac 现读一次（省略 mac 时按 limit 依次尝试已配对设备）。" +
                "经典蓝牙耳机/音箱通常不提供该 GATT 服务，读不到时如实说明，不要编造电量。"
        "usb_list" ->
            "枚举 USB host 上全部设备：VID/PID、厂商/产品/序列号、设备类与已知类型（MTP/PTP/ADB/MIDI/UVC/HID/串口）、" +
                "接口与端点（类型/方向/包长/间隔）、权限状态；用于判断 OTG 插了什么设备。"
        "usb_midi" ->
            "USB MIDI 收发（MidiManager）：action=list 列 MIDI 设备与输入/输出端口；" +
                "action=send 向设备输入端口发送 note_on/note_off/control_change/program_change/pitch_bend 或原始 hex 字节；" +
                "action=listen 监听设备输出端口 N 秒并解码消息。零额外权限，需硬件支持 android.software.midi。"
        else -> "蓝牙/USB 扩展工具"
    }

    override val parameters: JsonObject = when (name) {
        "ble_advertise" -> schemaOf(
            "action" to strParam("status（默认）| start | stop"),
            "service_uuids" to strParam("逗号分隔的服务 UUID，支持 16/32/128 位，如 180D,0000180f-0000-1000-8000-00805f9b34fb"),
            "manufacturer_data" to strParam("厂商数据 公司ID:hex，如 0x004C:0215aa（iBeacon 常见 4C00）"),
            "service_data" to strParam("服务数据 UUID:hex，如 180D:0102"),
            "include_name" to boolParam("扫描响应中是否携带本机蓝牙名，默认 true"),
            "include_tx_power" to boolParam("广播中是否携带发射功率，默认 true"),
            "connectable" to boolParam("是否允许被连接（可连接广播），默认 true"),
            "mode" to strParam("广播模式 low_power|balanced|low_latency，默认 balanced"),
            "tx_power" to strParam("发射功率 low|medium|high，默认 medium"),
            "timeout_s" to intParam("自动停止秒数，默认 120，范围 1..600"),
        )
        "bt_audio_route" -> schemaOf(
            "action" to strParam("list（默认）| set | reset"),
            "mac" to strParam("action=set 时的已连接 A2DP 设备 MAC"),
            "name" to strParam("action=set 时的设备名（模糊匹配，mac 优先）"),
            "device_id" to intParam("action=set 时的系统音频输出设备 id（AudioManager getDevices 的 id，mac/name 都不匹配时用）"),
            "mode" to strParam("media（默认，切 A2DP 媒体输出）| communication（切通话/通信路由）"),
        )
        "bt_battery" -> schemaOf(
            "action" to strParam("list（默认）| read"),
            "mac" to strParam("action=read 的设备 MAC；省略则按 limit 依次读已配对设备"),
            "limit" to intParam("action=read 且省略 mac 时最多尝试的设备数，默认 3，范围 1..8"),
        )
        "usb_list" -> schemaOf(
            "include_endpoints" to boolParam("是否展开端点信息，默认 true"),
        )
        "usb_midi" -> schemaOf(
            "action" to strParam("list（默认）| send | listen"),
            "device_id" to intParam("目标 MIDI 设备 id（见 action=list）；与 name 二选一"),
            "name" to strParam("目标 MIDI 设备名（模糊匹配）"),
            "port" to intParam("端口序号，默认 0：send 用输入端口，listen 用输出端口"),
            "type" to strParam("send 的消息类型：note_on|note_off|control_change|program_change|pitch_bend|hex"),
            "channel" to intParam("MIDI 通道 1..16，默认 1"),
            "note" to intParam("note_on/note_off 的音符 0..127（60=中央 C）"),
            "velocity" to intParam("note_on/note_off 的力度 0..127，默认 100；0 等价 note_off"),
            "controller" to intParam("control_change 的控制器号 0..127"),
            "value" to intParam("control_change 的 0..127 或 pitch_bend 的 0..16383（中心 8192）"),
            "program" to intParam("program_change 的音色号 0..127"),
            "bytes" to strParam("type=hex 时的原始字节，如 90 40 7F"),
            "seconds" to intParam("action=listen 的监听秒数，默认 5，范围 1..30"),
            "limit" to intParam("action=listen 最多返回的消息条数，默认 60，范围 1..200"),
        )
        else -> schemaOf()
    }

    override val destructive: Boolean = name in setOf("ble_advertise", "bt_audio_route")

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        runCatching {
            when (name) {
                "ble_advertise" -> bleAdvertise(args)
                "bt_audio_route" -> btAudioRoute(args)
                "bt_battery" -> btBattery(args)
                "usb_list" -> usbList(args)
                "usb_midi" -> usbMidi(args)
                else -> "ERROR: 未知工具 $name"
            }
        }.getOrElse { "ERROR: $name 执行失败：${it.message ?: it.javaClass.simpleName}" }
    }

    private val manager get() = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val btAdapter get() = manager?.adapter
    private val audio get() = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val usb get() = context.getSystemService(Context.USB_SERVICE) as? UsbManager

    private suspend fun ensureConnect(): String? {
        if (btAdapter == null) return "ERROR: 蓝牙不可用（无蓝牙硬件）"
        if (Build.VERSION.SDK_INT >= 31 && !permissions.ensure(listOf(MANIFEST_CONNECT))) {
            return "ERROR: 缺少 BLUETOOTH_CONNECT 权限（拒绝或永久拒绝，可在系统设置开启）"
        }
        return null
    }

    private fun requireEnabled(): String? {
        val adapter = btAdapter ?: return "ERROR: 蓝牙不可用（无蓝牙硬件）"
        if (!adapter.isEnabled) return "ERROR: 蓝牙未开启，请先开启蓝牙（bt_toggle 或系统开关）"
        return null
    }

    /* --------------------------- BLE advertise -------------------------- */

    private suspend fun bleAdvertise(args: JsonObject): String {
        val adapter = btAdapter ?: return "ERROR: 蓝牙不可用（无蓝牙硬件）"
        return when ((args.strArg("action") ?: "status").lowercase()) {
            "status" -> advStatusJson(adapter)
            "stop" -> {
                val stopped = stopAdvertising()
                buildJsonObject {
                    put("action", "stop")
                    put("status", "OK")
                    put("stopped", stopped)
                    put("advertising", advState?.isLive() == true)
                }.toString()
            }
            "start" -> startAdvertising(adapter, args)
            else -> "ERROR: action 仅支持 status|start|stop"
        }
    }

    private suspend fun startAdvertising(adapter: BluetoothAdapter, args: JsonObject): String {
        if (Build.VERSION.SDK_INT >= 31 && !permissions.ensure(listOf(MANIFEST_ADVERTISE))) {
            return "ERROR: 缺少 BLUETOOTH_ADVERTISE 权限（拒绝或永久拒绝，可在系统设置开启）"
        }
        if (!adapter.isEnabled) return "ERROR: 蓝牙未开启，请先开启蓝牙"
        // isMultipleAdvertisementSupported 只表示「可并发多个广播」，部分机型即使为 false
        // 也能启动单个广播，所以只把广播器本身当作硬门槛。
        val multiSupported = runCatching { adapter.isMultipleAdvertisementSupported }.getOrDefault(false)
        val advertiser = adapter.bluetoothLeAdvertiser
            ?: return "ERROR: 无法取得 BLE 广播器（本机不支持 BLE 外设模式，isMultipleAdvertisementSupported=$multiSupported）"

        val payload = try {
            BleAdvCodec.Payload(
                serviceUuids = BleAdvCodec.parseServiceUuids(args.strArg("service_uuids")),
                manufacturerId = null,
                serviceDataUuid = null,
            ).let { base ->
                val manufacturer = BleAdvCodec.parseManufacturer(args.strArg("manufacturer_data"))
                val serviceData = BleAdvCodec.parseServiceData(args.strArg("service_data"))
                base.copy(
                    manufacturerId = manufacturer?.first,
                    manufacturerData = manufacturer?.second ?: ByteArray(0),
                    serviceDataUuid = serviceData?.first,
                    serviceData = serviceData?.second ?: ByteArray(0),
                )
            }
        } catch (e: IllegalArgumentException) {
            return "ERROR: ${e.message}"
        }

        val includeName = args.boolArg("include_name") ?: true
        val includeTxPower = args.boolArg("include_tx_power") ?: true
        val connectable = args.boolArg("connectable") ?: true
        val timeoutSec = (args.intArg("timeout_s") ?: 120).coerceIn(1, 600)
        val mode = when ((args.strArg("mode") ?: "balanced").lowercase()) {
            "low_power", "low-power" -> AdvertiseSettings.ADVERTISE_MODE_LOW_POWER
            "low_latency", "low-latency" -> AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
            "balanced" -> AdvertiseSettings.ADVERTISE_MODE_BALANCED
            else -> return "ERROR: mode 仅支持 low_power|balanced|low_latency"
        }
        val txPower = when ((args.strArg("tx_power") ?: "medium").lowercase()) {
            "low" -> AdvertiseSettings.ADVERTISE_TX_POWER_LOW
            "medium" -> AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM
            "high" -> AdvertiseSettings.ADVERTISE_TX_POWER_HIGH
            else -> return "ERROR: tx_power 仅支持 low|medium|high"
        }

        val adapterName = runCatching { adapter.name }.getOrNull()
        val nameBytes = adapterName?.toByteArray(Charsets.UTF_8)?.size ?: 0
        BleAdvCodec.validate(payload, includeTxPower, nameBytes, includeName)?.let { return "ERROR: $it" }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(mode)
            .setTxPowerLevel(txPower)
            .setConnectable(connectable)
            .setTimeout(0)
            .build()

        val dataBuilder = AdvertiseData.Builder()
            .setIncludeTxPowerLevel(includeTxPower)
        payload.serviceUuids.forEach { uuid ->
            BleUuids.toParcelUuid(uuid)?.let { dataBuilder.addServiceUuid(it) }
        }
        payload.manufacturerId?.let { id -> dataBuilder.addManufacturerData(id, payload.manufacturerData) }
        payload.serviceDataUuid?.let { uuid ->
            BleUuids.toParcelUuid(uuid)?.let { parcel -> dataBuilder.addServiceData(parcel, payload.serviceData) }
        }
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(includeName && nameBytes > 0)
            .build()

        stopAdvertising()

        val settled = CountDownLatch(1)
        val failure = AtomicReference<String?>(null)
        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                settled.countDown()
            }

            override fun onStartFailure(errorCode: Int) {
                failure.set(advFailureText(errorCode))
                settled.countDown()
            }
        }
        runCatching { advertiser.startAdvertising(settings, dataBuilder.build(), scanResponse, callback) }
            .getOrElse { return "ERROR: 启动广播失败：${it.message ?: it.javaClass.simpleName}" }

        if (!settled.await(4, TimeUnit.SECONDS)) {
            runCatching { advertiser.stopAdvertising(callback) }
            return "ERROR: 广播启动超时（4s 未收到回调），蓝牙栈可能未就绪"
        }
        failure.get()?.let {
            runCatching { advertiser.stopAdvertising(callback) }
            return "ERROR: 广播启动失败：$it"
        }

        val expiresAt = System.currentTimeMillis() + timeoutSec * 1000L
        val snapshot = buildJsonObject {
            put("serviceUuids", payload.serviceUuids.joinToString(",") { BleUuids.short(it) })
            put("manufacturerId", payload.manufacturerId ?: -1)
            put("manufacturerBytes", payload.manufacturerData.size)
            put("serviceDataBytes", payload.serviceData.size)
            put("connectable", connectable)
            put("includeName", includeName && nameBytes > 0)
            put("includeTxPower", includeTxPower)
            put("mode", (args.strArg("mode") ?: "balanced").lowercase())
            put("txPower", (args.strArg("tx_power") ?: "medium").lowercase())
            put("timeoutSec", timeoutSec)
        }
        advState = AdvState(advertiser, callback, snapshot, System.currentTimeMillis(), expiresAt)
        scheduleAutoStop(timeoutSec)
        return buildJsonObject {
            put("action", "start")
            put("status", "OK")
            put("advertising", true)
            put("deviceName", adapterName ?: "")
            put("expiresInSec", timeoutSec)
            put("payloadBytes", BleAdvCodec.payloadBytes(payload, includeTxPower))
            put("multipleAdvertisementsSupported", multiSupported)
            put("config", snapshot)
            put("note", "广播随进程存活；到期或 action=stop 自动停止。名称由本机蓝牙名决定，改名请到系统蓝牙设置")
        }.toString()
    }

    private fun advStatusJson(adapter: BluetoothAdapter): String {
        val state = advState
        val active = state != null && state.isLive()
        return buildJsonObject {
            put("action", "status")
            put("status", "OK")
            put("advertising", active)
            put("advertiserAvailable", runCatching { adapter.bluetoothLeAdvertiser != null }.getOrDefault(false))
            put("multipleAdvertisementsSupported", runCatching { adapter.isMultipleAdvertisementSupported }.getOrDefault(false))
            put("bluetoothEnabled", runCatching { adapter.isEnabled }.getOrDefault(false))
            put("deviceName", runCatching { adapter.name }.getOrNull() ?: "")
            if (state != null && active) {
                put("remainingSec", ((state.expiresAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0))
                put("startedAt", state.startedAt)
                put("config", state.config)
            }
        }.toString()
    }

    private fun stopAdvertising(): Boolean {
        val state = advState ?: return false
        advState = null
        stopHandler.removeCallbacksAndMessages(null)
        return runCatching { state.advertiser.stopAdvertising(state.callback) }.isSuccess
    }

    private fun scheduleAutoStop(timeoutSec: Int) {
        stopHandler.removeCallbacksAndMessages(null)
        stopHandler.postDelayed({ stopAdvertising() }, timeoutSec * 1000L)
    }

    /* --------------------------- bt_audio_route ------------------------- */

    private suspend fun btAudioRoute(args: JsonObject): String {
        ensureConnect()?.let { return it }
        requireEnabled()?.let { return it }
        val am = audio ?: return "ERROR: 音频服务不可用"
        return when ((args.strArg("action") ?: "list").lowercase()) {
            "list" -> audioRouteSnapshot(am, "list", null, null, null)
            "set" -> {
                val mode = (args.strArg("mode") ?: "media").lowercase()
                if (mode !in setOf("media", "communication")) return "ERROR: mode 仅支持 media|communication"
                setAudioRoute(am, args, mode)
            }
            "reset" -> {
                val failure = runCatching {
                    if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice()
                    am.stopBluetoothSco()
                    am.isBluetoothScoOn = false
                    am.mode = AudioManager.MODE_NORMAL
                    null
                }.getOrElse { it.message ?: it.javaClass.simpleName }
                if (failure != null) "ERROR: 恢复默认路由失败：$failure"
                else audioRouteSnapshot(am, "reset", null, "已清除通信设备并恢复 MODE_NORMAL", null)
            }
            else -> "ERROR: action 仅支持 list|set|reset"
        }
    }

    private suspend fun setAudioRoute(am: AudioManager, args: JsonObject, mode: String): String {
        val mac = args.strArg("mac")?.uppercase()
        if (mac != null && !BluetoothAdapter.checkBluetoothAddress(mac)) return "ERROR: MAC 地址格式无效"
        val nameArg = args.strArg("name")
        val deviceId = args.intArg("device_id")

        val connected = a2dpConnectedDevices() ?: emptyList()
        val target = connected.firstOrNull { mac != null && it.address.equals(mac, true) }
            ?: connected.firstOrNull { nameArg != null && (it.name ?: "").contains(nameArg, ignoreCase = true) }
            ?: if (mac == null && nameArg == null && deviceId == null) connected.firstOrNull() else null

        if (mode == "media") {
            if (connected.isEmpty()) {
                return buildJsonObject {
                    put("status", "NOT_APPLIED")
                    put("action", "set")
                    put("mode", mode)
                    put("reason", "没有已连接的 A2DP 音频设备；先 bt_connect/系统蓝牙连接耳机或音箱")
                }.toString()
            }
            val device = target ?: return buildJsonObject {
                put("status", "NOT_APPLIED")
                put("action", "set")
                put("mode", mode)
                put("reason", "未匹配到目标 A2DP 设备")
                putJsonArray("connected") { connected.forEach { add(buildJsonObject {
                    put("name", it.name ?: ""); put("mac", it.address)
                }) } }
            }.toString()
            val applied = a2dpSetActiveDevice(device)
            if (!applied) {
                // Fallback: 能拿到系统音频输出设备时，至少把通信路由指过去并如实说明媒体未切换。
                val fallbackDevice = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { info ->
                    isBluetoothAudioType(info.type) && nameMatches(info, device.name)
                }
                val fallbackOk = if (fallbackDevice != null && Build.VERSION.SDK_INT >= 31) {
                    runCatching {
                        am.mode = AudioManager.MODE_IN_COMMUNICATION
                        am.setCommunicationDevice(fallbackDevice)
                    }.getOrDefault(false)
                } else false
                return audioRouteSnapshot(
                    am,
                    "set",
                    device.name ?: device.address,
                    if (fallbackOk) "A2DP active device 切换被系统拒绝；已改为切换通信路由（媒体走向可能不变，请在系统蓝牙页确认）"
                    else "A2DP active device 切换被系统拒绝（隐藏 API 受限）；请在系统蓝牙页手动选择输出设备",
                    false,
                )
            }
            delay(600)
            val nowActive = a2dpActiveDevice()
            val verified = nowActive?.address?.equals(device.address, true) == true
            return audioRouteSnapshot(
                am,
                "set",
                device.name ?: device.address,
                if (verified) "已切换媒体输出（A2DP active device 校验一致）"
                else "已提交 A2DP 切换，但 active device 校验不一致；可能被通话或其他 App 抢占",
                verified,
            )
        }

        // communication mode: AudioManager 公开 API，仅影响通话/通信路由。
        val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val info = outputs.firstOrNull { deviceId != null && it.id == deviceId }
            ?: outputs.firstOrNull { isBluetoothAudioType(it.type) && (mac != null && nameMatches(it, mac) || nameArg != null && nameMatches(it, nameArg)) }
            ?: outputs.firstOrNull { isBluetoothAudioType(it.type) }
            ?: return buildJsonObject {
                put("status", "NOT_APPLIED")
                put("action", "set")
                put("mode", mode)
                put("reason", "系统音频输出里没有蓝牙设备；请确认耳机/音箱已连接")
            }.toString()
        if (Build.VERSION.SDK_INT < 31) {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            am.startBluetoothSco()
            am.isBluetoothScoOn = true
            return audioRouteSnapshot(am, "set", audioTypeName(info.type), "Android 12 以下用蓝牙 SCO 切换通信路由", true)
        }
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        val ok = runCatching { am.setCommunicationDevice(info) }.getOrDefault(false)
        return audioRouteSnapshot(
            am,
            "set",
            audioTypeName(info.type),
            if (ok) "通信路由已切换（媒体播放仍由系统按已连接设备决定）"
            else "系统拒绝切换通信路由（设备可能被占用或非可路由设备）",
            ok,
        )
    }

    private fun nameMatches(info: AudioDeviceInfo, needle: String?): Boolean {
        if (needle.isNullOrBlank()) return false
        val product = info.productName?.toString() ?: ""
        return product.contains(needle, ignoreCase = true) || needle.equals(product, ignoreCase = true)
    }

    private suspend fun audioRouteSnapshot(
        am: AudioManager,
        action: String,
        requested: String?,
        note: String?,
        applied: Boolean?,
    ): String {
        val outputs = runCatching { am.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }.getOrDefault(emptyArray())
        val connected = a2dpConnectedDevices() ?: emptyList()
        val active = a2dpActiveDevice()
        return buildJsonObject {
            put("status", if (applied == false) "NOT_APPLIED" else "OK")
            put("action", action)
            requested?.let { put("requested", it) }
            applied?.let { put("applied", it) }
            active?.let { put("activeA2dp", buildJsonObject { put("name", it.name ?: ""); put("mac", it.address) }) }
            put("mode", when (am.mode) {
                AudioManager.MODE_NORMAL -> "normal"
                AudioManager.MODE_RINGTONE -> "ringtone"
                AudioManager.MODE_IN_CALL -> "in_call"
                AudioManager.MODE_IN_COMMUNICATION -> "in_communication"
                else -> "mode_${am.mode}"
            })
            put("speakerphoneOn", am.isSpeakerphoneOn)
            put("bluetoothScoOn", am.isBluetoothScoOn)
            putJsonArray("a2dpConnected") {
                connected.forEach { d ->
                    add(buildJsonObject {
                        put("name", d.name ?: "")
                        put("mac", d.address)
                        put("type", btDeviceTypeName(d.type))
                    })
                }
            }
            putJsonArray("outputs") {
                outputs.filter { isBluetoothAudioType(it.type) }.forEach { info ->
                    add(buildJsonObject {
                        put("id", info.id)
                        put("type", audioTypeName(info.type))
                        put("name", info.productName?.toString() ?: "")
                        put("sink", info.isSink)
                    })
                }
            }
            if (!note.isNullOrBlank()) put("note", note)
            put("scope", "media=a2dp active device (hidden API); communication=AudioManager routing")
        }.toString()
    }

    private suspend fun a2dpConnectedDevices(): List<BluetoothDevice>? =
        profileCall(BluetoothProfile.A2DP) { proxy -> proxy.connectedDevices ?: emptyList() }

    private suspend fun a2dpActiveDevice(): BluetoothDevice? =
        profileCall(BluetoothProfile.A2DP) { proxy ->
            runCatching { proxy.javaClass.getMethod("getActiveDevice").invoke(proxy) as? BluetoothDevice }.getOrNull()
        }

    private suspend fun a2dpSetActiveDevice(device: BluetoothDevice): Boolean =
        profileCall(BluetoothProfile.A2DP) { proxy -> reflectSetActiveDevice(proxy, device) } ?: false

    private fun reflectSetActiveDevice(proxy: BluetoothProfile, device: BluetoothDevice): Boolean {
        val methods = listOfNotNull(
            runCatching { proxy.javaClass.getMethod("setActiveDevice", BluetoothDevice::class.java) }.getOrNull(),
            runCatching {
                Class.forName("android.bluetooth.BluetoothA2dp")
                    .getMethod("setActiveDevice", BluetoothDevice::class.java)
            }.getOrNull(),
        ).distinct()
        for (method in methods) {
            val result = runCatching { method.invoke(proxy, device) }
            if (result.isSuccess && (result.getOrNull() as? Boolean ?: true)) return true
        }
        return false
    }

    private suspend fun <T> profileCall(profile: Int, block: (BluetoothProfile) -> T): T? {
        val adapter = btAdapter ?: return null
        return withTimeoutOrNull(6_000) {
            suspendCancellableCoroutine { cont ->
                var closed = false
                val listener = object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(type: Int, proxy: BluetoothProfile) {
                        val value = runCatching { block(proxy) }.getOrNull()
                        if (!closed) {
                            closed = true
                            runCatching { adapter.closeProfileProxy(type, proxy) }
                        }
                        if (cont.isActive) cont.resume(value)
                    }

                    override fun onServiceDisconnected(type: Int) {
                        if (cont.isActive) cont.resume(null)
                    }
                }
                val requested = runCatching { adapter.getProfileProxy(context, listener, profile) }.getOrDefault(false)
                if (!requested && cont.isActive) cont.resume(null)
            }
        }
    }

    /* ------------------------------ bt_battery -------------------------- */

    private suspend fun btBattery(args: JsonObject): String {
        ensureConnect()?.let { return it }
        requireEnabled()?.let { return it }
        val adapter = btAdapter ?: return "ERROR: 蓝牙不可用"
        val action = (args.strArg("action") ?: "list").lowercase()
        return when (action) {
            "list" -> batteryList(adapter)
            "read" -> {
                val mac = args.strArg("mac")?.uppercase()
                if (mac != null) {
                    if (!BluetoothAdapter.checkBluetoothAddress(mac)) return "ERROR: MAC 地址格式无效"
                    val device = runCatching { adapter.getRemoteDevice(mac) }.getOrNull()
                        ?: return "ERROR: 无法获取设备 $mac"
                    batteryRead(device)
                } else {
                    val limit = (args.intArg("limit") ?: 3).coerceIn(1, 8)
                    val bonded = adapter.bondedDevices?.toList() ?: emptyList()
                    if (bonded.isEmpty()) return "ERROR: 没有已配对设备；先用 bt_scan/系统蓝牙配对"
                    val targets = bonded.sortedBy { it.type == BluetoothDevice.DEVICE_TYPE_CLASSIC }.take(limit)
                    val results = targets.map { device ->
                        device to runCatching { batteryRead(device) }
                            .getOrElse { "ERROR: ${it.message ?: it.javaClass.simpleName}" }
                    }
                    buildJsonObject {
                        put("action", "read")
                        putJsonArray("results") {
                            results.forEach { (device, outcome) ->
                                add(buildJsonObject {
                                    put("name", device.name ?: "")
                                    put("mac", device.address)
                                    put("result", outcome)
                                })
                            }
                        }
                        put("note", "依次读取耗时较长：每次 GATT 连接约 1-8 秒；只读标准 Battery Service")
                    }.toString()
                }
            }
            else -> "ERROR: action 仅支持 list|read"
        }
    }

    private fun batteryList(adapter: BluetoothAdapter): String {
        val bonded = adapter.bondedDevices?.toList() ?: emptyList()
        val cache = batteryCache()
        return buildJsonObject {
            put("action", "list")
            put("status", "OK")
            putJsonArray("devices") {
                bonded.forEach { d ->
                    val entry = cache[deviceCacheKey(d)]
                    add(buildJsonObject {
                        put("name", d.name ?: "")
                        put("mac", d.address)
                        put("type", btDeviceTypeName(d.type))
                        put("bondState", d.bondState)
                        if (entry != null) {
                            put("level", (entry["level"] as? JsonPrimitive)?.content?.toIntOrNull() ?: -1)
                            put("source", (entry["source"] as? JsonPrimitive)?.content ?: "")
                            put("readAt", (entry["readAt"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L)
                        }
                    })
                }
            }
            put("known", cache.size)
            put("note", "level 来自上次 action=read 的缓存；经典蓝牙音频设备通常没有 GATT 电量服务")
        }.toString()
    }

    private suspend fun batteryRead(device: BluetoothDevice): String {
        // 1) SystemApi 缓存值（多为特权 App 提供），失败即降级到 GATT。
        val systemLevel = runCatching {
            device.javaClass.getMethod("getBatteryLevel").invoke(device) as? Int
        }.getOrNull()
        if (systemLevel != null && systemLevel in 0..100) {
            cacheBattery(device, systemLevel, "system_api")
            return batteryJson(device, systemLevel, "system_api", "系统缓存值（BluetoothDevice.getBatteryLevel）")
        }
        // 2) 标准 GATT Battery Service。
        val (level, note) = readGattBattery(device)
        if (level != null) {
            cacheBattery(device, level, "gatt")
            return batteryJson(device, level, "gatt", note)
        }
        return buildJsonObject {
            put("status", "NO_DATA")
            put("name", device.name ?: "")
            put("mac", device.address)
            put("type", btDeviceTypeName(device.type))
            put("reason", note)
            put("hint", "经典蓝牙耳机/音箱的电量一般只在厂商私有协议或系统 UI 中可见；本工具只读标准 GATT 0x180F")
        }.toString()
    }

    private fun batteryJson(device: BluetoothDevice, level: Int, source: String, note: String): String =
        buildJsonObject {
            put("status", "OK")
            put("name", device.name ?: "")
            put("mac", device.address)
            put("type", btDeviceTypeName(device.type))
            put("level", level)
            put("source", source)
            put("readAt", System.currentTimeMillis())
            if (note.isNotBlank()) put("note", note)
        }.toString()

    private fun readGattBattery(device: BluetoothDevice): Pair<Int?, String> {
        val connectLatch = CountDownLatch(1)
        val discovered = CountDownLatch(1)
        val read = CountDownLatch(1)
        val state = AtomicInteger(BluetoothProfile.STATE_DISCONNECTED)
        val gattStatus = AtomicInteger(BluetoothGatt.GATT_FAILURE)
        val discoveryStatus = AtomicInteger(BluetoothGatt.GATT_FAILURE)
        val readStatus = AtomicInteger(BluetoothGatt.GATT_FAILURE)
        val value = AtomicReference<ByteArray?>(null)
        val delivered = AtomicBoolean(false)

        fun record(raw: ByteArray?) {
            if (delivered.compareAndSet(false, true)) {
                value.set(raw?.copyOf())
                read.countDown()
            }
        }

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                state.set(newState)
                gattStatus.set(status)
                connectLatch.countDown()
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                discoveryStatus.set(status)
                discovered.countDown()
            }

            // Android 12 及以下只回调这个三参版本。
            @Suppress("DEPRECATION")
            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                readStatus.set(status)
                record(characteristic.value)
            }

            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                raw: ByteArray,
                status: Int,
            ) {
                readStatus.set(status)
                record(raw)
            }
        }

        val gatt = runCatching { device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE) }.getOrNull()
            ?: return null to "GATT 连接无法启动（设备可能仅支持经典蓝牙，或蓝牙栈忙）"
        try {
            if (!connectLatch.await(8, TimeUnit.SECONDS)) return null to "GATT 连接超时（8s）"
            if (state.get() != BluetoothProfile.STATE_CONNECTED) {
                return null to "GATT 未连接（status=${gattStatus.get()}，常见原因：设备不支持 BLE 或已被其他主机连接）"
            }
            if (!runCatching { gatt.discoverServices() }.getOrDefault(false)) return null to "无法发起服务发现"
            if (!discovered.await(6, TimeUnit.SECONDS)) return null to "服务发现超时（6s）"
            if (discoveryStatus.get() != BluetoothGatt.GATT_SUCCESS) return null to "服务发现失败（status=${discoveryStatus.get()}）"
            val service = gatt.getService(BATTERY_SERVICE_UUID)
                ?: return null to "设备没有标准电量服务 0x180F（经典蓝牙音频设备通常如此）"
            val characteristic = service.getCharacteristic(BATTERY_LEVEL_UUID)
                ?: return null to "电量服务里没有 Battery Level 特征 0x2A19"
            if (!runCatching { gatt.readCharacteristic(characteristic) }.getOrDefault(false)) {
                return null to "读取电量特征被拒绝"
            }
            if (!read.await(6, TimeUnit.SECONDS)) return null to "读取电量超时（6s）"
            if (readStatus.get() != BluetoothGatt.GATT_SUCCESS) return null to "读取电量失败（status=${readStatus.get()}）"
            val parsed = BatteryCodec.parse(value.get())
            return parsed.level to parsed.note
        } finally {
            runCatching { gatt.disconnect() }
            runCatching { gatt.close() }
        }
    }

    /** KV keys only allow [A-Za-z0-9._-], so the MAC colons are stripped. */
    private fun deviceCacheKey(device: BluetoothDevice): String =
        device.address.uppercase().replace(":", "")

    private fun batteryCache(): Map<String, JsonObject> {
        val store = runCatching { KvStore(context) }.getOrNull() ?: return emptyMap()
        val keys = runCatching { store.keys(BATTERY_NAMESPACE) }.getOrNull() ?: return emptyMap()
        val result = mutableMapOf<String, JsonObject>()
        for (info in keys) {
            val element = runCatching { store.get(BATTERY_NAMESPACE, info.key) }.getOrNull() as? JsonObject ?: continue
            result[info.key] = element
        }
        return result
    }

    private fun cacheBattery(device: BluetoothDevice, level: Int, source: String) {
        val store = runCatching { KvStore(context) }.getOrNull() ?: return
        runCatching {
            store.set(
                BATTERY_NAMESPACE,
                deviceCacheKey(device),
                buildJsonObject {
                    put("level", level)
                    put("source", source)
                    put("readAt", System.currentTimeMillis())
                    put("name", device.name ?: "")
                    put("mac", device.address)
                },
            )
        }
    }

    /* -------------------------------- usb_list -------------------------- */

    private fun usbList(args: JsonObject): String {
        val usbManager = usb ?: return "ERROR: USB 服务不可用"
        val includeEndpoints = args.boolArg("include_endpoints") ?: true
        val devices = usbManager.deviceList.values.toList()
        val hostFeature = context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)
        return buildJsonObject {
            put("status", "OK")
            put("usbHostSupported", hostFeature)
            put("count", devices.size)
            putJsonArray("devices") { devices.forEach { d -> add(usbDeviceJson(usbManager, d, includeEndpoints)) } }
            if (devices.isEmpty()) {
                put("note", "未检测到 USB 设备：确认 OTG 转接头/线缆已接好，且系统 USB host 未被占用（部分机型需在开发者选项切换 USB 模式）")
            }
        }.toString()
    }

    private fun usbDeviceJson(usbManager: UsbManager, device: UsbDevice, includeEndpoints: Boolean): JsonObject =
        buildJsonObject {
            put("deviceName", device.deviceName)
            put("deviceId", device.deviceId)
            put("vendorId", device.vendorId)
            put("productId", device.productId)
            put("vid", "0x${device.vendorId.toString(16).padStart(4, '0')}")
            put("pid", "0x${device.productId.toString(16).padStart(4, '0')}")
            put("deviceClass", UsbClasses.className(device.deviceClass))
            put("deviceClassCode", device.deviceClass)
            put("deviceSubclass", device.deviceSubclass)
            put("deviceProtocol", device.deviceProtocol)
            put("manufacturer", runCatching { device.manufacturerName }.getOrNull() ?: "")
            put("product", runCatching { device.productName }.getOrNull() ?: "")
            put("version", runCatching { device.version }.getOrNull() ?: "")
            put("serial", runCatching { device.serialNumber }.getOrNull() ?: "")
            put("permission", runCatching { usbManager.hasPermission(device) }.getOrDefault(false))
            putJsonArray("interfaces") {
                for (index in 0 until device.interfaceCount) {
                    val iface = runCatching { device.getInterface(index) }.getOrNull() ?: continue
                    val kind = UsbClasses.kind(iface.interfaceClass, iface.interfaceSubclass, iface.interfaceProtocol)
                    add(buildJsonObject {
                        put("index", index)
                        put("id", iface.id)
                        put("class", UsbClasses.className(iface.interfaceClass))
                        put("classCode", iface.interfaceClass)
                        put("subclass", iface.interfaceSubclass)
                        put("protocol", iface.interfaceProtocol)
                        kind?.let { put("kind", it) }
                        put("endpoints", iface.endpointCount)
                        if (includeEndpoints) {
                            putJsonArray("endpointDetails") {
                                for (e in 0 until iface.endpointCount) {
                                    val ep = runCatching { iface.getEndpoint(e) }.getOrNull() ?: continue
                                    add(endpointJson(ep))
                                }
                            }
                        }
                    })
                }
            }
            put("kinds", buildJsonArray {
                val found = mutableSetOf<String>()
                for (index in 0 until device.interfaceCount) {
                    val iface = runCatching { device.getInterface(index) }.getOrNull() ?: continue
                    UsbClasses.kind(iface.interfaceClass, iface.interfaceSubclass, iface.interfaceProtocol)
                        ?.takeIf { found.add(it) }?.let { add(JsonPrimitive(it)) }
                }
            })
        }

    private fun endpointJson(ep: UsbEndpoint): JsonObject = buildJsonObject {
        put("address", "0x${ep.address.toString(16)}")
        put("number", ep.endpointNumber)
        put("direction", if (ep.direction == UsbConstants.USB_DIR_IN) "in" else "out")
        put("type", UsbClasses.endpointType(ep.type))
        put("maxPacketSize", ep.maxPacketSize)
        put("interval", ep.interval)
    }

    /* -------------------------------- usb_midi -------------------------- */

    private fun midiManager(): MidiManager? =
        runCatching { context.getSystemService(Context.MIDI_SERVICE) as? MidiManager }.getOrNull()

    private suspend fun usbMidi(args: JsonObject): String {
        val manager = midiManager() ?: return "ERROR: MIDI 服务不可用"
        return when ((args.strArg("action") ?: "list").lowercase()) {
            "list" -> midiList(manager)
            "send" -> midiSend(manager, args)
            "listen" -> midiListen(manager, args)
            else -> "ERROR: action 仅支持 list|send|listen"
        }
    }

    private fun midiList(manager: MidiManager): String {
        val devices = runCatching { manager.devices }.getOrDefault(emptyArray())
        return buildJsonObject {
            put("status", "OK")
            put("count", devices.size)
            put("midiSupported", context.packageManager.hasSystemFeature(PackageManager.FEATURE_MIDI))
            putJsonArray("devices") {
                devices.forEach { info ->
                    val properties = info.properties
                    add(buildJsonObject {
                        put("id", info.id)
                        put("type", midiTypeName(info.type))
                        put("name", properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: "")
                        put("manufacturer", properties.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER) ?: "")
                        put("product", properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT) ?: "")
                        put("version", properties.getString(MidiDeviceInfo.PROPERTY_VERSION) ?: "")
                        put("serial", properties.getString(MidiDeviceInfo.PROPERTY_SERIAL_NUMBER) ?: "")
                        put("private", info.isPrivate)
                        put("inputPorts", info.inputPortCount)
                        put("outputPorts", info.outputPortCount)
                        putJsonArray("ports") {
                            info.ports?.forEach { port ->
                                add(buildJsonObject {
                                    put("name", port.name ?: "")
                                    put("direction", if (port.type == MidiDeviceInfo.PortInfo.TYPE_INPUT) {
                                        "input (app → device, 用于 send)"
                                    } else "output (device → app, 用于 listen)")
                                })
                            }
                        }
                    })
                }
            }
            put("note", "USB MIDI 设备由系统 MidiService 接管，打开前请确认设备未被其他 App 独占")
        }.toString()
    }

    private fun midiTypeName(type: Int): String = when (type) {
        MidiDeviceInfo.TYPE_USB -> "usb"
        MidiDeviceInfo.TYPE_BLUETOOTH -> "bluetooth"
        MidiDeviceInfo.TYPE_VIRTUAL -> "virtual"
        else -> "type_$type"
    }

    private fun findMidiInfo(manager: MidiManager, args: JsonObject): MidiDeviceInfo? {
        val devices = runCatching { manager.devices }.getOrDefault(emptyArray())
        val id = args.intArg("device_id")
        val nameArg = args.strArg("name")
        return devices.firstOrNull { id != null && it.id == id }
            ?: devices.firstOrNull { info ->
                nameArg != null && (
                    (info.properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: "").contains(nameArg, ignoreCase = true) ||
                        (info.properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT) ?: "").contains(nameArg, ignoreCase = true)
                    )
            }
            ?: if (id == null && nameArg == null && devices.size == 1) devices.first() else null
    }

    private fun openMidiDevice(manager: MidiManager, info: MidiDeviceInfo): Pair<MidiDevice?, String> {
        val latch = CountDownLatch(1)
        val opened = AtomicReference<MidiDevice?>(null)
        val error = AtomicReference<String?>(null)
        val listener = MidiManager.OnDeviceOpenedListener { device ->
            if (device == null) error.set("系统拒绝打开设备（可能被其他 App 独占或已断开）")
            opened.set(device)
            latch.countDown()
        }
        return try {
            manager.openDevice(info, listener, Handler(Looper.getMainLooper()))
            if (!latch.await(6, TimeUnit.SECONDS)) null to "打开 MIDI 设备超时（6s）"
            else if (opened.get() == null) null to (error.get() ?: "打开 MIDI 设备失败")
            else opened.get() to ""
        } catch (e: Exception) {
            null to (e.message ?: e.javaClass.simpleName)
        }
    }

    private fun buildMidiBytes(args: JsonObject): Pair<ByteArray?, String> {
        val type = (args.strArg("type") ?: "note_on").lowercase()
        val channel = args.intArg("channel") ?: 1
        return when (type) {
            "note_on" -> {
                val note = args.intArg("note") ?: return null to "note_on 需要 note"
                val velocity = args.intArg("velocity") ?: 100
                if (note !in 0..127) return null to "note 必须在 0..127"
                MidiCodec.noteOn(channel, note, velocity) to ""
            }
            "note_off" -> {
                val note = args.intArg("note") ?: return null to "note_off 需要 note"
                MidiCodec.noteOff(channel, note, args.intArg("velocity") ?: 64) to ""
            }
            "control_change", "cc" -> {
                val controller = args.intArg("controller") ?: return null to "control_change 需要 controller"
                val value = args.intArg("value") ?: return null to "control_change 需要 value"
                if (controller !in 0..127 || value !in 0..127) return null to "controller/value 必须在 0..127"
                MidiCodec.controlChange(channel, controller, value) to ""
            }
            "program_change" -> {
                val program = args.intArg("program") ?: return null to "program_change 需要 program"
                if (program !in 0..127) return null to "program 必须在 0..127"
                MidiCodec.programChange(channel, program) to ""
            }
            "pitch_bend" -> {
                val value = args.intArg("value") ?: 8192
                if (value !in 0..16383) return null to "pitch_bend value 必须在 0..16383（中心 8192）"
                MidiCodec.pitchBend(channel, value) to ""
            }
            "hex" -> {
                val bytes = MidiCodec.parseHex(args.strArg("bytes"))
                    ?: return null to "bytes 不是合法 MIDI 十六进制（需以状态字节 0x80-0xFF 开头，≤64 字节）"
                bytes to ""
            }
            else -> null to "type 仅支持 note_on|note_off|control_change|program_change|pitch_bend|hex"
        }
    }

    private fun midiSend(manager: MidiManager, args: JsonObject): String {
        val info = findMidiInfo(manager, args) ?: return "ERROR: 未找到目标 MIDI 设备；先 action=list"
        val (bytes, buildError) = buildMidiBytes(args)
        if (bytes == null) return "ERROR: $buildError"
        if (info.inputPortCount <= 0) return "ERROR: 设备 ${info.id} 没有输入端口，无法发送"
        val portIndex = (args.intArg("port") ?: 0).coerceIn(0, info.inputPortCount - 1)
        val (device, openError) = openMidiDevice(manager, info)
        if (device == null) return "ERROR: $openError"
        var port: MidiInputPort? = null
        return try {
            port = device.openInputPort(portIndex)
                ?: return "ERROR: 无法打开输入端口 $portIndex"
            val sent = runCatching { port?.onSend(bytes, 0, bytes.size, 0) }.isSuccess
            Thread.sleep(120)
            if (!sent) "ERROR: 发送 MIDI 消息失败" else buildJsonObject {
                put("status", "OK")
                put("action", "send")
                put("deviceId", info.id)
                put("port", portIndex)
                put("bytes", bytes.joinToString(" ") { "%02X".format(it) })
                put("decoded", MidiCodec.describe(bytes))
            }.toString()
        } finally {
            runCatching { port?.close() }
            runCatching { device.close() }
        }
    }

    private fun midiListen(manager: MidiManager, args: JsonObject): String {
        val info = findMidiInfo(manager, args) ?: return "ERROR: 未找到目标 MIDI 设备；先 action=list"
        if (info.outputPortCount <= 0) return "ERROR: 设备 ${info.id} 没有输出端口，无法监听"
        val portIndex = (args.intArg("port") ?: 0).coerceIn(0, info.outputPortCount - 1)
        val seconds = (args.intArg("seconds") ?: 5).coerceIn(1, 30)
        val limit = (args.intArg("limit") ?: 60).coerceIn(1, 200)
        val (device, openError) = openMidiDevice(manager, info)
        if (device == null) return "ERROR: $openError"

        val messages = mutableListOf<String>()
        val receiver = object : MidiReceiver() {
            override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
                if (count <= 0) return
                synchronized(messages) {
                    if (messages.size < limit) {
                        messages += MidiCodec.describe(msg.copyOfRange(offset, offset + count))
                    }
                }
            }

            override fun onFlush() {}
        }
        var port: MidiOutputPort? = null
        return try {
            port = device.openOutputPort(portIndex) ?: return "ERROR: 无法打开输出端口 $portIndex"
            port.connect(receiver)
            Thread.sleep(seconds * 1000L)
            buildJsonObject {
                put("status", "OK")
                put("action", "listen")
                put("deviceId", info.id)
                put("port", portIndex)
                put("seconds", seconds)
                put("count", synchronized(messages) { messages.size })
                put("truncated", synchronized(messages) { messages.size } >= limit)
                putJsonArray("messages") {
                    synchronized(messages) { messages.forEach { message -> add(JsonPrimitive(message)) } }
                }
            }.toString()
        } finally {
            runCatching { port?.disconnect(receiver) }
            runCatching { port?.close() }
            runCatching { device.close() }
        }
    }

    private companion object {
        const val MANIFEST_CONNECT = "android.permission.BLUETOOTH_CONNECT"
        const val MANIFEST_ADVERTISE = "android.permission.BLUETOOTH_ADVERTISE"
        const val BATTERY_NAMESPACE = "bt_battery"
        val BATTERY_SERVICE_UUID: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        val BATTERY_LEVEL_UUID: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

        data class AdvState(
            val advertiser: BluetoothLeAdvertiser,
            val callback: AdvertiseCallback,
            val config: JsonObject,
            val startedAt: Long,
            val expiresAt: Long,
        ) {
            fun isLive(): Boolean = System.currentTimeMillis() < expiresAt
        }

        @Volatile
        private var advState: AdvState? = null
        private val stopHandler = Handler(Looper.getMainLooper())
    }
}
