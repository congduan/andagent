package com.dc.andagent.cron

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** R105: pure JVM coverage for the cron a2ui branch routing and payload spec. */
class CronA2uiTest {

    /* ---- cron a2ui 分支路由：前台且解锁才渲染表单，否则通知降级 ---- */
    @Test fun foregroundUnlockedRendersForm() =
        assertEquals(CronA2uiAction.RENDER_FORM, CronA2ui.resolve(appForeground = true, deviceLocked = false))

    @Test fun foregroundButLockedFallsBack() =
        assertEquals(CronA2uiAction.NOTIFY_FALLBACK, CronA2ui.resolve(appForeground = true, deviceLocked = true))

    @Test fun backgroundUnlockedFallsBack() =
        assertEquals(CronA2uiAction.NOTIFY_FALLBACK, CronA2ui.resolve(appForeground = false, deviceLocked = false))

    @Test fun backgroundAndLockedFallsBack() =
        assertEquals(CronA2uiAction.NOTIFY_FALLBACK, CronA2ui.resolve(appForeground = false, deviceLocked = true))

    /* ---- toFence：把存储的 a2ui JSON spec 包成 assistant 消息围栏 ---- */
    @Test fun toFenceWrapsSpec() {
        val spec = "{\"title\":\"晨报\",\"fields\":[{\"type\":\"SWITCH\",\"key\":\"todo\"}]}"
        assertEquals("```a2ui\n$spec\n```", CronA2ui.toFence(spec))
    }

    /* ---- validateSpec：cron_create 处的结构校验 ---- */
    @Test fun validSpecPasses() {
        val spec = """{"title":"晨报","fields":[{"type":"TEXT_INPUT","key":"weather","prefill":"晴"},{"type":"SWITCH","key":"todo","prefill":true},{"type":"NUMBER_SLIDER","key":"focus","min":0,"max":60,"prefill":30}]}"""
        assertNull(CronA2ui.validateSpec(spec))
    }

    @Test fun invalidJsonRejected() = assertTrue(CronA2ui.validateSpec("{not json") != null)
    @Test fun nonObjectRejected() = assertTrue(CronA2ui.validateSpec("[]") != null)
    @Test fun missingFieldsRejected() = assertTrue(CronA2ui.validateSpec("""{"title":"x"}""") != null)
    @Test fun emptyFieldsRejected() = assertTrue(CronA2ui.validateSpec("""{"fields":[]}""") != null)
    @Test fun nonObjectFieldRejected() = assertTrue(CronA2ui.validateSpec("""{"fields":[1,2]}""") != null)

    @Test fun prefillSpecsWithMatchedTypesPass() {
        // prefill 的类型解析/渲染由前端 prefillOf 落地（见 A2uiCard.test.tsx）；
        // 这里确认带各类合法 prefill 的 spec 在 cron_create 结构校验中通过、
        // 不会因 prefill 字段被误拒。
        val spec = """{"fields":[{"type":"TEXT_INPUT","key":"a","prefill":"s"},{"type":"DATE_PICK","key":"b","prefill":"2026-11-08"},{"type":"CHOICE","key":"c","options":["x","y"],"prefill":"x"},{"type":"CHOICE","key":"d","multiple":true,"options":["x"],"prefill":["x"]},{"type":"NUMBER_SLIDER","key":"e","prefill":5},{"type":"SWITCH","key":"f","prefill":false}]}"""
        assertNull(CronA2ui.validateSpec(spec))
    }
}
