package com.dc.andagent.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.roundToLong

/**
 * Blocking wait between agent steps. Capped at 60s so a wait always fits the
 * WebView's default 65s tool timeout and never hogs the 2-thread tool pool for
 * minutes at a time.
 */
class WaitTool : Tool {
    override val name = "wait"
    override val description = "阻塞等待指定时长后再继续，用于给页面加载、动画、设备状态变化留出间隔。用 seconds（秒，可小数）或 duration_ms（毫秒）指定时长，二选一，最长 60 秒；返回实际等待的毫秒数。"
    override val parameters = schemaOf(
        "seconds" to numParam("等待秒数，支持小数，例如 0.5 / 2，范围 0.001~60；与 duration_ms 二选一"),
        "duration_ms" to intParam("等待毫秒数，范围 1~60000；同时给出时以本参数为准"),
    )

    override suspend fun execute(args: JsonObject): String {
        val requestedMs = when {
            args["duration_ms"] != null -> {
                val ms = args.longArg("duration_ms") ?: return "ERROR: duration_ms 必须是 1~60000 的整数"
                if (ms !in MIN_MS..MAX_MS) return "ERROR: duration_ms 必须在 1~60000 之间"
                ms
            }
            args["seconds"] != null -> {
                val seconds = args.strArg("seconds")?.toDoubleOrNull()
                    ?.takeIf { !it.isNaN() && !it.isInfinite() }
                    ?: return "ERROR: seconds 必须是 0.001~60 的数字"
                val ms = (seconds * 1000.0).roundToLong()
                if (ms !in MIN_MS..MAX_MS) return "ERROR: seconds 必须在 0.001~60 之间（最长 60 秒）"
                ms
            }
            else -> return "ERROR: 请用 seconds 或 duration_ms 指定等待时长（最长 60 秒）"
        }
        val startedAt = System.nanoTime()
        // Thread.sleep rather than coroutine delay: AgentBridge cancels tools with
        // future.cancel(true), and the resulting InterruptedException is mapped to
        // 「已被用户中断」. A plain delay would keep running until the full duration.
        Thread.sleep(requestedMs)
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
        return buildJsonObject {
            put("waited_ms", requestedMs)
            put("elapsed_ms", elapsedMs)
        }.toString()
    }

    companion object {
        const val MIN_MS = 1L
        const val MAX_MS = 60_000L
    }
}
