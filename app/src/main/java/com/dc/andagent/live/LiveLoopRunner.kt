package com.dc.andagent.live

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Process-local live loops. Each tick gets a fresh QuickJS context. */
class LiveLoopRunner(
    private val evaluate: (String, (String) -> Unit) -> String,
    private val deliver: (String, String, JsonElement) -> Unit,
    private val notify: (String) -> Unit,
    private val onStop: (String) -> Unit = {},
) {
    private val scheduler = Executors.newScheduledThreadPool(8)
    private val loops = linkedMapOf<String, Loop>()

    class Loop(
        val id: String, val name: String, val code: String, val intervalMs: Long, val consumer: String,
    ) {
        @Volatile var stopped = false
        @Volatile var paused = false
        @Volatile var failures = 0
        @Volatile var ticks = 0L
        @Volatile var error: String? = null
        @Volatile var firstTickFailed = false
        @Volatile var firstTickError: String? = null
        @Volatile var future: ScheduledFuture<*>? = null
        val results = ArrayDeque<JsonElement>()
        val errorHistory = ArrayDeque<String>()
        fun state(): String = when { stopped -> "stopped"; paused -> "paused"; else -> "running" }
    }

    @Synchronized fun start(name: String, code: String, intervalMs: Long, consumer: String): JsonObject {
        require(code.isNotBlank() && code.length <= 100_000) { "code 长度必须为 1..100000" }
        require(intervalMs in 200..3_600_000) { "interval_ms 必须为 200..3600000" }
        require(consumer in setOf("dialog", "overlay", "kv")) { "consumer 必须为 dialog/overlay/kv" }
        require(loops.values.count { !it.stopped } < 8) { "最多同时运行 8 个 live loop" }
        val loop = Loop(UUID.randomUUID().toString(), name.take(80), code, intervalMs, consumer)
        loops[loop.id] = loop
        while (loops.size > 32) {
            val oldest = loops.entries.firstOrNull { it.value.stopped } ?: break
            loops.remove(oldest.key)
        }
        // Surface syntax/runtime failures in live_start itself. Codex R77
        // must-fix #5: run the first tick off the runner monitor — a legal
        // script calling live_list/live_stop would deadlock on the same lock
        // while start() holds it for the whole first evaluation.
        scheduler.execute { tick(loop) }
        return snapshot(loop)
    }

    @Synchronized fun stop(id: String): JsonObject {
        val loop = loops[id] ?: error("live loop 不存在: $id")
        loop.stopped = true
        loop.future?.cancel(false)
        onStop(id)
        return snapshot(loop)
    }

    @Synchronized fun pause(id: String, paused: Boolean): JsonObject {
        val loop = loops[id] ?: error("live loop 不存在: $id")
        require(!loop.stopped) { "live loop 已停止" }
        loop.paused = paused
        return snapshot(loop)
    }

    @Synchronized fun list(): JsonObject = buildJsonObject {
        put("loops", kotlinx.serialization.json.JsonArray(loops.values.map(::snapshot)))
    }

    private fun schedule(loop: Loop, delay: Long) {
        loop.future = scheduler.schedule({ tick(loop) }, delay, TimeUnit.MILLISECONDS)
    }

    private fun tick(loop: Loop) {
        if (loop.stopped) return
        if (!loop.paused) {
            var submitted = false
            var failure: String? = null
            var callbackError: String? = null
            val response = runCatching { evaluate(loop.code) { raw ->
                if (submitted) { callbackError = "live.result 每 tick 只能调用一次"; error(callbackError!!) }
                if (raw.toByteArray(Charsets.UTF_8).size > 16 * 1024) {
                    callbackError = "live.result 超过 16KB"; error(callbackError!!)
                }
                val value = Json.parseToJsonElement(raw)
                synchronized(loop) {
                    loop.results.addLast(value)
                    while (loop.results.size > 10) loop.results.removeFirst()
                }
                submitted = true
                if (!loop.stopped) deliver(loop.id, loop.consumer, value)
            } }.getOrElse { "ERROR: ${it.message ?: it.javaClass.simpleName}" }
            if (callbackError != null) failure = "ERROR: $callbackError"
            if (response.startsWith("ERROR:")) failure = response
            if (!submitted && failure == null) failure = "tick 未调用 live.result(data)"
            loop.ticks++
            if (failure == null) { loop.failures = 0; loop.error = null }
            else {
                loop.failures++
                loop.error = failure
                if (loop.ticks == 1L) { loop.firstTickFailed = true; loop.firstTickError = failure }
                synchronized(loop) {
                    loop.errorHistory.addFirst("#${loop.ticks} · ${java.time.Instant.now()} · $failure")
                    while (loop.errorHistory.size > 5) loop.errorHistory.removeLast()
                }
                if (loop.failures >= 5) {
                    loop.stopped = true
                    onStop(loop.id)
                    notify("实时循环「${loop.name}」连续 5 次失败，已自动停止：$failure")
                }
            }
        }
        synchronized(loops) { if (!loop.stopped) schedule(loop, loop.intervalMs) }
    }

    private fun snapshot(loop: Loop): JsonObject = buildJsonObject {
        put("id", loop.id); put("name", loop.name); put("state", loop.state())
        put("consumer", loop.consumer); put("interval_ms", loop.intervalMs)
        put("ticks", loop.ticks); put("failures", loop.failures)
        put("firstTickFailed", loop.firstTickFailed)
        loop.firstTickError?.let { put("warning", "首次 tick 失败：$it") }
        loop.error?.let { put("error", it) }
        synchronized(loop) {
            put("results", JsonArray(loop.results.toList()))
            put("errorHistory", JsonArray(loop.errorHistory.map(::JsonPrimitive)))
        }
    }

    @Synchronized fun shutdown() {
        loops.values.forEach { it.stopped = true; it.future?.cancel(false) }
        loops.keys.forEach(onStop)
        scheduler.shutdownNow()
    }
}
