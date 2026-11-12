package com.dc.andagent.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/** R117① 使用习惯统计聚合（UsageEventStats）纯 JVM 单测；输入为 (pkg, ts) 的 resume-only 序列。 */
class UsageEventStatsTest {

    // 固定为本地时间 2026-11-13 12:00（默认时区），跨「本地日」断言不受运行时日期影响。
    private val nowMs: Long = run {
        val cal = Calendar.getInstance()
        cal.set(2026, Calendar.NOVEMBER, 13, 12, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.timeInMillis
    }

    private val launcher = "com.android.launcher"

    private fun resumed(pkg: String, ts: Long): Pair<String, Long> = pkg to ts

    private fun perDay(result: JsonObject): JsonObject = result["perDay"]!!.jsonObject
    private fun switchesOf(day: JsonElement): Int =
        day.jsonObject["foregroundSwitches"]!!.jsonPrimitive.content.toInt()
    private fun sessionsOf(day: JsonElement): Int =
        day.jsonObject["sessionEstimate"]!!.jsonPrimitive.content.toInt()
    private fun recent(result: JsonObject): JsonArray = result["recent"]!!.jsonArray
    private fun totalSwitches(result: JsonObject): Int =
        result["totalSwitches"]!!.jsonPrimitive.content.toInt()
    private fun totalSessions(result: JsonObject): Int =
        result["totalSessionsEstimate"]!!.jsonPrimitive.content.toInt()

    @Test fun perDayGroupsEventsAcrossTwoLocalDays() {
        val events = listOf(
            resumed("com.a", nowMs),                    // 今天 12:00
            resumed("com.b", nowMs - 3_600_000L),      // 今天 11:00
            resumed("com.c", nowMs - 7_200_000L),      // 今天 10:00
            resumed("com.d", nowMs - 86_400_000L),     // 昨天 12:00
            resumed("com.e", nowMs - 93_600_000L),     // 昨天 10:00
        )
        val r = UsageEventStats.aggregate(events, null, nowMs, 2)
        val days = perDay(r)
        assertEquals(2, days.size)
        assertEquals(setOf(3, 2), days.values.map { switchesOf(it) }.toSet())
        assertEquals(5, totalSwitches(r))
    }

    @Test fun samePackageConsecutiveResumeCountsOnlyOneSwitch() {
        // 必修 3：同包连续 resume 不重复计切换，整段只计 1。
        val events = listOf(
            resumed("com.a", nowMs),
            resumed("com.a", nowMs - 3_600_000L),
            resumed("com.a", nowMs - 7_200_000L),
        )
        val r = UsageEventStats.aggregate(events, launcher, nowMs, 1)
        assertEquals(1, totalSwitches(r))
        assertEquals(1, switchesOf(perDay(r).values.single()))
    }

    @Test fun repeatedLauncherResumeDoesNotInflateSessionEstimate() {
        // 必修 3：桌面自身重复 resume 不虚增 session（首条即桌面计 1 后不再累加）。
        val events = listOf(
            resumed(launcher, nowMs),
            resumed(launcher, nowMs - 3_600_000L),
            resumed(launcher, nowMs - 7_200_000L),
        )
        val r = UsageEventStats.aggregate(events, launcher, nowMs, 1)
        assertEquals(1, totalSessions(r))
        assertEquals(1, totalSwitches(r))
    }

    @Test fun nonLauncherToLauncherTransitionCountsOneSession() {
        // 必修 3：前台包从非 launcher 变为 launcher 的转场计 1。
        val events = listOf(
            resumed(launcher, nowMs),                  // 较新：切回桌面
            resumed("com.a", nowMs - 3_600_000L),     // 较早：使用其它应用
        )
        val r = UsageEventStats.aggregate(events, launcher, nowMs, 1)
        assertEquals(1, totalSessions(r))
        assertEquals(2, totalSwitches(r))
    }

    @Test fun firstResumeBeingLauncherCountsOneSession() {
        // 必修 3：窗口内首条 resume 即 launcher，视为「从未知状态回桌面」计 1。
        val events = listOf(
            resumed("com.b", nowMs),
            resumed("com.a", nowMs - 3_600_000L),
            resumed(launcher, nowMs - 7_200_000L),    // 最早：首条即 launcher
        )
        val r = UsageEventStats.aggregate(events, launcher, nowMs, 1)
        assertEquals(1, totalSessions(r))
    }

    @Test fun launcherResumedCountsAsSessionEstimate() {
        val events = listOf(
            resumed("com.a", nowMs),
            resumed(launcher, nowMs - 3_600_000L),
            resumed("com.b", nowMs - 7_200_000L),
            resumed(launcher, nowMs - 10_800_000L),
        )
        val r = UsageEventStats.aggregate(events, launcher, nowMs, 1)
        assertEquals(4, totalSwitches(r))
        assertEquals(2, totalSessions(r))
        assertEquals(2, sessionsOf(perDay(r).values.single()))
    }

    @Test fun nullLauncherYieldsNullSessionEstimateAndNote() {
        val events = listOf(resumed("com.a", nowMs), resumed(launcher, nowMs - 3_600_000L))
        val r = UsageEventStats.aggregate(events, null, nowMs, 1)
        assertEquals(JsonNull, r["totalSessionsEstimate"])
        assertEquals(JsonNull, perDay(r).values.single().jsonObject["sessionEstimate"])
        val note = r["note"]!!.jsonPrimitive.content
        assertTrue(note.contains("sessionEstimate"))
        assertTrue(note.contains("unlock"))
        assertTrue(note.contains("null"))
    }

    @Test fun recentIsDescendingAndLimited() {
        val events = (0 until 5).map { i -> resumed("com.a$i", nowMs - i * 3_600_000L) }
        val r = UsageEventStats.aggregate(events, launcher, nowMs, 1, recentLimit = 3)
        val times = recent(r).map { it.jsonObject["time"]!!.jsonPrimitive.content.toLong() }
        assertEquals(3, times.size)
        assertEquals(listOf(nowMs, nowMs - 3_600_000L, nowMs - 7_200_000L), times)
        assertEquals("com.a0", recent(r)[0].jsonObject["package"]!!.jsonPrimitive.content)
    }

    @Test fun daysRangeFiltersOldEvents() {
        val events = listOf(
            resumed("com.a", nowMs),                          // 今天，窗口内
            resumed("com.b", nowMs - 86_400_000L),           // 昨天，窗口内（days=2）
            resumed("com.old", nowMs - 5 * 86_400_000L),     // 5 天前，范围外
        )
        val r = UsageEventStats.aggregate(events, launcher, nowMs, 2)
        assertEquals(2, totalSwitches(r))
        assertEquals(2, recent(r).size)
        assertEquals(2, perDay(r).size)
        assertTrue(recent(r).none { it.jsonObject["package"]!!.jsonPrimitive.content == "com.old" })
    }

    @Test fun emptyInputReturnsZeroTotals() {
        val r = UsageEventStats.aggregate(emptyList(), launcher, nowMs, 1)
        assertEquals(0, totalSwitches(r))
        assertEquals(0, totalSessions(r))
        assertEquals(0, perDay(r).size)
        assertEquals(0, recent(r).size)
        assertTrue(r["note"]!!.jsonPrimitive.content.contains("sessionEstimate"))
    }

    @Test fun crossDayTotalsSumPerDay() {
        val events = listOf(
            resumed("com.a", nowMs),
            resumed(launcher, nowMs - 3_600_000L),
            resumed("com.b", nowMs - 7_200_000L),
            resumed("com.c", nowMs - 86_400_000L),
            resumed(launcher, nowMs - 93_600_000L),
        )
        val r = UsageEventStats.aggregate(events, launcher, nowMs, 2)
        val days = perDay(r)
        assertEquals(2, days.size)
        assertEquals(days.values.sumOf { switchesOf(it) }, totalSwitches(r))
        assertEquals(days.values.sumOf { sessionsOf(it) }, totalSessions(r))
    }

    @Test fun defaultRecentLimitIsFifty() {
        val events = (0 until 60).map { i -> resumed("com.a", nowMs - i * 60_000L) }
        val r = UsageEventStats.aggregate(events, launcher, nowMs, 1)
        assertEquals(50, recent(r).size)
    }

    @Test fun recentLimitCappedAtTwoHundred() {
        val events = (0 until 250).map { i -> resumed("com.a", nowMs - i * 1_000L) }
        val r = UsageEventStats.aggregate(events, launcher, nowMs, 1, recentLimit = 999)
        assertEquals(200, recent(r).size)
    }
}
