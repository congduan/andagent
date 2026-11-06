package com.dc.andagent.tools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.dc.andagent.bridge.PermissionBroker
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.math.round

const val SPEECH_DEFAULT_TIMEOUT_S = 15
const val SPEECH_MIN_TIMEOUT_S = 5
const val SPEECH_MAX_TIMEOUT_S = 60
const val SPEECH_DEFAULT_LANGUAGE = "zh-CN"

/** Clamp the requested wait budget; non-numeric or missing input falls back to the default. */
fun normalizeSpeechTimeout(seconds: Int?): Int =
    (seconds ?: SPEECH_DEFAULT_TIMEOUT_S).coerceIn(SPEECH_MIN_TIMEOUT_S, SPEECH_MAX_TIMEOUT_S)

/** BCP-47 tag for RecognizerIntent; blank/missing input falls back to zh-CN. */
fun normalizeSpeechLanguage(language: String?): String =
    language?.trim()?.takeIf { it.isNotEmpty() } ?: SPEECH_DEFAULT_LANGUAGE

/**
 * Human-readable text for a [SpeechRecognizer] error code. Kept pure so every
 * branch can be unit-tested without a real recognition service.
 */
fun speechErrorMessage(code: Int): String = when (code) {
    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时，请检查网络后重试"
    SpeechRecognizer.ERROR_NETWORK -> "网络错误，请检查网络连接"
    SpeechRecognizer.ERROR_AUDIO -> "录音失败，请检查麦克风是否被占用"
    SpeechRecognizer.ERROR_SERVER -> "识别服务错误，请稍后重试"
    SpeechRecognizer.ERROR_CLIENT -> "识别客户端错误，请重试"
    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "未检测到语音输入，请靠近麦克风重试"
    SpeechRecognizer.ERROR_NO_MATCH -> "未识别到语音，请靠近麦克风重试"
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别服务忙，请稍后重试"
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "麦克风权限不足，请在系统设置中授予"
    10 -> "识别请求过于频繁，请稍后重试" // ERROR_TOO_MANY_REQUESTS (API 31)
    11 -> "识别服务连接断开，请重试" // ERROR_SERVER_DISCONNECTED (API 31)
    12 -> "设备缺少该语言的识别模型，请更换 language 或安装语音数据" // ERROR_LANGUAGE_NOT_SUPPORTED (API 31)
    13 -> "该语言识别模型不可用，请更换 language" // ERROR_LANGUAGE_UNAVAILABLE (API 31)
    14 -> "无法检查语言支持情况，请重试" // ERROR_CANNOT_CHECK_SUPPORT (API 31)
    15 -> "无法监听下载事件，请稍后重试" // ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS (API 34)
    else -> "语音识别失败（错误码 $code）"
}

/** Confidence rounded to 2 decimals; null when the service omits an unusable score. */
fun normalizeSpeechConfidence(raw: Float?): Double? {
    if (raw == null || raw.isNaN() || raw.isInfinite() || raw < 0f || raw > 1f) return null
    return round(raw * 100.0) / 100.0
}

/**
 * Result payload shared with the LLM. Pure so the JSON shape can be asserted in unit tests.
 * The key set is stable: confidence is always present (null when the recognition
 * service does not report a score), so consumers can rely on the structure.
 */
fun speechResultJson(text: String, confidence: Float?, language: String, elapsedMs: Long): JsonObject =
    buildJsonObject {
        put("text", text)
        put("confidence", normalizeSpeechConfidence(confidence)?.let(::JsonPrimitive) ?: JsonNull)
        put("language", language)
        put("elapsedMs", elapsedMs)
    }

class SpeechRecognizeTool(
    private val context: Context,
    private val permissions: PermissionBroker,
) : Tool {
    override val name = "speech_recognize"
    override val description = "调用系统语音识别录一段用户语音并转成文字（语音输入）；返回识别文本、置信度、语言和耗时。"
    override val parameters = schemaOf(
        "timeout_s" to intParam("等待用户说完的上限秒数，默认 15，范围 5-60"),
        "language" to strParam("识别语言 BCP-47 标签，如 zh-CN、en-US，默认 zh-CN"),
    )

    override suspend fun execute(args: JsonObject): String {
        val timeoutS = normalizeSpeechTimeout(args.intArg("timeout_s"))
        val language = normalizeSpeechLanguage(args.strArg("language"))

        val available = isRecognitionAvailable()
        if (!available) return "ERROR: 设备无系统语音识别服务（如 Google App 未装或 ROM 裁剪）"
        if (!permissions.ensure(listOf(Manifest.permission.RECORD_AUDIO))) return "ERROR: 麦克风权限未授予"

        val startedAt = System.nanoTime()
        val outcome = withTimeoutOrNull(timeoutS * 1000L) { recognizeOnce(language) }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        return when (outcome) {
            null -> "ERROR: 语音识别超时（${timeoutS}s），请靠近麦克风重试"
            is SpeechOutcome.Failure -> outcome.message
            is SpeechOutcome.Success ->
                speechResultJson(outcome.text, outcome.confidence, language, elapsedMs).toString()
        }
    }

    /** SpeechRecognizer is only safe to touch on the main thread, including this probe. */
    private suspend fun isRecognitionAvailable(): Boolean =
        suspendCancellableCoroutine { cont ->
            onMain {
                val available = runCatching {
                    SpeechRecognizer.isRecognitionAvailable(context)
                }.getOrDefault(false)
                if (cont.isActive) cont.resume(available)
            }
        }

    /**
     * Creates the recognizer on the main thread, starts listening, and suspends
     * until onResults/onError. The recognizer is destroyed on every path:
     * results, error, start failure, and coroutine cancellation (timeout).
     */
    private suspend fun recognizeOnce(language: String): SpeechOutcome =
        suspendCancellableCoroutine { cont ->
            val settled = AtomicBoolean(false)
            val recognizerRef = AtomicReference<SpeechRecognizer?>(null)

            fun destroyRecognizer() {
                val recognizer = recognizerRef.getAndSet(null) ?: return
                onMain { runCatching { recognizer.destroy() } }
            }

            fun settle(outcome: SpeechOutcome) {
                if (!settled.compareAndSet(false, true)) return
                destroyRecognizer()
                if (cont.isActive) cont.resume(outcome)
            }

            cont.invokeOnCancellation {
                if (settled.compareAndSet(false, true)) destroyRecognizer()
            }

            // SpeechRecognizer must be created on the main thread.
            onMain {
                if (!cont.isActive) return@onMain
                val recognizer = try {
                    SpeechRecognizer.createSpeechRecognizer(context)
                } catch (e: Exception) {
                    settle(SpeechOutcome.Failure("ERROR: 语音识别初始化失败（${e.message ?: e.javaClass.simpleName}）"))
                    return@onMain
                }
                recognizerRef.set(recognizer)
                if (!cont.isActive) {
                    destroyRecognizer()
                    return@onMain
                }
                try {
                    recognizer.setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) = Unit
                        override fun onBeginningOfSpeech() = Unit
                        override fun onRmsChanged(rmsdB: Float) = Unit
                        override fun onBufferReceived(buffer: ByteArray?) = Unit
                        override fun onEndOfSpeech() = Unit
                        override fun onEvent(eventType: Int, params: Bundle?) = Unit
                        override fun onPartialResults(partialResults: Bundle?) = Unit

                        override fun onResults(results: Bundle?) {
                            val parsed = runCatching {
                                val text = results
                                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                    ?.firstOrNull()
                                    ?.trim()
                                    .orEmpty()
                                val confidence = results
                                    ?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                                    ?.firstOrNull()
                                text to confidence
                            }
                            parsed.fold(
                                onSuccess = { (text, confidence) ->
                                    if (text.isEmpty()) {
                                        settle(SpeechOutcome.Failure("ERROR: 未识别到语音内容，请靠近麦克风重试"))
                                    } else {
                                        settle(SpeechOutcome.Success(text, confidence))
                                    }
                                },
                                onFailure = {
                                    settle(SpeechOutcome.Failure("ERROR: 识别结果解析失败"))
                                },
                            )
                        }

                        override fun onError(error: Int) {
                            settle(SpeechOutcome.Failure("ERROR: ${speechErrorMessage(error)}（code=$error）"))
                        }
                    })
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                        putExtra(RecognizerIntent.EXTRA_PROMPT, "请说话…")
                    }
                    recognizer.startListening(intent)
                } catch (e: Exception) {
                    // Any failure after creation must settle here; settle also destroys the recognizer.
                    settle(SpeechOutcome.Failure("ERROR: 语音识别启动失败（${e.message ?: e.javaClass.simpleName}）"))
                }
            }
        }
}

private sealed interface SpeechOutcome {
    data class Success(val text: String, val confidence: Float?) : SpeechOutcome
    data class Failure(val message: String) : SpeechOutcome
}

/** Run immediately when already on the main thread, otherwise post there. */
private fun onMain(block: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) {
        block()
    } else {
        Handler(Looper.getMainLooper()).post(block)
    }
}
