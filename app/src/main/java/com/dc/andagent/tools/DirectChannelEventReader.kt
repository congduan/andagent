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
        return out
    }
}
