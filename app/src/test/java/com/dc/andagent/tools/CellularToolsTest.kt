package com.dc.andagent.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * R113: 蜂窝深度刀纯函数校验。
 * 覆盖 NR-ARFCN/LTE EARFCN 频率换算、频段带宽上限表、信号质量分档与综合、
 * NetworkScan 错误码映射，以及流量配额预测。
 */
class CellularToolsTest {

    /* --------------------------- 频率 / 频段换算 --------------------------- */

    @Test
    fun `nr arfcn converts per 3gpp ranges`() {
        assertEquals(2524.95, CellularCodec.nrArfcnToMhz(504_990)!!, 0.001)
        // 633333 → 3000 + 33333*0.015 = 3499.995（n78 常见 3.5GHz）
        assertEquals(3499.995, CellularCodec.nrArfcnToMhz(633_333)!!, 0.001)
        assertEquals(3000.0, CellularCodec.nrArfcnToMhz(600_000)!!, 0.001)
        assertEquals(24_250.08, CellularCodec.nrArfcnToMhz(2_016_667)!!, 0.001)
        assertEquals(99_999.96, CellularCodec.nrArfcnToMhz(3_279_165)!!, 0.01)
        // 非法/超范围返回 null
        assertNull(CellularCodec.nrArfcnToMhz(0))
        assertNull(CellularCodec.nrArfcnToMhz(-5))
        assertNull(CellularCodec.nrArfcnToMhz(3_279_166))
    }

    @Test
    fun `nr frequency range splits fr1 and fr2`() {
        assertEquals("FR1", CellularCodec.frequencyRange(3500.0))
        assertEquals("FR1", CellularCodec.frequencyRange(7124.9))
        assertEquals("FR2", CellularCodec.frequencyRange(7125.0))
        assertEquals("FR2", CellularCodec.frequencyRange(28_000.0))
    }

    @Test
    fun `nr band labels and bandwidth caps`() {
        assertEquals("n78", CellularCodec.nrBandLabel(78))
        assertEquals("n?", CellularCodec.nrBandLabel(0))
        assertEquals(100, CellularCodec.nrBandMaxBandwidthMhz(78))
        assertEquals(100, CellularCodec.nrBandMaxBandwidthMhz(41))
        assertEquals(35, CellularCodec.nrBandMaxBandwidthMhz(3))
        assertEquals(15, CellularCodec.nrBandMaxBandwidthMhz(12))
        assertEquals(400, CellularCodec.nrBandMaxBandwidthMhz(257))
        assertNull(CellularCodec.nrBandMaxBandwidthMhz(9999))
    }

    @Test
    fun `lte earfcn converts with band table`() {
        // band 3: 1805 + 0.1*(1650-1200) = 1850
        assertEquals(1850.0, CellularCodec.lteEarfcnToMhz(1650, 3)!!, 0.001)
        // band 41: 2496 + 0.1*(40620-39650) = 2593
        assertEquals(2593.0, CellularCodec.lteEarfcnToMhz(40_620, 41)!!, 0.001)
        // band 1: 2110 + 0.1*100 = 2120
        assertEquals(2120.0, CellularCodec.lteEarfcnToMhz(100, 1)!!, 0.001)
        // 频段未知 / EARFCN 非法 → null，不猜
        assertNull(CellularCodec.lteEarfcnToMhz(1650, null))
        assertNull(CellularCodec.lteEarfcnToMhz(1650, 99))
        assertNull(CellularCodec.lteEarfcnToMhz(0, 3))
    }

    @Test
    fun `lte bandwidth khz converts to mhz`() {
        assertEquals(20.0, CellularCodec.lteBandWidthMhz(20_000)!!, 0.001)
        assertEquals(1.4, CellularCodec.lteBandWidthMhz(1_400)!!, 0.001)
        assertNull(CellularCodec.lteBandWidthMhz(null))
        assertNull(CellularCodec.lteBandWidthMhz(0))
    }

    /* ------------------------------ 信号质量 ------------------------------ */

    @Test
    fun `rsrp labels follow common thresholds`() {
        assertEquals("excellent", CellularCodec.rsrpLabel(-70))
        assertEquals("excellent", CellularCodec.rsrpLabel(-80))
        assertEquals("good", CellularCodec.rsrpLabel(-81))
        assertEquals("good", CellularCodec.rsrpLabel(-90))
        assertEquals("fair", CellularCodec.rsrpLabel(-91))
        assertEquals("fair", CellularCodec.rsrpLabel(-100))
        assertEquals("poor", CellularCodec.rsrpLabel(-101))
        assertEquals("poor", CellularCodec.rsrpLabel(-110))
        assertEquals("very_poor", CellularCodec.rsrpLabel(-111))
    }

    @Test
    fun `rsrq and sinr labels follow common thresholds`() {
        assertEquals("excellent", CellularCodec.rsrqLabel(-10))
        assertEquals("good", CellularCodec.rsrqLabel(-15))
        assertEquals("fair", CellularCodec.rsrqLabel(-20))
        assertEquals("poor", CellularCodec.rsrqLabel(-21))

        assertEquals("excellent", CellularCodec.sinrLabel(20))
        assertEquals("good", CellularCodec.sinrLabel(13))
        assertEquals("fair", CellularCodec.sinrLabel(0))
        assertEquals("poor", CellularCodec.sinrLabel(-1))
    }

    @Test
    fun `overall label takes the worst available rating`() {
        assertEquals("poor", CellularCodec.overallLabel("excellent", "poor", null))
        assertEquals("fair", CellularCodec.overallLabel("good", "fair"))
        assertEquals("excellent", CellularCodec.overallLabel(null, "excellent"))
        assertNull(CellularCodec.overallLabel(null, null))
    }

    @Test
    fun `level names cover 0 to 4`() {
        assertEquals("none", CellularCodec.levelName(0))
        assertEquals("poor", CellularCodec.levelName(1))
        assertEquals("moderate", CellularCodec.levelName(2))
        assertEquals("good", CellularCodec.levelName(3))
        assertEquals("great", CellularCodec.levelName(4))
        assertNull(CellularCodec.levelName(9))
        assertNull(CellularCodec.levelName(null))
    }

    /* ------------------------------ 枚举映射 ------------------------------ */

    @Test
    fun `network type and access network names`() {
        assertEquals("lte", CellularCodec.networkTypeName(13))
        assertEquals("nr", CellularCodec.networkTypeName(20))
        assertEquals("lte_ca", CellularCodec.networkTypeName(19))
        assertEquals("unknown", CellularCodec.networkTypeName(0))
        assertEquals("unknown", CellularCodec.networkTypeName(77))

        assertEquals("geran", CellularCodec.accessNetworkName(1))
        assertEquals("eutran", CellularCodec.accessNetworkName(3))
        assertEquals("ngran", CellularCodec.accessNetworkName(6))
        assertEquals("unknown", CellularCodec.accessNetworkName(0))
    }

    @Test
    fun `connection status and scan error names`() {
        assertEquals("primary_serving", CellularCodec.connectionStatusName(1))
        assertEquals("secondary_serving", CellularCodec.connectionStatusName(2))
        assertEquals("none", CellularCodec.connectionStatusName(0))
        assertNull(CellularCodec.connectionStatusName(Int.MAX_VALUE))
        assertNull(CellularCodec.connectionStatusName(null))

        assertEquals("success", CellularCodec.scanErrorName(0))
        assertEquals("modem_error", CellularCodec.scanErrorName(1))
        assertEquals("invalid_scan", CellularCodec.scanErrorName(2))
        assertEquals("modem_unavailable", CellularCodec.scanErrorName(3))
        assertEquals("unsupported", CellularCodec.scanErrorName(4))
        assertEquals("interrupted", CellularCodec.scanErrorName(10002))
        assertEquals("unknown_42", CellularCodec.scanErrorName(42))
    }

    /* ------------------------------ 流量配额 ------------------------------ */

    @Test
    fun `quota projection reports remaining and burn-down`() {
        val gb = 1024L * 1024 * 1024
        val q = CellularCodec.quotaProjection(6 * gb, 10 * gb, 30)!!
        assertEquals(60.0, q.percentUsed, 0.01)
        assertEquals(4 * gb, q.remainingBytes)
        assertEquals(6 * gb / 30, q.dailyAvgBytes)
        assertEquals(20.0, q.daysUntilLimit!!, 0.01)
        assertEquals("watch", q.level)
    }

    @Test
    fun `quota projection flags warning and over-limit`() {
        val gb = 1024L * 1024 * 1024
        assertEquals("warning", CellularCodec.quotaProjection(9 * gb, 10 * gb, 30)!!.level)
        val over = CellularCodec.quotaProjection(11 * gb, 10 * gb, 30)!!
        assertEquals("over", over.level)
        assertEquals(0L, over.remainingBytes)
        assertEquals(0.0, over.daysUntilLimit!!, 0.0001)
    }

    @Test
    fun `quota projection needs a positive limit and tolerates zero usage`() {
        assertNull(CellularCodec.quotaProjection(1_000, null, 30))
        assertNull(CellularCodec.quotaProjection(1_000, 0, 30))
        val q = CellularCodec.quotaProjection(0, 1024L * 1024, 0)!!
        assertEquals("ok", q.level)
        assertNull(q.daysUntilLimit) // 零用量不预测耗尽天数
        assertEquals(0L, q.dailyAvgBytes)
    }
}
