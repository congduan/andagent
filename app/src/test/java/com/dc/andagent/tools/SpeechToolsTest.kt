package com.dc.andagent.tools

import android.speech.SpeechRecognizer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pure-function tests for speech_recognize. Live recognition (callbacks,
 * timeout and recognizer destruction on a real service) needs a device with a
 * Google/ROM speech service and is recorded as untested in the round report.
 */
@RunWith(RobolectricTestRunner::class)
class SpeechToolsTest {

    @Test fun timeoutFallsBackToDefaultAndClampsToRange() {
        assertEquals(15, normalizeSpeechTimeout(null))
        assertEquals(5, normalizeSpeechTimeout(Int.MIN_VALUE))
        assertEquals(5, normalizeSpeechTimeout(0))
        assertEquals(5, normalizeSpeechTimeout(4))
        assertEquals(5, normalizeSpeechTimeout(5))
        assertEquals(30, normalizeSpeechTimeout(30))
        assertEquals(60, normalizeSpeechTimeout(60))
        assertEquals(60, normalizeSpeechTimeout(61))
        assertEquals(60, normalizeSpeechTimeout(Int.MAX_VALUE))
    }

    @Test fun languageDefaultsToZhCnAndKeepsExplicitTags() {
        assertEquals("zh-CN", normalizeSpeechLanguage(null))
        assertEquals("zh-CN", normalizeSpeechLanguage(""))
        assertEquals("zh-CN", normalizeSpeechLanguage("   "))
        assertEquals("en-US", normalizeSpeechLanguage("en-US"))
        assertEquals("zh-CN", normalizeSpeechLanguage("  zh-CN  "))
        assertEquals("ja-JP", normalizeSpeechLanguage("ja-JP"))
    }

    @Test fun errorMessagesCoverEveryDocumentedCode() {
        val expected = mapOf(
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT to "网络超时",
            SpeechRecognizer.ERROR_NETWORK to "网络错误",
            SpeechRecognizer.ERROR_AUDIO to "录音失败",
            SpeechRecognizer.ERROR_SERVER to "识别服务错误",
            SpeechRecognizer.ERROR_CLIENT to "识别客户端错误",
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT to "未检测到语音输入",
            SpeechRecognizer.ERROR_NO_MATCH to "未识别到语音",
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY to "识别服务忙",
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS to "权限不足",
        )
        expected.forEach { (code, fragment) ->
            assertTrue("code $code", speechErrorMessage(code).contains(fragment))
        }
        // API 31+ / API 33 codes are matched by number (compileSdk-safe in this module).
        (10..15).forEach { code ->
            assertTrue("code $code", speechErrorMessage(code).isNotBlank())
        }
        assertEquals("语音识别失败（错误码 99）", speechErrorMessage(99))
        assertEquals("语音识别失败（错误码 -1）", speechErrorMessage(-1))
        // All branches must be distinct so logs are actionable.
        val all = (1..15).map(::speechErrorMessage)
        assertEquals(all.size, all.toSet().size)
    }

    @Test fun confidenceIsRoundedToTwoDecimals() {
        assertEquals(0.93, normalizeSpeechConfidence(0.9345f))
        assertEquals(0.94, normalizeSpeechConfidence(0.9351f))
        assertEquals(1.0, normalizeSpeechConfidence(1f))
        assertEquals(0.0, normalizeSpeechConfidence(0f))
        assertNull(normalizeSpeechConfidence(null))
        assertNull(normalizeSpeechConfidence(-1f))
        assertNull(normalizeSpeechConfidence(1.5f))
        assertNull(normalizeSpeechConfidence(Float.NaN))
        assertNull(normalizeSpeechConfidence(Float.POSITIVE_INFINITY))
    }

    @Test fun resultJsonHasTextConfidenceLanguageAndElapsed() {
        val json = Json.parseToJsonElement(
            speechResultJson("打开空调", 0.9345f, "zh-CN", 4_200).toString(),
        ).jsonObject
        assertEquals("打开空调", json["text"]!!.jsonPrimitive.content)
        assertEquals(0.93, json["confidence"]!!.jsonPrimitive.content.toDouble(), 0.0001)
        assertEquals("zh-CN", json["language"]!!.jsonPrimitive.content)
        assertEquals("4200", json["elapsedMs"]!!.jsonPrimitive.content)
    }

    @Test fun resultJsonOmitsUnknownConfidence() {
        val json = Json.parseToJsonElement(
            speechResultJson("hello", -1f, "en-US", 10).toString(),
        ).jsonObject
        // Key set must stay stable: unusable score is serialized as null, not dropped.
        assertTrue(json.containsKey("confidence"))
        assertTrue(json["confidence"] is JsonNull)
        assertEquals("hello", json["text"]!!.jsonPrimitive.content)
    }

    @Test fun resultJsonEscapesTextAsValidJson() {
        val text = "他说：\"你好\"\n第二行"
        val json = Json.parseToJsonElement(
            speechResultJson(text, 0.5f, "zh-CN", 1).toString(),
        ).jsonObject
        assertEquals(text, json["text"]!!.jsonPrimitive.content)
    }
}
