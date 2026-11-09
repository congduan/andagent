package com.dc.andagent.tools

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import com.dc.andagent.usb.EspFlasher
import com.dc.andagent.usb.EspReset
import com.dc.andagent.usb.EspSerialIo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

val ESP_FLASH_TOOLS = listOf("esp_flash", "esp_monitor")

/** ESP32 ROM bootloader 烧录 + 监视：复用 SerialSessions 的已打开串口，不自己开设备。 */
class EspFlashTool(private val context: Context, override val name: String) : Tool {
    override val description: String = when (name) {
        "esp_flash" -> "把固件镜像经 USB 串口烧录到 ESP32 系列 ROM bootloader（SLIP 协议：SYNC→FLASH_BEGIN/DATA/END→SPI_FLASH_MD5 校验→硬复位）。需先用 usb_serial_open 打开串口（不要自己开设备）；image_path 为镜像绝对路径，offset 必填（bootloader=0x1000(ESP32)/0x0(ESP8266)、app=0x10000）；flash_size_mb 默认 4。镜像 ≤8MB。烧录后用 esp_monitor 回读日志验证。破坏性：改写芯片 flash。"
        else -> "硬复位（RTS 脉冲）后捕获 USB 串口输出，用于 ESP32 烧录后回读启动日志验证。handle 为 usb_serial_open 返回值；duration_s 默认 5（1..60）；save_to 可另存到 Downloads/AndAgentUsb 的 .log/.txt。返回 utf8 文本与字节数。"
    }

    override val parameters: JsonObject = when (name) {
        "esp_flash" -> schemaOf(
            "handle" to strParam("usb_serial_open 返回的 handle"),
            "image_path" to strParam("镜像绝对路径（/sdcard/ 或 app filesDir 下，存在且 ≤8MB）"),
            "offset" to intParam("烧录偏移，必填，≥0；bootloader=0x1000(ESP32)/0x0(ESP8266)、app=0x10000"),
            "flash_size_mb" to intParam("flash 容量 MB，默认 4，范围 1..16"),
            required = listOf("handle", "image_path", "offset"),
        )
        else -> schemaOf(
            "handle" to strParam("usb_serial_open 返回的 handle"),
            "duration_s" to intParam("捕获秒数，默认 5，范围 1..60"),
            "save_to" to strParam("可选 .log/.txt 文件名，保存到 Downloads/AndAgentUsb"),
            required = listOf("handle"),
        )
    }

    override val destructive: Boolean = name == "esp_flash"

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        runCatching {
            when (name) {
                "esp_flash" -> flash(args)
                "esp_monitor" -> monitor(args)
                else -> "ERROR: 未知工具 $name"
            }
        }.getOrElse { "ERROR: ${it.message ?: it.javaClass.simpleName}" }
    }

    private fun flash(args: JsonObject): String {
        val session = espSession(args.strArg("handle") ?: "")
        val path = args.strArg("image_path") ?: throw IllegalArgumentException("缺少 image_path")
        val file = File(path)
        require(file.isFile) { "镜像文件不存在或非普通文件：$path" }
        require(file.length() <= 8L * 1024 * 1024) { "镜像超过 8MB：${file.length()} 字节" }
        val image = file.readBytes()
        val offset = args.intArg("offset") ?: throw IllegalArgumentException("缺少 offset（必填）")
        require(offset >= 0) { "offset 必须 ≥0" }
        val flashSizeMb = (args.intArg("flash_size_mb") ?: 4).also { require(it in 1..16) { "flash_size_mb 需 1..16" } }
        val flashSizeBytes = flashSizeMb * 1024 * 1024

        // 把已打开的 UsbSerialPort 适配成纯 JVM 核心的注入依赖（全程复用同一把锁）。
        val io = object : EspSerialIo {
            override fun read(buf: ByteArray, timeoutMs: Int): Int =
                synchronized(session.lock) { session.port.read(buf, timeoutMs) }
            override fun write(buf: ByteArray): Int =
                synchronized(session.lock) { session.port.write(buf) }
        }
        val control: (Boolean, Boolean) -> Unit = { dtr, rts ->
            synchronized(session.lock) { session.port.setDtrRts(dtr, rts) }
        }
        val sleepMs: (Long) -> Unit = { ms -> Thread.sleep(ms) }

        val r = EspFlasher(io, control, sleepMs).flash(image, offset, flashSizeBytes)
        return buildJsonObject {
            put("status", "OK")
            put("chip", r.chip)
            if (r.chipId != null) put("chipId", r.chipId) else put("chipId", JsonNull)
            if (r.mac != null) put("mac", r.mac) else put("mac", JsonNull)
            put("offset", r.offset)
            put("bytes", r.bytes)
            put("blocks", r.blocks)
            put("md5", r.md5)
            put("verified", r.verified)
            put("elapsedMs", r.elapsedMs)
            r.warning?.let { put("warning", it) }
        }.toString()
    }

    private fun monitor(args: JsonObject): String {
        val session = espSession(args.strArg("handle") ?: "")
        val duration = (args.intArg("duration_s") ?: 5).also { require(it in 1..60) { "duration_s 需 1..60" } }
        val saveTo = args.strArg("save_to")

        // 先硬复位（RTS 脉冲）让芯片重新启动，再捕获启动日志。
        synchronized(session.lock) {
            for (s in EspReset.hardReset()) {
                session.port.setDtrRts(s.dtr, s.rts)
                if (s.sleepAfterMs > 0) Thread.sleep(s.sleepAfterMs)
            }
        }

        val collected = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        synchronized(session.lock) {
            val deadline = SystemClock.elapsedRealtime() + duration * 1000L
            while (SystemClock.elapsedRealtime() < deadline) {
                val wait = minOf(1000L, deadline - SystemClock.elapsedRealtime()).toInt()
                if (wait <= 0) break
                val n = session.port.read(buf, wait)
                if (n > 0) collected.write(buf, 0, n)
            }
        }
        val bytes = collected.toByteArray()
        val full = String(bytes, Charsets.UTF_8)
        val text = if (full.length > 32768) full.take(32768) else full

        val savedPath = if (saveTo != null) saveCapture(saveTo, bytes) else null
        return buildJsonObject {
            put("status", "OK")
            put("bytes", bytes.size)
            put("utf8", text)
            if (full.length > 32768) put("truncated", true)
            if (savedPath != null) put("path", savedPath)
        }.toString()
    }

    /** 复用 usb_serial_capture 的 MediaStore 落盘语义（Downloads/AndAgentUsb，.log/.txt）。 */
    private fun saveCapture(saveTo: String, bytes: ByteArray): String {
        require(Regex("[A-Za-z0-9._-]{1,100}\\.(log|txt)", RegexOption.IGNORE_CASE).matches(saveTo) &&
            !saveTo.startsWith('.')) { "save_to 必须为安全的 .log/.txt 文件名" }
        val resolver = context.contentResolver
        val uri = if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, saveTo)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/AndAgentUsb")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                ?: throw IOException("MediaStore 创建日志失败")
        } else null
        val legacy = if (uri == null) {
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "AndAgentUsb/$saveTo")
        } else null
        try {
            val out = if (uri != null) resolver.openOutputStream(uri)
            else { legacy!!.parentFile?.mkdirs(); legacy.outputStream() }
            (out ?: throw IOException("无法写入日志")).use { it.write(bytes) }
        } catch (e: Exception) {
            if (uri != null) resolver.delete(uri, null, null) else legacy?.delete()
            throw e
        }
        if (uri != null) resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return "/sdcard/Download/AndAgentUsb/$saveTo"
    }
}
