package com.dc.andagent.pods

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Clean-room decoder of public 0x004C / 0x07 plaintext fields.
 * Layout: https://github.com/fischejo/airpods-notify/blob/master/doc/proximity_protocol.md
 * Cross-check: https://github.com/furiousMAC/continuity/blob/master/messages/proximity_pairing.md
 * The encrypted suffix is ignored. Only paired-mode frames are decoded.
 */
data class PodSnapshot(
    val model: String, val modelId: Int, val isLeftInEar: Boolean?, val isRightInEar: Boolean?,
    val batteryLeft: Int?, val batteryRight: Int?, val batteryCase: Int?,
    val chargingL: Boolean?, val chargingR: Boolean?, val chargingCase: Boolean?,
    val lidOpen: Boolean?, val isConnected: Boolean?, val rssi: Int,
    val timestamp: Long = System.currentTimeMillis(), val address: String? = null,
) {
    fun json() = buildJsonObject {
        put("model", model); put("modelId", modelId)
        put("isLeftInEar", isLeftInEar); put("isRightInEar", isRightInEar)
        put("batteryLeft", batteryLeft); put("batteryRight", batteryRight); put("batteryCase", batteryCase)
        put("chargingL", chargingL); put("chargingR", chargingR); put("chargingCase", chargingCase)
        put("lidOpen", lidOpen); put("isConnected", isConnected); put("rssi", rssi)
        put("timestamp", timestamp); put("connectionSource", "advertisement_inference")
    }
}

object AppleBleParser {
    private val models = mapOf(
        0x0220 to "AirPods 1", 0x0f20 to "AirPods 2", 0x1320 to "AirPods 3",
        0x1920 to "AirPods 4", 0x1b20 to "AirPods 4 ANC", 0x0e20 to "AirPods Pro",
        0x1420 to "AirPods Pro 2", 0x2420 to "AirPods Pro 2 USB-C",
        0x0a20 to "AirPods Max", 0x1f20 to "AirPods Max USB-C",
        0x0b20 to "Powerbeats Pro", 0x0520 to "Beats X", 0x1020 to "Beats Flex",
        0x1120 to "Beats Studio Buds", 0x0620 to "Beats Solo 3",
        0x0920 to "Beats Studio 3", 0x0320 to "Powerbeats 3", 0x0c20 to "Beats Solo Pro",
    )
    private fun level(n: Int): Int? = if (n == 15) null else (n * 10).coerceAtMost(100)

    /** Data excludes the two-byte Bluetooth company identifier.
     * 解析整条厂商数据（可含多条 Continuity 消息链：type+len+payload 串联），
     * 逐条尝试 0x07 状态帧——连接态广播可能把状态消息放在链的第二条
     * （对照 CAPod ContinuityProtocol.Decoder 的链式解析）。 */
    fun parse(data: ByteArray, rssi: Int, address: String? = null, now: Long = System.currentTimeMillis()): PodSnapshot? {
        var remaining = data
        var best: PodSnapshot? = null
        while (remaining.size >= 2) {
            val msgLen = (remaining[1].toInt() and 255)
            if (msgLen + 2 > remaining.size) break
            val msg = remaining.copyOfRange(0, msgLen + 2)
            parseSingle(msg, rssi, address, now)?.let { if (best == null || it.isLeftInEar != null) best = it }
            remaining = remaining.copyOfRange(msgLen + 2, remaining.size)
        }
        return best
    }

    /** 解析单条 Continuity 消息（type + len + payload）。 */
    private fun parseSingle(data: ByteArray, rssi: Int, address: String?, now: Long): PodSnapshot? {
        if (data.size < 11 || (data[0].toInt() and 255) != 0x07) return null
        val length = data[1].toInt() and 255
        if (length < 9 || data.size < length + 2) return null
        val id = ((data[3].toInt() and 255) shl 8) or (data[4].toInt() and 255)
        val model = models[id] ?: return null
        if ((data[2].toInt() and 255) == 0) {
            if (data.size < 15) return null
            fun pairingLevel(index: Int) = (data[index].toInt() and 255).takeIf { it <= 100 }
            return PodSnapshot(model, id, null, null, pairingLevel(13), pairingLevel(12),
                pairingLevel(14), null, null, null, null, null, rssi, now, address)
        }
        if ((data[2].toInt() and 255) != 1) return null
        val status = data[5].toInt() and 255
        val battery = data[6].toInt() and 255
        val flags = data[7].toInt() and 255
        val lid = data[8].toInt() and 255
        val connectedByte = data[10].toInt() and 255
        val flipped = status and 0x40 != 0
        val leftRaw = if (flipped) battery and 15 else battery shr 4
        val rightRaw = if (flipped) battery shr 4 else battery and 15
        val leftCharge = if (flipped) flags and 1 != 0 else flags and 2 != 0
        val rightCharge = if (flipped) flags and 2 != 0 else flags and 1 != 0
        val single = model.startsWith("AirPods Max") || model.startsWith("Beats Solo") || model.startsWith("Beats Studio 3")
        return PodSnapshot(
            model, id,
            if (single) null else if (flipped) status and 8 != 0 else status and 2 != 0,
            if (single) null else if (flipped) status and 2 != 0 else status and 8 != 0,
            if (single) null else level(leftRaw), level(rightRaw),
            if (single) null else level(flags shr 4),
            if (single) null else leftCharge, rightCharge, if (single) null else flags and 4 != 0,
            if (single) null else lid and 8 != 0,
            when (connectedByte) { 0 -> false; 4, 5, 6, 7, 9 -> true; else -> null },
            rssi, now, address,
        )
    }
}
