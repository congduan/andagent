package com.dc.andagent.script

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Structured QuickJS error. QuickJS reports its native location inside
 * [stack] as `scriptName:line:col`; keeping the whole stack (not just the
 * message) is what makes live-card errors actionable.
 *
 * Pure Kotlin so the passthrough fields are unit-testable without the native
 * QuickJS library (which is unavailable in JVM tests).
 */
data class ScriptError(
    val message: String,
    val stack: String? = null,
    val scriptName: String? = null,
    val line: Int? = null,
    val col: Int? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("message", message)
        stack?.let { put("stack", it) }
        scriptName?.let { put("scriptName", it) }
        line?.let { put("line", it) }
        col?.let { put("col", it) }
    }

    /** Compact single-line label for toasts: `andagent-script.js:2:5 · ReferenceError: x`. */
    fun display(): String {
        val location = if (scriptName != null && line != null) "$scriptName:$line:${col ?: 1}" else null
        return listOfNotNull(location, message).joinToString(" · ")
    }

    companion object {
        private val LOCATION = Regex("""([A-Za-z0-9_./-]+\.js):(\d+):(\d+)""")

        private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.content

        /** Build from a JS Error's message/stack, extracting the native scriptName:line:col. */
        fun fromJs(message: String?, stack: String?): ScriptError {
            val firstLine = stack?.lineSequence()?.firstOrNull()?.trim()
            val match = stack?.let { LOCATION.find(it) }
            return ScriptError(
                message = message?.takeIf { it.isNotBlank() } ?: firstLine ?: "unknown error",
                stack = stack,
                scriptName = match?.groupValues?.get(1),
                line = match?.groupValues?.get(2)?.toIntOrNull(),
                col = match?.groupValues?.get(3)?.toIntOrNull(),
            )
        }

        /** Build from a Java/Kotlin throwable, preserving its full stack trace. */
        fun fromThrowable(error: Throwable): ScriptError {
            val stack = buildString {
                append(error.javaClass.name).append(": ").append(error.message ?: "")
                error.stackTrace.forEach { append("\n    at ").append(it) }
            }
            return fromJs(error.message, stack)
        }

        /**
         * Normalize an `ERROR: ...` engine result into a structured error.
         * Accepts both the JSON payload produced by evalLive and legacy plain
         * message/stack strings, so older tool results still parse.
         */
        fun fromErrorResult(result: String): ScriptError {
            val raw = result.removePrefix("ERROR:").trim()
            val payload = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()
            if (payload != null && payload.text("message") != null) {
                return ScriptError(
                    message = payload.text("message") ?: raw,
                    stack = payload.text("stack"),
                    scriptName = payload.text("scriptName"),
                    line = payload.text("line")?.toIntOrNull(),
                    col = payload.text("col")?.toIntOrNull(),
                )
            }
            return fromJs(raw, raw)
        }
    }
}
