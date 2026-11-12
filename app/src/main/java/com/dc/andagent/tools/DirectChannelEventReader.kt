package com.dc.andagent.tools

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * R146 — SensorDirectChannel 共享内存事件解析（纯 JVM，可单测，零 Android framework 依赖）。
 *
 * developer.android.com / AOSP sensors.h 的 direct-channel 事件布局，104 字节/条，全 little-endian：
 *   0x0000 int32  size = 104 (= sizeof(sensors_event_t))
 *   0x0004 int32  reportToken（direct channel 下 sensor 字段被复用为 report token）
 *   0x0008 int32  type（传感器类型）
 *   0x000C uint32 atomic counter（从 1 起，跳过 0，wrap 后也从 1；写入边界判据）
 *   0x0010 int64  timestamp (ns)
 *   0x0018 float[16] data
 *   0x0058 int32[4] reserved = 0
 *
 * 判停规则：不依赖尾部指针。先扫全 buffer 取 atomic counter 最大值 maxC 作为「写入边界」，
 * 再只收 counter 在 [1, maxC] 的槽——即便读到「新写一半」（counter 已推进到 maxC+1、
 * 数据还是旧的）的槽也不会误收。counter==0 视为未写入；size!=104 的坏槽跳过，绝不 crash。
 * 同一 counter（wrap 或硬件重复）可能出现多次，全收、不去重。
 */
object DirectChannelEventReader {
    const val EVENT_SIZE = 104
    private const val OFF_SIZE = 0x00
    private const val OFF_REPORT_TOKEN = 0x04
    private const val OFF_TYPE = 0x08
    private const val OFF_COUNTER = 0x0C
    private const val OFF_TIMESTAMP = 0x10
    private const val OFF_DATA = 0x18
    private const val DATA_FLOATS = 16

    // R146 必修 3：SensorDirectChannel 的 rate 档位值（与 android.hardware.SensorDirectChannel
    // 的 RATE_NORMAL/RATE_FAST/RATE_VERY_FAST 常量一致）。此处用 Int 字面量以保持本对象零
    // Android framework 依赖，可在纯 JVM 单测中直接调用与断言。
    const val RATE_NORMAL = 1
    const val RATE_FAST = 2
    const val RATE_VERY_FAST = 3

    data class Event(
        val tsNs: Long,
        val counter: Long,       // uint32 拓宽为 Long（1..0xFFFFFFFF）
        val reportToken: Int,
        val type: Int,
        val values: FloatArray,  // 16 floats
        val slot: Int,
    )

    /**
     * 写入边界：全 buffer 中有效槽（size==104 且 counter!=0）的最大 counter；无有效槽返回 0。
     * counter 读成无符号（getInt 后 & 0xFFFFFFFFL），与 HAL 的 uint32 语义一致。
     */
    fun maxCounter(buffer: ByteArray, slots: Int = buffer.size / EVENT_SIZE): Long {
        if (slots <= 0) return 0L
        val limit = minOf(slots, buffer.size / EVENT_SIZE)
        if (limit <= 0) return 0L
        val bb = ByteBuffer.wrap(buffer, 0, limit * EVENT_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        var max = 0L
        for (i in 0 until limit) {
            val base = i * EVENT_SIZE
            if (bb.getInt(base + OFF_SIZE) != EVENT_SIZE) continue
            val c = bb.getInt(base + OFF_COUNTER).toLong() and 0xFFFF_FFFFL
            if (c != 0L && c > max) max = c
        }
        return max
    }

    /**
     * 解析共享内存快照。[slots] 为参与扫描的槽位数（超出部分不读），默认按 buffer 大小取整槽。
     * [boundary] 为写入边界，默认就地扫全 buffer 取 [maxCounter]；显式传入可复现「先取边界、
     * 硬件又并发写入」的两阶段读取，用于验证 counter>boundary 的半写/新写槽会被排除。
     * 坏槽（size!=104）、未写入槽（counter==0）、边界外槽（counter>boundary）一律跳过。
     */
    fun parse(
        buffer: ByteArray,
        slots: Int = buffer.size / EVENT_SIZE,
        boundary: Long = maxCounter(buffer, slots),
    ): List<Event> {
        if (slots <= 0 || boundary <= 0L) return emptyList()
        val limit = minOf(slots, buffer.size / EVENT_SIZE)
        if (limit <= 0) return emptyList()
        val bb = ByteBuffer.wrap(buffer, 0, limit * EVENT_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        val out = ArrayList<Event>(limit)
        for (i in 0 until limit) {
            val base = i * EVENT_SIZE
            if (bb.getInt(base + OFF_SIZE) != EVENT_SIZE) continue          // 坏槽 / 未写入
            val c = bb.getInt(base + OFF_COUNTER).toLong() and 0xFFFF_FFFFL
            if (c == 0L) continue                                           // counter 0 = 未写入
            if (c > boundary) continue                                       // 写入边界之外（半写/新写）
            val values = FloatArray(DATA_FLOATS)
            for (j in 0 until DATA_FLOATS) values[j] = bb.getFloat(base + OFF_DATA + j * 4)
            out += Event(
                tsNs = bb.getLong(base + OFF_TIMESTAMP),
                counter = c,
                reportToken = bb.getInt(base + OFF_REPORT_TOKEN),
                type = bb.getInt(base + OFF_TYPE),
                values = values,
                slot = i,
            )
        }
        // R146 必修 2：counter 单调递增即时间序，按 counter 升序稳定排序恢复写入顺序，
        // 避免环形缓冲回绕后仍按物理槽顺序输出导致时间戳倒退/跳变。无 uint32 counter
        // wrap 场景下有效；回绕后以 overwritten/windowMs 如实标注，不虚报。
        return out.sortedBy { it.counter }
    }

    /**
     * R146 二轮必修 A：写指针回绕特征（条件 b）——「确定已覆盖 / 实际截断」判据，纯函数可单测。
     * counter 最大槽的物理位置 < counter 最小槽的物理位置 ⇒ 最新事件落在最旧事件之前，
     * 写指针已回绕并重写了中间槽，旧事件必已被覆盖。该信号纯快照可得、可证伪、是覆盖的充分条件。
     *
     * 充分性前提（AOSP direct-channel 模型）：写指针自槽 0 起、只前进、counter 每次写入 +1，
     * 故未覆盖时 slot=(counter-1) 随 counter 单调递增，不会出现「最大 counter 落在更小槽」的假阳性。
     * 用 Event.slot（物理槽号）而非列表下标，故对已按 counter 排序的 parse 输出同样有效。
     * 注意：恰为 slots 整数倍的全量覆盖会让快照再次呈顺序写入特征而漏判（充分但非必要），
     * 此时由 [detectWrap] 的条件 a) 兜底为 possibleWrap=true、overwritten=false，宁可少报不虚报。
     * 与 [detectWrap] 不同：本函数不受 events.size 影响，全槽写满时仍独立判 b。
     */
    fun hasWrapSignature(events: List<Event>): Boolean {
        if (events.isEmpty()) return false
        val minCounter = events.minOf { it.counter }
        val maxCounter = events.maxOf { it.counter }
        val minSlot = events.filter { it.counter == minCounter }.minOf { it.slot }
        val maxSlot = events.filter { it.counter == maxCounter }.maxOf { it.slot }
        return maxSlot < minSlot
    }

    /**
     * R146 二轮必修 A：判断环形共享内存「可能已回绕」（possibleWrap 语义，可单测纯函数）。
     * 返回保守的「可能覆盖」信号，而非「确定已覆盖」：满足其一即 true（a∨b）——
     *   a) events.size >= slots —— 全槽写满。恰好写满且顺序写入时，仅凭快照无法区分
     *      「写满未覆盖」与「写满且已覆盖一轮」，故保守判为 true（可能覆盖），不谎称已覆盖；
     *   b) [hasWrapSignature] —— 写指针回绕特征（最新事件落在最旧事件之前），确定覆盖。
     * 逻辑-文档-文案三者一致：possibleWrap 只说「可能已回绕 / 若发生覆盖」，不写「已覆盖」。
     * 需要「确定覆盖」判据请直接调用 [hasWrapSignature]，不要用本函数的返回值代替。
     */
    fun detectWrap(events: List<Event>, slots: Int): Boolean {
        if (events.isEmpty()) return false
        if (events.size >= slots) return true
        return hasWrapSignature(events)
    }

    /**
     * R146 必修 3：用户请求的 hz 映射到 direct-channel rate 档位（纯函数，可单测）。
     *   hz <= 110 → RATE_NORMAL（标称 50Hz）
     *   hz <= 440 → RATE_FAST（标称 200Hz）
     *   否则（上限 500 内）→ RATE_VERY_FAST（标称 800Hz）
     * 分界落在相邻档标称 55%~220% 交叠区，档位值见 RATE_* 常量。
     */
    fun rateLevelForHz(hz: Int): Int = when {
        hz <= 110 -> RATE_NORMAL
        hz <= 440 -> RATE_FAST
        else -> RATE_VERY_FAST
    }

    /** 档位标称采样率（Hz）：NORMAL=50 / FAST=200 / VERY_FAST=800。 */
    fun nominalHzForLevel(level: Int): Int = when (level) {
        RATE_NORMAL -> 50
        RATE_FAST -> 200
        RATE_VERY_FAST -> 800
        else -> 200 // 防御：钳制后 level 必在 [RATE_NORMAL, RATE_VERY_FAST]
    }
}
