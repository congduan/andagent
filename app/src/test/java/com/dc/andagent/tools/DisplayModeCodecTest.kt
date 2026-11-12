package com.dc.andagent.tools

import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** R87c: DisplayModeCodec 纯函数回归（modes JSON 化/默认推断/刷新率匹配/HDR 映射/reset）。 */
class DisplayModeCodecTest {

    private fun mode(id: Int, w: Int, h: Int, rate: Float) = DisplayModeSnapshot(id, w, h, rate)

    @Test
    fun `roundRate keeps two decimals`() {
        assertEquals(59.94, DisplayModeCodec.roundRate(59.94f), 1e-9)
        assertEquals(120.0, DisplayModeCodec.roundRate(120.0f), 1e-9)
        assertEquals(90.55, DisplayModeCodec.roundRate(90.546f), 1e-9)
    }

    @Test
    fun `hdr type mapping covers known types and rejects unknown`() {
        assertEquals("DOLBY_VISION", DisplayModeCodec.hdrTypeName(1))
        assertEquals("HDR10", DisplayModeCodec.hdrTypeName(2))
        assertEquals("HLG", DisplayModeCodec.hdrTypeName(3))
        assertEquals("HDR10_PLUS", DisplayModeCodec.hdrTypeName(4))
        assertNull(DisplayModeCodec.hdrTypeName(0))
        assertNull(DisplayModeCodec.hdrTypeName(99))
        assertEquals(listOf("HDR10", "HLG"), DisplayModeCodec.hdrTypeNames(intArrayOf(2, 3, 99)))
    }

    @Test
    fun `default mode prefers highest resolution then refresh rate`() {
        val modes = listOf(
            mode(1, 1080, 2400, 60f),
            mode(2, 1080, 2400, 120f),
            mode(3, 720, 1600, 90f),
        )
        assertEquals(2, DisplayModeCodec.defaultModeId(modes))
        // 空列表安全返回 null。
        assertNull(DisplayModeCodec.defaultModeId(emptyList()))
    }

    @Test
    fun `peak refresh rate is max across modes`() {
        val modes = listOf(mode(1, 1080, 2400, 59.94f), mode(2, 1080, 2400, 120f))
        assertEquals(120.0, DisplayModeCodec.peakRefreshRate(modes)!!, 1e-9)
        assertNull(DisplayModeCodec.peakRefreshRate(emptyList()))
    }

    @Test
    fun `supported rate labels dedupe and sort ascending`() {
        val modes = listOf(
            mode(1, 1080, 2400, 120f),
            mode(2, 1080, 2400, 60f),
            mode(3, 720, 1600, 60.003f),
            mode(4, 1080, 2400, 90f),
        )
        assertEquals(listOf("60.00", "90.00", "120.00"), DisplayModeCodec.supportedRateLabels(modes))
    }

    @Test
    fun `modesToJson marks default and current`() {
        val modes = listOf(mode(1, 1080, 2400, 60f), mode(2, 1080, 2400, 120f))
        val json = DisplayModeCodec.modesToJson(modes, currentModeId = 2, defaultModeId = 2)
        assertEquals(2, json.size)
        val first = json[0].jsonObject
        val second = json[1].jsonObject
        assertEquals(1, first["modeId"]!!.jsonPrimitive.int)
        assertFalse(first["isDefault"]!!.jsonPrimitive.boolean)
        assertFalse(first["isCurrent"]!!.jsonPrimitive.boolean)
        assertEquals(120, second["roundedRefreshRate"]!!.jsonPrimitive.int)
        assertTrue(second["isDefault"]!!.jsonPrimitive.boolean)
        assertTrue(second["isCurrent"]!!.jsonPrimitive.boolean)
        assertEquals(120.0, second["refreshRate"]!!.jsonPrimitive.double, 1e-9)
    }

    @Test
    fun `modesToJson yields null isDefault when default mode unknown`() {
        // review 必修3：工具层传 defaultModeId=null（公开 API 无系统默认模式），
        // isDefault 须输出 JSON null（未知）而不是 false（已核实非默认）。
        val modes = listOf(mode(1, 1080, 2400, 60f))
        val json = DisplayModeCodec.modesToJson(modes, currentModeId = 1, defaultModeId = null)
        assertTrue(json[0].jsonObject["isDefault"] is kotlinx.serialization.json.JsonNull)
        assertTrue(json[0].jsonObject["isCurrent"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `selectModeForRefreshRate hits exact rate at current resolution`() {
        val modes = listOf(
            mode(1, 1080, 2400, 60f),
            mode(2, 1080, 2400, 120f),
            mode(3, 720, 1600, 120f),
        )
        val sel = DisplayModeCodec.selectModeForRefreshRate(modes, 120f, 1080, 2400)
        val found = sel as RefreshRateSelection.Found
        assertEquals(2, found.modeId)
        assertEquals(120.0, DisplayModeCodec.roundRate(found.refreshRate), 1e-9)
    }

    @Test
    fun `selectModeForRefreshRate picks closest within epsilon`() {
        val modes = listOf(mode(1, 1080, 2400, 59.94f), mode(2, 1080, 2400, 120f))
        val sel = DisplayModeCodec.selectModeForRefreshRate(modes, 60f, 1080, 2400)
        assertEquals(1, (sel as RefreshRateSelection.Found).modeId)
    }

    @Test
    fun `selectModeForRefreshRate misses unknown rate and lists supported values`() {
        val modes = listOf(mode(1, 1080, 2400, 60f), mode(2, 1080, 2400, 120f))
        val sel = DisplayModeCodec.selectModeForRefreshRate(modes, 90f, 1080, 2400)
        val nf = sel as RefreshRateSelection.NotFound
        assertEquals(listOf("60.00", "120.00"), nf.supportedRates)
    }

    @Test
    fun `selectModeForRefreshRate does not switch resolution`() {
        // 90Hz 只存在于别的分辨率 → 当前分辨率下算未命中。
        val modes = listOf(
            mode(1, 1080, 2400, 60f),
            mode(2, 720, 1600, 90f),
        )
        val sel = DisplayModeCodec.selectModeForRefreshRate(modes, 90f, 1080, 2400)
        assertTrue(sel is RefreshRateSelection.NotFound)
    }

    @Test
    fun `reset semantics return zero`() {
        assertEquals(0, DisplayModeCodec.resetModeId())
    }

    @Test
    fun `rateLabel formats with two decimals`() {
        assertEquals("59.94", DisplayModeCodec.rateLabel(59.94f))
        assertEquals("120.00", DisplayModeCodec.rateLabel(120f))
    }
}
