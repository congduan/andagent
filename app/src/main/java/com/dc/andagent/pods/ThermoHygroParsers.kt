package com.dc.andagent.pods

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicInteger

/**
 * R148：BLE 温湿度计明文广播解析（小米/青萍 LYWSD03MMC 家族）。
 * 设备每秒把温湿度写进广播包，扫描即读，无需配对/连接。
 *
 * 覆盖 3 类 service data（由 [ThermoHygroParsers.decode] 按 UUID 分发）：
 *  · 0x181A Environmental Sensing：ATC1441（MAC 开头，大端）与 pvvx custom（magic 开头，小端）
 *  · 0xFE95 Xiaomi MiBeacon：v3/v5 明文 object 段
 *
 * 关于 MiBeacon 的诚实边界：本实现按简报给出的简化 object 模型
 * （1 字节 event type + 1 字节长度 + value，即 TLV）解析，只认 0x0D 温度、
 * 0x04 湿度、0x0A 电池 mV。真实 MiBeacon 的 2 字节 object id / 能力位变体
 * 未覆盖；加密广播（token/HMAC）不破解、不解密库，一律返回 null 并由
 * [encryptedCount] 与 [ENCRYPTED_NOTE] 如实标注。未覆盖格式计入 [unknownCount]。
 *
 * 每个 parser 都是纯函数：长度不足、魔数/版本不符、读数越界一律返回 null，
 * 绝不抛异常（广播来自不可信的射频环境）。
 */
data class ThermoSnapshot(
    val name: String,
    val mac: String?,
    val temperatureC: Double?,
    val humidityPercent: Double?,
    val batteryPercent: Int?,
    val batteryMv: Int?,
    val rssi: Int,
    val ts: Long,
    val sourceFormat: String,
) {
    /** 摄氏为主，附带华氏换算字段供工具返回。 */
    fun temperatureF(): Double? = temperatureC?.let { it * 9.0 / 5.0 + 32.0 }

    fun json() = buildJsonObject {
        put("name", name)
        put("mac", mac)
        put("temperatureC", temperatureC)
        put("temperatureF", temperatureF())
        put("humidityPercent", humidityPercent)
        put("batteryPercent", batteryPercent)
        put("batteryMv", batteryMv)
        put("rssi", rssi)
        put("ts", ts)
        put("sourceFormat", sourceFormat)
    }
}

/* ------------------------------ 字节读取 helper ------------------------------ */

private fun u8(b: ByteArray, i: Int): Int = b[i].toInt() and 0xFF
private fun u16le(b: ByteArray, i: Int): Int = u8(b, i) or (u8(b, i + 1) shl 8)
private fun u16be(b: ByteArray, i: Int): Int = (u8(b, i) shl 8) or u8(b, i + 1)
private fun s16le(b: ByteArray, i: Int): Int = u16le(b, i).toShort().toInt()
private fun s16be(b: ByteArray, i: Int): Int = u16be(b, i).toShort().toInt()

/** 广播里的 MAC 通常按字节序排列；MiBeacon 实际是反序（低字节在前），故 reversed=true。 */
private fun hexMac(b: ByteArray, off: Int, reversed: Boolean): String = buildString {
    for (k in 0 until 6) {
        if (k > 0) append(':')
        val v = u8(b, off + (if (reversed) 5 - k else k))
        append(v.toString(16).uppercase().padStart(2, '0'))
    }
}

/** 优先用系统给的 device.address；广播内 MAC 作为兜底（单测传 null 时可验证派生结果）。 */
private fun pickMac(passed: String?, b: ByteArray, off: Int, reversed: Boolean): String? =
    passed?.takeIf { it.isNotBlank() } ?: hexMac(b, off, reversed)

private fun plausibleTemp(c: Double) = c in -50.0..100.0
private fun plausibleHumidity(h: Double) = h in 0.0..100.0
private fun plausibleBattery(p: Int) = p in 0..100
private fun plausibleMv(mv: Int) = mv in 0..5000

/* ------------------------------- 1. ATC1441 ------------------------------- */

/** ATC1441：MAC(6) + temp(i16 BE /10) + hum(u8) + batt%(u8) + batt_mV(u16 BE) + counter(u8)。 */
object Atc1441Parser {
    const val FORMAT = "ATC1441"

    fun parse(bytes: ByteArray, rssi: Int, mac: String? = null, ts: Long = System.currentTimeMillis()): ThermoSnapshot? {
        if (bytes.size < 13) return null
        val temp = s16be(bytes, 6) / 10.0
        val hum = u8(bytes, 8).toDouble()
        val batt = u8(bytes, 9)
        val mv = u16be(bytes, 10)
        if (!plausibleTemp(temp) || !plausibleHumidity(hum) || !plausibleBattery(batt) || !plausibleMv(mv)) return null
        return ThermoSnapshot(
            name = "LYWSD03MMC (ATC1441)",
            mac = pickMac(mac, bytes, 0, false),
            temperatureC = temp,
            humidityPercent = hum,
            batteryPercent = batt,
            batteryMv = mv,
            rssi = rssi,
            ts = ts,
            sourceFormat = FORMAT,
        )
    }
}

/* ------------------------------- 2. pvvx ------------------------------- */

/**
 * pvvx / ATC custom（v4 魔数 0x0845 / v5 魔数 0x0A45），全小端：
 * magic(u16) + flags(u8) + batt_mV(u16) + MAC(6) + temp(s16 /100) + hum(u16 /100)
 * + batt%(u8) + counter(u8) + flags2(u8)。字段合计 18 字节，容忍更长包。
 */
object PvvxParser {
    private const val MAGIC_V4 = 0x0845
    private const val MAGIC_V5 = 0x0A45

    fun versionOf(bytes: ByteArray): Int? = when (if (bytes.size >= 2) u16le(bytes, 0) else -1) {
        MAGIC_V4 -> 4
        MAGIC_V5 -> 5
        else -> null
    }

    fun parse(bytes: ByteArray, rssi: Int, mac: String? = null, ts: Long = System.currentTimeMillis()): ThermoSnapshot? {
        val v = versionOf(bytes) ?: return null
        if (bytes.size < 18) return null
        val mv = u16le(bytes, 3)
        val temp = s16le(bytes, 11) / 100.0
        val hum = u16le(bytes, 13) / 100.0
        val batt = u8(bytes, 15)
        if (!plausibleTemp(temp) || !plausibleHumidity(hum) || !plausibleBattery(batt) || !plausibleMv(mv)) return null
        return ThermoSnapshot(
            name = "LYWSD03MMC (pvvx v$v)",
            mac = pickMac(mac, bytes, 5, false),
            temperatureC = temp,
            humidityPercent = hum,
            batteryPercent = batt,
            batteryMv = mv,
            rssi = rssi,
            ts = ts,
            sourceFormat = if (v == 4) "pvvx4" else "pvvx5",
        )
    }
}

/* ------------------------------ 3. MiBeacon ------------------------------ */

/**
 * Xiaomi MiBeacon（service data 0xFE95）：
 * frame control(u16 LE) + product id(u16 LE) + counter(u8) + MAC(6, 反序)
 * [+ v5 capability/IOK 字节] + object TLV 段。
 * frame control：version = bits 12..15；0x08 加密、0x10 含 MAC、0x20 含 capability、
 * 0x40 含 object。加密帧一律返回 null（不破解）。
 */
object MiBeaconParser {
    private const val FC_ENCRYPTED = 0x08
    private const val FC_MAC = 0x10
    private const val FC_CAPABILITY = 0x20
    private const val FC_OBJECT = 0x40

    private const val OBJ_TEMPERATURE = 0x0D
    private const val OBJ_HUMIDITY = 0x04
    private const val OBJ_BATTERY_MV = 0x0A

    fun isEncrypted(bytes: ByteArray): Boolean = bytes.size >= 2 && (u16le(bytes, 0) and FC_ENCRYPTED) != 0

    private fun modelName(product: Int): String = when (product) {
        0x055B -> "LYWSD03MMC"
        else -> "Xiaomi MiBeacon (pid 0x${product.toString(16).uppercase().padStart(4, '0')})"
    }

    fun parse(bytes: ByteArray, rssi: Int, mac: String? = null, ts: Long = System.currentTimeMillis()): ThermoSnapshot? {
        if (bytes.size < 5) return null
        val fc = u16le(bytes, 0)
        val version = (fc shr 12) and 0xF
        if (version != 3 && version != 5) return null
        if (isEncrypted(bytes)) return null
        val product = u16le(bytes, 2)
        val hasMac = (fc and FC_MAC) != 0
        if ((fc and FC_OBJECT) == 0) return null
        // counter(1) 在 index 4，其后是 MAC(6)；v5 能力位存在时再跳 1 字节能力描述。
        var off = 5 + (if (hasMac) 6 else 0)
        if (version == 5 && (fc and FC_CAPABILITY) != 0) off += 1

        var temp: Double? = null
        var hum: Double? = null
        var mv: Int? = null
        while (off + 2 <= bytes.size) {
            val type = u8(bytes, off)
            val len = u8(bytes, off + 1)
            off += 2
            if (off + len > bytes.size) break
            when (type) {
                OBJ_TEMPERATURE -> if (len >= 2) temp = s16le(bytes, off) / 10.0
                OBJ_HUMIDITY -> if (len >= 2) hum = u16le(bytes, off) / 10.0
                    else if (len == 1) hum = u8(bytes, off).toDouble()
                OBJ_BATTERY_MV -> if (len >= 2) mv = u16le(bytes, off)
            }
            off += len
        }
        temp?.let { if (!plausibleTemp(it)) temp = null }
        hum?.let { if (!plausibleHumidity(it)) hum = null }
        mv?.let { if (!plausibleMv(it)) mv = null }
        if (temp == null && hum == null && mv == null) return null
        return ThermoSnapshot(
            name = modelName(product),
            mac = if (hasMac) pickMac(mac, bytes, 5, true) else mac,
            temperatureC = temp,
            humidityPercent = hum,
            batteryPercent = null,
            batteryMv = mv,
            rssi = rssi,
            ts = ts,
            sourceFormat = if (version == 3) "MiBeacon3" else "MiBeacon5",
        )
    }
}

/* ------------------------------ 分发器 ------------------------------ */

object ThermoHygroParsers {
    const val UUID_181A = "0000181a-0000-1000-8000-00805f9b34fb"
    const val UUID_FE95 = "0000fe95-0000-1000-8000-00805f9b34fb"

    /** 加密广播不破解，如实引导用户改用可本地读取的明文方案。 */
    const val ENCRYPTED_NOTE = "加密广播，需在米家开本地解锁（LYWSD03MMC 刷 pvvx 固件或开启无加密广播）"

    /** 未发现明文广播时的下一步指引（工具返回与战报可见）。 */
    const val GUIDE = "未发现明文温湿度广播：确认设备为 ATC/pvvx 固件或米家已开无加密广播；加密版需刷固件（pvvx）才能本地读取。"

    private val unknown = AtomicInteger(0)
    private val encrypted = AtomicInteger(0)

    val unknownCount: Int get() = unknown.get()
    val encryptedCount: Int get() = encrypted.get()

    fun resetCounters() {
        unknown.set(0)
        encrypted.set(0)
    }

    /**
     * 按 service data UUID 分发。0x181A 先认 pvvx 魔数，否则按 ATC1441；0xFE95 走 MiBeacon。
     * 返回 null 时把该包计入 unknownCount（FE95 加密帧计入 encryptedCount）。
     */
    fun decode(uuid: String, bytes: ByteArray?, rssi: Int, mac: String?, ts: Long): ThermoSnapshot? {
        if (bytes == null || bytes.isEmpty()) return null
        val snapshot = when (uuid.lowercase()) {
            UUID_181A -> if (PvvxParser.versionOf(bytes) != null) PvvxParser.parse(bytes, rssi, mac, ts)
                else Atc1441Parser.parse(bytes, rssi, mac, ts)
            UUID_FE95 -> MiBeaconParser.parse(bytes, rssi, mac, ts)
            else -> return null
        }
        if (snapshot == null) {
            if (uuid.equals(UUID_FE95, ignoreCase = true) && MiBeaconParser.isEncrypted(bytes)) encrypted.incrementAndGet()
            else unknown.incrementAndGet()
        }
        return snapshot
    }
}
