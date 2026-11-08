package com.dc.andagent.tools

import android.content.Context
import android.hardware.ConsumerIrManager
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import kotlin.math.roundToInt

val IR_TOOLS = listOf("ir_available", "ir_send_raw", "ir_send_pronto")

/** Pronto 0000 learned code: one frequency word, then intro/repeat burst pairs. */
internal data class IrSignal(val carrierHz: Int, val pattern: IntArray)

internal object ProntoHex {
    private const val CLOCK_MICROS = 0.241246

    fun parse(hex: String): IrSignal {
        val tokens = hex.trim().split(Regex("[\\s,]+"))
        require(tokens.size >= 6 && tokens.all { it.matches(Regex("[0-9a-fA-F]{4}")) }) {
            "Pronto Hex 必须由 4 位十六进制字组成"
        }
        val words = tokens.map { it.toInt(16) }
        require(words[0] == 0x0000) { "仅支持 Pronto 0000 原始调制码；RC5/RC6 压缩码需先转换为原始脉冲" }
        require(words[1] > 0) { "Pronto 载波分频字必须大于 0" }
        val pairCount = words[2] + words[3]
        require(pairCount > 0 && pairCount <= 1024 && words.size == 4 + pairCount * 2) {
            "Pronto 引导/重复段长度与脉冲数据不匹配"
        }
        val frequency = (1_000_000.0 / (words[1] * CLOCK_MICROS)).roundToInt()
        require(frequency in 20_000..60_000) { "Pronto 载波频率超出 20–60 kHz" }
        val pattern = words.drop(4).map {
            require(it > 0) { "Pronto 脉冲时长必须大于 0" }
            (it * words[1] * CLOCK_MICROS).roundToInt().coerceAtLeast(1)
        }.toIntArray()
        validatePattern(pattern)
        return IrSignal(frequency, pattern)
    }
}

internal fun validatePattern(pattern: IntArray) {
    require(pattern.isNotEmpty() && pattern.size % 2 == 0 && pattern.size <= 2048) {
        "pattern 必须是非空的 on/off 微秒交替数组，且最多 2048 项"
    }
    require(pattern.all { it > 0 }) { "pattern 各项必须为正整数微秒" }
    require(pattern.sumOf { it.toLong() } <= 2_000_000L) { "红外信号总时长不能超过 2 秒" }
}

/**
 * ConsumerIrManager 封装：可用性探测与实际发射，供 ir_* 与 ir_code_* 工具共用。
 * 载波范围外的请求在这里统一拦截，避免把「系统静默不发」误报成发送成功。
 */
internal object IrEmitter {
    fun manager(context: Context): ConsumerIrManager? =
        context.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager

    fun available(context: Context): Boolean =
        runCatching { manager(context)?.hasIrEmitter() == true }.getOrDefault(false)

    /** 发射信号；成功返回 null，失败返回可直接回给模型的中文错误文案。 */
    fun send(context: Context, signal: IrSignal): String? {
        val manager = manager(context) ?: return "ERROR: 设备没有可用的红外发射器"
        if (!available(context)) return "ERROR: 设备没有可用的红外发射器"
        val ranges = runCatching { manager.carrierFrequencies }.getOrNull()
        if (!ranges.isNullOrEmpty() && ranges.none { signal.carrierHz in it.minFrequency..it.maxFrequency }) {
            return "ERROR: 设备不支持 ${signal.carrierHz} Hz 载波"
        }
        return runCatching {
            manager.transmit(signal.carrierHz, signal.pattern)
            null
        }.getOrElse { e -> "ERROR: 红外发射失败：${e.message ?: e.javaClass.simpleName}" }
    }
}

class IrTool(private val context: Context, override val name: String) : Tool {
    override val description = when (name) {
        "ir_available" -> "检测手机红外发射器并返回支持的载波频率范围。"
        "ir_send_raw" -> "以指定载波发送 on/off 微秒时长数组。会真实发射红外信号。"
        else -> "发送 Pronto Hex 0000 原始调制码，可用于电视、空调等红外遥控设备。会真实发射。"
    }
    override val parameters = when (name) {
        "ir_send_raw" -> schemaOf(
            "carrier_hz" to intParam("载波频率 Hz，例如 38000"),
            "pattern" to buildJsonObject {
                put("type", "array")
                put("description", "交替 on/off 的正整数微秒数组，如 [9000,4500,560,560]")
                put("items", buildJsonObject { put("type", "integer") })
            }, required = listOf("carrier_hz", "pattern"))
        "ir_send_pronto" -> schemaOf("hex" to strParam("Pronto Hex 0000 码，4 位十六进制字用空格分隔"), required = listOf("hex"))
        else -> schemaOf()
    }
    override val destructive get() = name != "ir_available"

    override suspend fun execute(args: JsonObject): String {
        val manager = context.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager
        val available = runCatching { manager?.hasIrEmitter() == true }.getOrDefault(false)
        if (name == "ir_available") return buildJsonObject {
            put("available", available)
            putJsonArray("carrierRangesHz") {
                if (available) manager?.carrierFrequencies?.forEach { range ->
                    add(buildJsonObject { put("min", range.minFrequency); put("max", range.maxFrequency) })
                }
            }
            if (!available) put("message", "设备没有可用的红外发射器")
        }.toString()
        if (!available || manager == null) return "ERROR: 设备没有可用的红外发射器"
        return try {
            val signal = when (name) {
                "ir_send_pronto" -> ProntoHex.parse(args.strArg("hex") ?: error("缺少 hex"))
                "ir_send_raw" -> {
                    val hz = args.intArg("carrier_hz") ?: error("carrier_hz 必须是整数")
                    val values = args["pattern"] as? JsonArray ?: error("pattern 必须是 JSON 数组")
                    val pattern = values.map { (it as? JsonPrimitive)?.takeUnless { p -> p.isString }?.intOrNull
                        ?: error("pattern 必须只含整数") }.toIntArray()
                    require(hz in 20_000..60_000) { "载波频率必须在 20–60 kHz" }
                    validatePattern(pattern)
                    IrSignal(hz, pattern)
                }
                else -> error("未知红外工具")
            }
            IrEmitter.send(context, signal)?.let { return it }
            buildJsonObject {
                put("sent", true)
                put("carrierHz", signal.carrierHz)
                put("pulseCount", signal.pattern.size)
            }.toString()
        } catch (e: Exception) {
            "ERROR: 红外发射失败：${e.message ?: e.javaClass.simpleName}"
        }
    }
}
