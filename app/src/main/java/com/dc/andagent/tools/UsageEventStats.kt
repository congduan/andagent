package com.dc.andagent.tools

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Calendar
import java.util.Locale

/**
 * R117① 屏幕/前台切换事件聚合：纯 JVM object，零 Android 依赖，可全量 JVM 单测。
 * 输入为 (package, timestampMs) 的 resume-only 序列（调用方在遍历 UsageEvents 时已按
 * ACTIVITY_RESUMED 即时过滤，见 SystemApiTools.screenEvents）；recent 输出「最近前台活动历史」。
 *
 * 转场语义：foregroundSwitches 按相邻前台包变化计数——首条 resume 计 1，之后仅当前台包与
 * 前一条不同才 +1，同包连续 resume 不计；sessionEstimate 是「前台包从其它应用切回桌面(launcher)」
 * 的转场数代理（首条即桌面也计 1，视为从未知状态回桌面），并非真实解锁/亮屏计数；
 * 无法解析 launcher 包名时置 null，不虚报。
 */
object UsageEventStats {

    /** UsageEvents.Event.ACTIVITY_RESUMED 为 API 29+ 常量，数值为 1；沿用字面量供 screenEvents 遍历过滤（已对照 AOSP 核实）。 */
    const val ACTIVITY_RESUMED = 1
    const val DEFAULT_RECENT_LIMIT = 50
    const val MAX_RECENT_LIMIT = 200

    private const val DAY_MS = 86_400_000L

    private const val NOTE_BASE =
        "foregroundSwitches/totalSwitches 按相邻前台包变化计数：首条 resume 计 1，之后仅当前台包与前一条不同才 +1，" +
            "同包连续 resume 不重复计 (switches counts adjacent foreground-package changes: the first resume counts 1, " +
            "each later +1 only if the package differs from the previous; same-package re-resumes are not counted). " +
            "sessionEstimate 是「前台包从其它应用切回桌面(launcher)」的转场数代理（首条即桌面也计 1），" +
            "并非真实解锁/亮屏次数 (sessionEstimate is a proxy for 'switching back to the launcher' transitions, " +
            "the first resume being the launcher also counts 1; it is not a real unlock/screen-on count)."
    private const val NOTE_NO_LAUNCHER =
        " 本次未解析到桌面(launcher)包名，sessionEstimate 置 null 不虚报 " +
            "(launcher package unresolved, so sessionEstimate is null rather than fabricated)."

    /** 按默认时区的本地日期分组，格式 yyyy-MM-dd。 */
    private fun dayKey(timestampMs: Long): String {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timestampMs
        return String.format(
            Locale.ROOT, "%04d-%02d-%02d",
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH),
        )
    }

    fun aggregate(
        events: List<Pair<String, Long>>,
        launcherPackage: String?,
        nowMs: Long,
        days: Int,
        recentLimit: Int = DEFAULT_RECENT_LIMIT,
    ): JsonObject {
        val limit = recentLimit.coerceIn(1, MAX_RECENT_LIMIT)
        val begin = nowMs - days.toLong() * DAY_MS
        // 输入已是遍历时即时过滤出的 resume-only 序列；此处仅按时间窗口过滤并升序排序（量级可控，不再二次过滤复制）。
        val ordered = events
            .filter { it.second in begin..nowMs }
            .sortedBy { it.second }

        val daySwitches = mutableMapOf<String, Int>()
        val daySessions = mutableMapOf<String, Int>()
        var totalSwitches = 0
        var totalSessions = 0
        var prevPkg: String? = null
        for (i in ordered.indices) {
            val (pkg, ts) = ordered[i]
            val key = dayKey(ts)
            // foregroundSwitches：首条 resume 计 1，之后仅当前台包与前一条不同才 +1（同包连续 resume 不计）。
            if (i == 0 || pkg != prevPkg) {
                totalSwitches++
                daySwitches[key] = (daySwitches[key] ?: 0) + 1
            }
            // sessionEstimate：前台包从非 launcher 变为 launcher 的转场数（首条即 launcher 也计 1）。
            if (launcherPackage != null && pkg == launcherPackage && (i == 0 || prevPkg != launcherPackage)) {
                totalSessions++
                daySessions[key] = (daySessions[key] ?: 0) + 1
            }
            prevPkg = pkg
        }

        val perDay = buildJsonObject {
            daySwitches.keys.sorted().forEach { key ->
                put(key, buildJsonObject {
                    put("foregroundSwitches", daySwitches.getValue(key))
                    if (launcherPackage == null) put("sessionEstimate", JsonNull)
                    else put("sessionEstimate", daySessions[key] ?: 0)
                })
            }
        }

        // recent：最近前台活动历史（JSON 键仍 recent，字段 package/time 不变），按时间倒序取 limit 条。
        val recent = buildJsonArray {
            ordered.asReversed().take(limit).forEach { (pkg, ts) ->
                add(buildJsonObject { put("package", pkg); put("time", ts) })
            }
        }

        return buildJsonObject {
            put("days", days)
            put("totalSwitches", totalSwitches)
            if (launcherPackage == null) put("totalSessionsEstimate", JsonNull)
            else put("totalSessionsEstimate", totalSessions)
            put("perDay", perDay)
            put("recent", recent)
            put("note", if (launcherPackage == null) NOTE_BASE + NOTE_NO_LAUNCHER else NOTE_BASE)
        }
    }
}
