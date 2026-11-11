package com.dc.andagent.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** R85② 纯函数层测试：GNSS 评级 / 轨迹统计（含 provider 切换与精度过滤）/ PLMN / 信号采样摘要。 */
class GnssCellularToolsTest {

    @Test
    fun `gnss constellation names map all AOSP constants`() {
        assertEquals("GPS", CellularCodec.gnssConstellationName(1))
        assertEquals("SBAS", CellularCodec.gnssConstellationName(2))
        assertEquals("GLONASS", CellularCodec.gnssConstellationName(3))
        assertEquals("QZSS", CellularCodec.gnssConstellationName(4))
        assertEquals("BEIDOU", CellularCodec.gnssConstellationName(5))
        assertEquals("GALILEO", CellularCodec.gnssConstellationName(6))
        assertEquals("IRNSS", CellularCodec.gnssConstellationName(7))
        assertEquals("UNKNOWN", CellularCodec.gnssConstellationName(0))
        assertEquals("UNKNOWN", CellularCodec.gnssConstellationName(99))
    }

    @Test
    fun `gnss signal grade thresholds`() {
        val c = CellularCodec
        assertEquals("good", c.gnssSignalGrade(30.0))
        assertEquals("good", c.gnssSignalGrade(45.0))
        assertEquals("fair", c.gnssSignalGrade(25.0))
        assertEquals("weak", c.gnssSignalGrade(10.0))
    }

    @Test
    fun `gnss overall grade by fix and cn0`() {
        val c = CellularCodec
        assertEquals("none", c.gnssOverall(0, 0, null).grade)
        assertTrue(c.gnssOverall(10, 10, 40.0).hint.isNotEmpty())
    }

    @Test
    fun `gnss overall hints nonempty for zero-used satellites`() {
        val hint = CellularCodec.gnssOverall(8, 0, 25.0).hint
        assertTrue(hint.isNotEmpty())
    }

    @Test
    fun `haversine matches known spherical distances within 1 percent`() {
        // 赤道 1° 经差 = R * π/180 = 111194.93 m
        assertEquals(111_194.93, CellularCodec.haversineMeters(0.0, 0.0, 0.0, 1.0), 1_112.0)
        // 伦敦 → 巴黎大圆距离约 343.5 km（公开值 343~344 km）
        val londonParis = CellularCodec.haversineMeters(51.5074, -0.1278, 48.8566, 2.3522)
        assertEquals(343_500.0, londonParis, 3_435.0)
        // 赤道四分之一圈 = 10007.5 km
        assertEquals(10_007_543.4, CellularCodec.haversineMeters(0.0, 0.0, 0.0, 90.0), 100_075.0)
    }

    @Test
    fun `haversine zero for identical and half circumference for antipodal`() {
        assertEquals(0.0, CellularCodec.haversineMeters(39.9, 116.4, 39.9, 116.4), 0.0001)
        // 对跖点 = π * R ≈ 20015 km
        assertEquals(20_015_114.4, CellularCodec.haversineMeters(0.0, 0.0, 0.0, 180.0), 200_151.0)
    }

    @Test
    fun `track stats accumulate distance and average reported speed`() {
        val pts = listOf(
            CellularCodec.TrackPoint(0.0, 0.0, speedMps = 1f, monoMs = 0L, provider = "gps"),
            CellularCodec.TrackPoint(0.0, 0.001, speedMps = 3f, monoMs = 1000L, provider = "gps"),
            CellularCodec.TrackPoint(0.0, 0.002, speedMps = 5f, monoMs = 2000L, provider = "gps"),
        )
        val s = CellularCodec.trackStats(pts)
        assertEquals(3, s.pointCount)
        assertEquals(222.4, s.distanceMeters, 2.3) // 2 × 111.19 m
        assertEquals(3.0, s.avgSpeedMps!!, 0.001)
        assertEquals(2000L, s.durationMs)
        assertEquals(0, s.providerJumpSegments)
        assertEquals(0, s.accuracyFilteredPoints)
    }

    @Test
    fun `track stats fall back to distance over time when no speed reported`() {
        val pts = listOf(
            CellularCodec.TrackPoint(0.0, 0.0, monoMs = 0L, provider = "gps"),
            CellularCodec.TrackPoint(0.0, 0.001, monoMs = 10_000L, provider = "gps"),
        )
        val s = CellularCodec.trackStats(pts)
        assertEquals(2, s.pointCount)
        assertEquals(11.12, s.avgSpeedMps!!, 0.2) // 111.19 m / 10 s
    }

    @Test
    fun `track stats on empty and single point`() {
        val empty = CellularCodec.trackStats(emptyList())
        assertEquals(0, empty.pointCount)
        assertEquals(0.0, empty.distanceMeters, 0.0)
        assertNull(empty.avgSpeedMps)
        assertEquals(0L, empty.durationMs)

        val one = CellularCodec.trackStats(
            listOf(CellularCodec.TrackPoint(1.0, 2.0, monoMs = 500L)),
        )
        assertEquals(1, one.pointCount)
        assertEquals(0.0, one.distanceMeters, 0.0)
        assertNull(one.avgSpeedMps)
    }

    /* ---------------- R85② review 必修项 3：provider 切换段不计里程 ---------------- */

    @Test
    fun `provider switch segments are excluded from distance and counted`() {
        // gps→network→gps 三段：两段跨源跳过，只有 gps 内部段计入
        val pts = listOf(
            CellularCodec.TrackPoint(0.0, 0.0, monoMs = 0L, provider = "gps"),
            CellularCodec.TrackPoint(0.0, 0.001, monoMs = 1000L, provider = "network"),
            CellularCodec.TrackPoint(0.0, 0.002, monoMs = 2000L, provider = "gps"),
        )
        val s = CellularCodec.trackStats(pts)
        assertEquals(3, s.pointCount)
        assertEquals(0.0, s.distanceMeters, 0.001) // 全部段都被跳过
        assertEquals(2, s.providerJumpSegments)
    }

    @Test
    fun `unknown provider segments still accumulate distance`() {
        // provider=null 视为旧数据/未知：不跳过（保守口径只拦「已知的跨源」）
        val pts = listOf(
            CellularCodec.TrackPoint(0.0, 0.0, monoMs = 0L),
            CellularCodec.TrackPoint(0.0, 0.001, monoMs = 1000L),
        )
        val s = CellularCodec.trackStats(pts)
        assertEquals(111.2, s.distanceMeters, 0.5)
        assertEquals(0, s.providerJumpSegments)
    }

    /* ---------------- R85② review 必修项 2：精度 >100m 点过滤（按点计数，含首末点） ---------------- */

    @Test
    fun `low accuracy points do not contribute to distance`() {
        val pts = listOf(
            CellularCodec.TrackPoint(0.0, 0.0, accuracyM = 10f, monoMs = 0L, provider = "gps"),
            CellularCodec.TrackPoint(0.0, 0.001, accuracyM = 500f, monoMs = 1000L, provider = "gps"),
            CellularCodec.TrackPoint(0.0, 0.002, accuracyM = 20f, monoMs = 2000L, provider = "gps"),
            CellularCodec.TrackPoint(0.0, 0.003, accuracyM = 30f, monoMs = 3000L, provider = "gps"),
        )
        val s = CellularCodec.trackStats(pts)
        // 500m 点两侧的段被跳过；最后一段（20m→30m，都是好点）计入 111.19m
        assertEquals(111.2, s.distanceMeters, 0.5)
        assertEquals(1, s.accuracyFilteredPoints)
        assertEquals(0, s.providerJumpSegments)
    }

    @Test
    fun `first point over accuracy limit is counted and breaks segments`() {
        val pts = listOf(
            CellularCodec.TrackPoint(0.0, 0.0, accuracyM = 200f, monoMs = 0L, provider = "gps"),
            CellularCodec.TrackPoint(0.0, 0.001, accuracyM = 10f, monoMs = 1000L, provider = "gps"),
            CellularCodec.TrackPoint(0.0, 0.002, accuracyM = 15f, monoMs = 2000L, provider = "gps"),
        )
        val s = CellularCodec.trackStats(pts)
        // 首点超限：计数=1，且第一段（200m→10m）被跳过，只有好点间的段计入
        assertEquals(1, s.accuracyFilteredPoints)
        assertEquals(111.2, s.distanceMeters, 0.5)
    }

    @Test
    fun `single low accuracy point is still reported as filtered`() {
        val s = CellularCodec.trackStats(
            listOf(CellularCodec.TrackPoint(1.0, 2.0, accuracyM = 999f, monoMs = 0L)),
        )
        assertEquals(1, s.pointCount)
        assertEquals(1, s.accuracyFilteredPoints)
        assertEquals(0.0, s.distanceMeters, 0.0)
    }

    /* ---------------- R85② review 必修项 4：单调时钟排序 ---------------- */

    @Test
    fun `points are ordered by monotonic clock not insertion order`() {
        // 乱序到达：monoMs 排序后距离才正确（两段各 111.19m）
        val pts = listOf(
            CellularCodec.TrackPoint(0.0, 0.002, monoMs = 2000L, provider = "gps"),
            CellularCodec.TrackPoint(0.0, 0.000, monoMs = 0L, provider = "gps"),
            CellularCodec.TrackPoint(0.0, 0.001, monoMs = 1000L, provider = "gps"),
        )
        val s = CellularCodec.trackStats(pts)
        assertEquals(222.4, s.distanceMeters, 2.3)
        assertEquals(2000L, s.durationMs)
    }

    /* ---------------- R85② review 必修项 1：估算 dbm 不进统计 ---------------- */

    @Test
    fun `signal summary ignores null entries from estimated readings`() {
        // estimated 样本在工具层被置 null 后传入：null/哨兵不计入统计
        val vals: List<Int?> = listOf(-85, null, -90, null, -95)
        val s = CellularCodec.signalSampleSummary(vals)
        assertEquals(3, s.count)
        assertEquals(-95, s.minDbm)
        assertEquals(-85, s.maxDbm)
        assertEquals(-90.0, s.avgDbm!!, 0.01)
    }

    @Test
    fun `signal summary reports min max avg and drop`() {
        val s = CellularCodec.signalSampleSummary(listOf(-70, -80, -100, -95))
        assertEquals(4, s.count)
        assertEquals(-100, s.minDbm)
        assertEquals(-70, s.maxDbm)
        assertEquals(30, s.maxDropDbm)
        assertEquals("degrading", s.trend)
    }

    @Test
    fun `signal summary detects improving and stable trends`() {
        assertEquals("improving", CellularCodec.signalSampleSummary(listOf(-100, -95, -85)).trend)
        assertEquals("stable", CellularCodec.signalSampleSummary(listOf(-88, -90, -87)).trend)
    }

    @Test
    fun `signal summary skips null and sentinel readings`() {
        val s = CellularCodec.signalSampleSummary(listOf(-85, null, Int.MAX_VALUE, -90))
        assertEquals(2, s.count)
    }

    @Test
    fun `signal summary handles empty and single sample`() {
        val empty = CellularCodec.signalSampleSummary(emptyList())
        assertEquals(0, empty.count)
        assertNull(empty.minDbm)
        assertNull(empty.maxDropDbm)
        assertEquals("unknown", empty.trend)
        val one = CellularCodec.signalSampleSummary(listOf(-85))
        assertEquals(1, one.count)
        assertNull(one.maxDropDbm)
        assertEquals("unknown", one.trend)
    }

    /* ---------------- R85 d）PLMN ---------------- */

    @Test
    fun `mcc mnc string concat is null safe`() {
        val c = CellularCodec
        assertEquals("46000", c.mccMncOf("460", "00"))
        assertNull(c.mccMncOf(null, "00"))
        assertNull(c.mccMncOf("460", ""))
        assertNull(c.mccMncOf(" ", "00"))
    }

    @Test
    fun `mcc mnc int concat pads mnc and rejects sentinels`() {
        val c = CellularCodec
        assertEquals("46000", c.mccMncOf(460, 0))
        assertEquals("460123", c.mccMncOf(460, 123))
        assertNull(c.mccMncOf(-1, 0))
        assertNull(c.mccMncOf(460, Int.MAX_VALUE))
    }
}
