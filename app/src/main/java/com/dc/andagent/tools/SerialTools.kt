package com.dc.andagent.tools

import android.content.ContentValues
import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import com.dc.andagent.usb.SerialDeviceKind
import com.dc.andagent.usb.UsbSerialPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

val USB_SERIAL_TOOLS = listOf("usb_serial_open", "usb_serial_read", "usb_serial_write", "usb_serial_close", "usb_serial_capture")

internal data class SerialSession(val deviceId: Int, val port: UsbSerialPort) {
    val lock = Any()
}

internal object SerialSessions {
    val sessions = ConcurrentHashMap<String, SerialSession>()
}

/** 供 EspFlashTools 复用同一份会话表（单点持有），不另开设备。 */
internal fun espSession(handle: String): SerialSession =
    SerialSessions.sessions[handle] ?: throw IllegalArgumentException("handle 不存在或已关闭")

class SerialTool(private val context: Context, override val name: String) : Tool {
    override val description: String = when (name) {
        "usb_serial_open" -> "按 usb_list 的 deviceId 或 VID:PID 打开 CDC-ACM、CH340/341、CP210x、FTDI USB 串口；未授权时提示调用 otg_request(permission=usb)。"
        "usb_serial_read" -> "读取 USB 串口字节并返回 hex 与 UTF-8 双视图。"
        "usb_serial_write" -> "向 USB 串口发送十六进制字节或 UTF-8 文本。"
        "usb_serial_close" -> "关闭 USB 串口会话并释放接口。"
        else -> "连续捕获 USB 串口日志并保存到 Downloads/AndAgentUsb。"
    }
    override val parameters: JsonObject = when (name) {
        "usb_serial_open" -> schemaOf(
            "device_id" to intParam("usb_list 返回的 deviceId；或使用 vid_pid"),
            "vid_pid" to strParam("VID:PID，如 1a86:7523；相同 VID:PID 多台设备时使用 device_id"),
            "baud" to intParam("波特率，默认 115200"),
            "data_bits" to intParam("数据位，默认 8"),
            "stop_bits" to intParam("停止位，默认 1"),
            "parity" to strParam("none|odd|even|mark|space，默认 none"),
            "dtr" to boolParam("DTR 控制线是否 asserted，默认 true（ESP32 烧录：DTR=IO0）"),
            "rts" to boolParam("RTS 控制线是否 asserted，默认 true（ESP32 烧录：RTS=EN）"),
        )
        "usb_serial_read" -> schemaOf("handle" to strParam("open 返回的 handle"), "timeout_ms" to intParam("等待毫秒，默认 1000，1..60000"), "max_bytes" to intParam("最大字节数，默认 4096，1..65536"), required = listOf("handle"))
        "usb_serial_write" -> schemaOf("handle" to strParam("open 返回的 handle"), "data_hex" to strParam("要发送的十六进制字节"), "data_text" to strParam("要发送的 UTF-8 文本；与 data_hex 二选一"), required = listOf("handle"))
        "usb_serial_close" -> schemaOf("handle" to strParam("open 返回的 handle"), required = listOf("handle"))
        else -> schemaOf("handle" to strParam("open 返回的 handle"), "duration_s" to intParam("捕获秒数，默认 10，1..300"), "save_to" to strParam("保存文件名，仅允许 Downloads/AndAgentUsb 内的 .log/.txt 文件；省略自动命名"), required = listOf("handle"))
    }
    override val destructive = false
    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        runCatching { when (name) {
            "usb_serial_open" -> open(args)
            "usb_serial_read" -> read(args)
            "usb_serial_write" -> write(args)
            "usb_serial_close" -> close(args)
            "usb_serial_capture" -> capture(args)
            else -> "ERROR: 未知工具 $name"
        } }.getOrElse { "ERROR: ${it.message ?: it.javaClass.simpleName}" }
    }
    private val usb get() = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private fun select(args: JsonObject): UsbDevice {
        val id = args.intArg("device_id")
        val pair = args.strArg("vid_pid")?.lowercase()?.removePrefix("0x")?.replace("0x", "")
        require(id != null || pair != null) { "需要 device_id 或 vid_pid" }
        val matches = usb.deviceList.values.filter { d ->
            if (id != null) d.deviceId == id
            else "%04x:%04x".format(d.vendorId, d.productId) == pair
        }
        require(matches.isNotEmpty()) { "未找到 USB 设备；先调用 usb_list" }
        require(matches.size == 1) { "多个设备匹配 VID:PID，请使用 device_id" }
        return matches.single()
    }
    private fun open(args: JsonObject): String {
        val device = select(args)
        val kind = SerialDeviceKind.detect(device)
            ?: return "ERROR: 不支持的串口芯片 VID=%04x PID=%04x".format(device.vendorId, device.productId)
        if (!usb.hasPermission(device)) return "ERROR: USB 设备尚未授权；先调用 otg_request(permission=usb)，再重试 usb_serial_open（device_id=${device.deviceId}）"
        if (SerialSessions.sessions.values.any { it.deviceId == device.deviceId }) return "ERROR: 设备 ${device.deviceId} 已打开，请先关闭旧 handle"
        val iface = SerialDeviceKind.dataInterface(device, kind) ?: return "ERROR: $kind 无可用 bulk IN/OUT 数据接口"
        val baud = args.intArg("baud") ?: 115200
        val bits = args.intArg("data_bits") ?: 8
        val stops = args.intArg("stop_bits") ?: 1
        val parity = args.strArg("parity") ?: "none"
        val dtr = args.boolArg("dtr") != false
        val rts = args.boolArg("rts") != false
        val connection = usb.openDevice(device) ?: return "ERROR: UsbManager.openDevice 失败；确认授权后重试"
        val port = SerialDeviceKind.create(device, kind)
        try { port.open(connection, iface); port.setBaud(baud, bits, stops, parity); port.setDtrRts(dtr, rts) }
        catch (e: Exception) { runCatching { port.close() }; runCatching { connection.close() }; throw e }
        val handle = UUID.randomUUID().toString()
        SerialSessions.sessions[handle] = SerialSession(device.deviceId, port)
        return buildJsonObject {
            put("status", "OK"); put("handle", handle); put("deviceId", device.deviceId)
            put("driver", kind); put("baud", baud); put("dataBits", bits); put("stopBits", stops); put("parity", parity)
            put("interfaceId", iface.id); put("inEndpoint", "0x%02x".format(port.readEndpoint.address))
            put("outEndpoint", "0x%02x".format(port.writeEndpoint.address))
            put("inMaxPacketSize", port.readEndpoint.maxPacketSize); put("outMaxPacketSize", port.writeEndpoint.maxPacketSize)
        }.toString()
    }
    private fun session(args: JsonObject): SerialSession = espSession(args.strArg("handle") ?: "")
    private fun read(args: JsonObject): String {
        val session = session(args)
        val timeout = (args.intArg("timeout_ms") ?: 1000).also { require(it in 1..60000) }
        val max = (args.intArg("max_bytes") ?: 4096).also { require(it in 1..65536) }
        val buf = ByteArray(max)
        val n = synchronized(session.lock) { session.port.read(buf, timeout) }
        return buildJsonObject { put("status", "OK"); put("count", n)
            put("hex", buf.copyOf(n).joinToString("") { "%02x".format(it.toInt() and 255) })
            put("utf8", String(buf, 0, n, Charsets.UTF_8)) }.toString()
    }
    private fun write(args: JsonObject): String {
        val text = args.strArg("data_text")
        val hex = args.strArg("data_hex")
        require((text == null) != (hex == null)) { "data_hex 与 data_text 必须且只能填写一个" }
        val bytes = if (text != null) text.toByteArray(Charsets.UTF_8) else {
            val clean = hex!!.replace(Regex("[\\s:,]"), "").removePrefix("0x")
            require(clean.length % 2 == 0 && clean.matches(Regex("[0-9a-fA-F]*"))) { "data_hex 无效" }
            clean.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }
        val session = session(args)
        val n = synchronized(session.lock) { session.port.write(bytes) }
        return buildJsonObject { put("status", "OK"); put("written", n) }.toString()
    }
    private fun close(args: JsonObject): String {
        val handle = args.strArg("handle") ?: return "ERROR: 缺少 handle"
        val session = SerialSessions.sessions.remove(handle) ?: return "ERROR: handle 不存在或已关闭"
        synchronized(session.lock) { session.port.close() }
        return buildJsonObject { put("status", "OK"); put("handle", handle) }.toString()
    }
    private fun capture(args: JsonObject): String {
        val session = session(args)
        val duration = (args.intArg("duration_s") ?: 10).also { require(it in 1..300) }
        val file = args.strArg("save_to") ?: "usb-${System.currentTimeMillis()}.log"
        require(Regex("[A-Za-z0-9._-]{1,100}\\.(log|txt)", RegexOption.IGNORE_CASE).matches(file) && !file.startsWith('.')) { "save_to 必须为安全的 .log/.txt 文件名" }
        val resolver = context.contentResolver
        val uri = if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/AndAgentUsb")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                ?: throw IOException("MediaStore 创建日志失败")
        } else null
        val legacy = if (uri == null) File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "AndAgentUsb/$file") else null
        var count = 0L
        try {
            val out = if (uri != null) resolver.openOutputStream(uri) else { legacy!!.parentFile?.mkdirs(); legacy.outputStream() }
            (out ?: throw IOException("无法写入日志")).use { stream ->
                val deadline = SystemClock.elapsedRealtime() + duration * 1000L
                val buf = ByteArray(4096)
                synchronized(session.lock) {
                    while (SystemClock.elapsedRealtime() < deadline) {
                        val wait = minOf(1000L, deadline - SystemClock.elapsedRealtime()).toInt()
                        if (wait <= 0) break
                        val n = session.port.read(buf, wait)
                        if (n > 0) { stream.write(buf, 0, n); count += n }
                    }
                }
            }
        } catch (e: Exception) { if (uri != null) resolver.delete(uri, null, null) else legacy?.delete(); throw e }
        if (uri != null) resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return buildJsonObject { put("status", "OK"); put("bytes", count); put("path", "/sdcard/Download/AndAgentUsb/$file"); if (uri != null) put("uri", uri.toString()) }.toString()
    }
}
