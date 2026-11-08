package com.dc.andagent.tools

import java.util.Locale
import kotlin.math.roundToInt

/**
 * R113 内置红外码表的协议编码器。
 *
 * 码表只存「协议 + 设备号 + 功能值」这类参数（与 irdb 的 CSV 口径一致），
 * 这里把参数编译成 ConsumerIrManager 可直接发射的 on/off 微秒数组，
 * 也能反向导出 Pronto Hex 0000，交给 `ir_send_pronto` 或其它设备使用。
 *
 * 支持：NEC1/NEC2、NECx1/NECx2（16 位地址，三星电视即此族）、Sony12/15/20、RC5。
 * 载波取各协议惯例值（NEC 38 kHz / Sony 40 kHz / RC5 36 kHz），码表条目可覆盖。
 */
internal object IrProtocols {
    val SUPPORTED = setOf("nec1", "nec2", "necx1", "necx2", "sony12", "sony15", "sony20", "rc5")

    /** 各协议惯例载波频率（Hz）。 */
    fun carrierHz(protocol: String): Int = when (normalize(protocol)) {
        "sony12", "sony15", "sony20" -> 40_000
        "rc5" -> 36_000
        else -> 38_000
    }

    /** 重复帧数：Sony 遥控器惯例连发 3 帧，其余 2 帧。 */
    fun defaultFrames(protocol: String): Int = when (normalize(protocol)) {
        "sony12", "sony15", "sony20" -> 3
        else -> 2
    }

    fun isSupported(protocol: String): Boolean = normalize(protocol) in SUPPORTED

    /**
     * 把协议参数编译成红外信号。
     *
     * @param device    设备号（NEC 8 位 / NECx 低字节 / Sony 设备号 / RC5 system）
     * @param subdevice 子设备号（NECx 高字节 / Sony20 扩展字节 / 未用填 -1）
     * @param function  功能值（0..255）
     * @param frames    连发帧数，0 表示按协议默认值
     */
    fun encode(protocol: String, device: Int, subdevice: Int, function: Int, frames: Int = 0): IrSignal {
        val proto = normalize(protocol)
        require(proto in SUPPORTED) { "不支持的协议：$protocol（支持 ${SUPPORTED.joinToString("/")}）" }
        require(function in 0..0xFF) { "功能值必须在 0..255" }
        val repeat = if (frames > 0) frames else defaultFrames(proto)
        require(repeat in 1..5) { "连发帧数只能在 1..5" }
        val carrier = carrierHz(proto)
        val pattern = when (proto) {
            "nec1", "nec2" -> necPattern(device, extended = false, subdevice, function, repeat)
            "necx1", "necx2" -> necPattern(device, extended = true, subdevice, function, repeat)
            "sony12" -> sonyPattern(function, device, subdevice, deviceBits = 5, extended = false, repeat)
            "sony15" -> sonyPattern(function, device, subdevice, deviceBits = 8, extended = false, repeat)
            "sony20" -> sonyPattern(function, device, subdevice, deviceBits = 5, extended = true, repeat)
            else -> rc5Pattern(device, function, repeat)
        }
        validatePattern(pattern)
        return IrSignal(carrier, pattern)
    }

    private fun normalize(protocol: String): String = protocol.trim().lowercase(Locale.US)

    /**
     * NEC 帧：9 ms/4.5 ms 引导 + 32 个位（位 0 = 560/560，位 1 = 560/1690）+ 560 停止位。
     * 标准 NEC 的 32 位是 地址/反码地址/命令/反码命令；NECx 换成 16 位地址 + 命令 + 反码命令。
     */
    private fun necPattern(device: Int, extended: Boolean, subdevice: Int, function: Int, frames: Int): IntArray {
        val bits: IntArray
        if (extended) {
            require(device in 0..0xFF && subdevice in 0..0xFF) {
                "NECx 需要 device 与 subdevice 都在 0..255（低字节 + 高字节）"
            }
            val address = (subdevice shl 8) or device
            bits = IntArray(32)
            var i = 0
            for (b in 0 until 16) bits[i++] = (address shr b) and 1
            for (b in 0 until 8) bits[i++] = (function shr b) and 1
            for (b in 0 until 8) bits[i++] = ((function.inv()) shr b) and 1
        } else {
            require(device in 0..0xFF) { "NEC 的 device 必须在 0..255" }
            bits = IntArray(32)
            var i = 0
            for (b in 0 until 8) bits[i++] = (device shr b) and 1
            for (b in 0 until 8) bits[i++] = ((device.inv()) shr b) and 1
            for (b in 0 until 8) bits[i++] = (function shr b) and 1
            for (b in 0 until 8) bits[i++] = ((function.inv()) shr b) and 1
        }
        val out = IrPatternBuilder()
        repeat(frames) {
            out.mark(9000)
            out.space(4500)
            for (bit in bits) {
                out.mark(560)
                out.space(if (bit == 1) 1690 else 560)
            }
            out.mark(560)
            out.space(NEC_FRAME_PERIOD)
        }
        return out.build()
    }

    /**
     * Sony SIRC：2.4 ms/600 µs 引导，位 1 = 1200/600，位 0 = 600/600，
     * 位序为 7 位命令（LSB）→ 设备号 →（Sony20 再追加 8 位扩展），每帧 45 ms 周期。
     */
    private fun sonyPattern(
        function: Int,
        device: Int,
        subdevice: Int,
        deviceBits: Int,
        extended: Boolean,
        frames: Int,
    ): IntArray {
        require(function in 0..0x7F) { "Sony 命令值必须在 0..127" }
        require(device in 0 until (1 shl deviceBits)) { "Sony 设备号必须在 0..${(1 shl deviceBits) - 1}" }
        val bits = ArrayList<Int>(20)
        for (b in 0 until 7) bits += (function shr b) and 1
        for (b in 0 until deviceBits) bits += (device shr b) and 1
        if (extended) {
            val ext = if (subdevice < 0) 0 else subdevice
            require(ext in 0..0xFF) { "Sony20 扩展字节（subdevice）必须在 0..255" }
            for (b in 0 until 8) bits += (ext shr b) and 1
        }
        val out = IrPatternBuilder()
        repeat(frames) {
            out.mark(2400)
            out.space(600)
            for (bit in bits) {
                out.mark(if (bit == 1) 1200 else 600)
                out.space(600)
            }
            out.space(SONY_FRAME_GAP)
        }
        return out.build()
    }

    /**
     * RC5：36 kHz、889 µs 半位曼彻斯特编码。
     * 帧结构 = 起始位 1 + field 位 + toggle 位 + 5 位系统号 + 6 位命令（系统/命令高位在前）。
     * 位 1 先空后满，位 0 先满后空；首个半位是空，按发射惯例裁掉。
     */
    private fun rc5Pattern(system: Int, command: Int, frames: Int): IntArray {
        require(system in 0..31) { "RC5 系统号必须在 0..31" }
        require(command in 0..0x7F) { "RC5 命令值必须在 0..127" }
        val halves = ArrayList<Int>() // 1 = 有载波, 0 = 无载波
        fun bit(v: Int) {
            if (v == 1) {
                halves += 0; halves += 1
            } else {
                halves += 1; halves += 0
            }
        }
        bit(1)
        bit(if (command and 0x40 == 0) 1 else 0)
        bit(0) // toggle：单帧内固定为 0，重复键由 frames 连发承担
        for (b in 4 downTo 0) bit((system shr b) and 1)
        for (b in 5 downTo 0) bit((command shr b) and 1)

        val out = IrPatternBuilder()
        repeat(frames) {
            var i = 0
            // 首半位是空（起始位的低电平），按真实遥控器波形裁掉。
            if (halves[0] == 0) i = 1
            while (i < halves.size) {
                if (halves[i] == 1) out.mark(RC5_HALF_BIT) else out.space(RC5_HALF_BIT)
                i++
            }
            out.space(RC5_FRAME_GAP)
        }
        return out.build()
    }

    private const val NEC_FRAME_PERIOD = 40_000
    private const val SONY_FRAME_GAP = 25_000
    private const val RC5_HALF_BIT = 889
    private const val RC5_FRAME_GAP = 114_000
}

/**
 * 交替 on/off 微秒序列的增量构造器：相邻同电平自动合并（例如「停止位后接帧间隔」），
 * 首个电平若是 off 直接丢弃（ConsumerIrManager 要求 pattern 以 on 开头）。
 */
internal class IrPatternBuilder {
    private val durations = ArrayList<Int>()

    fun mark(micros: Int) = add(true, micros)

    fun space(micros: Int) = add(false, micros)

    private fun add(on: Boolean, micros: Int) {
        require(micros > 0) { "红外脉冲时长必须大于 0" }
        if (durations.isEmpty()) {
            if (on) durations += micros
            return
        }
        val lastIsOn = durations.size % 2 == 1
        if (lastIsOn == on) durations[durations.size - 1] = durations.last() + micros else durations += micros
    }

    fun build(): IntArray {
        if (durations.size % 2 == 1) durations += 1
        return durations.toIntArray()
    }
}

/** Pronto Hex 0000 导出（载波分频字 + 引导段脉冲对，重复段留空）。 */
internal fun IrSignal.toProntoHex(): String {
    require(pattern.isNotEmpty() && pattern.size % 2 == 0) { "Pronto 导出需要偶数的 on/off 序列" }
    val word = (1_000_000.0 / (carrierHz * PRONTO_CLOCK_MICROS)).roundToInt().coerceIn(1, 0xFFFF)
    val clock = word * PRONTO_CLOCK_MICROS
    val counts = pattern.map { (it / clock).roundToInt().coerceIn(1, 0xFFFF) }
    val builder = StringBuilder("0000 ")
    builder.append(hex4(word)).append(" 0000 ").append(hex4(counts.size / 2))
    for (count in counts) builder.append(' ').append(hex4(count))
    return builder.toString()
}

private const val PRONTO_CLOCK_MICROS = 0.241246

private fun hex4(value: Int): String = String.format(Locale.US, "%04X", value)
