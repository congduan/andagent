package com.dc.andagent.cron

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * R105: pure helpers for the `kind=a2ui` cron action. No Android types live in
 * this file, so branch routing and payload validation stay JVM-unit-testable.
 *
 * A `kind=a2ui` task stores an a2ui JSON spec in [CronTask.payload]. When it
 * fires, the worker hands the fence to the live conversation (or, when the
 * device is locked / the app is backgrounded, falls back to a notification so
 * the form is re-rendered once the user returns). Submitting the card still
 * travels the existing a2ui_submit -> composer-fill -> agent path.
 */
enum class CronA2uiAction {
    /** App foreground + unlocked: inject the form into the live conversation. */
    RENDER_FORM,

    /** Locked / background: notify with a summary + tap-to-fill action. */
    NOTIFY_FALLBACK,
}

object CronA2ui {

    /** Pure branch decision, independent of Android runtime state. */
    fun resolve(appForeground: Boolean, deviceLocked: Boolean): CronA2uiAction =
        if (appForeground && !deviceLocked) CronA2uiAction.RENDER_FORM
        else CronA2uiAction.NOTIFY_FALLBACK

    /** Wrap a stored a2ui JSON spec into an assistant-message fenced block. */
    fun toFence(payload: String): String = "```a2ui\n$payload\n```"

    /**
     * Structural validation at authoring time (cron_create). Returns null when
     * the payload is a usable a2ui spec, otherwise a human-readable reason.
     * Prefill values themselves are parsed/ignored leniently by A2uiCard at
     * render time (see prefillOf); here we only reject specs that could never
     * render a card at all.
     */
    fun validateSpec(json: String): String? {
        val root = runCatching { Json.parseToJsonElement(json) }.getOrNull()
            ?: return "a2ui payload 不是合法 JSON"
        if (root !is JsonObject) return "a2ui payload 必须是 JSON 对象"
        val fields = root["fields"] as? JsonArray ?: return "a2ui 需要 fields 数组"
        if (fields.isEmpty()) return "a2ui fields 不能为空"
        if (fields.any { it !is JsonObject }) return "a2ui fields 必须是对象数组"
        return null
    }
}
