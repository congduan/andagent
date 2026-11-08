package com.dc.andagent.tools

import android.content.Context
import com.dc.andagent.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * Screen capture tools backed exclusively by MediaProjection (R28). The
 * one-time system consent dialog is the only privilege gate; there is no
 * external shell backend anymore (R107).
 */
class ScreenCaptureTool(private val context: Context) : Tool {
    override val name = "screen_capture"
    override val description =
        "截取一帧屏幕，保存 JPEG 并返回缩略图。首次调用会弹出系统屏幕捕获授权（一次授权后全自动）。"
    override val parameters = schemaOf()
    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        when (ensureCaptureChannel(context)) {
            CaptureChannel.PROJECTION -> projectionCaptureFrame(context)
            CaptureChannel.NEED_CONSENT -> "ERROR: " + tr(
                "屏幕捕获未授权：请在系统弹窗中点「立即开始」后重试（也可到 设置 → 屏幕捕获 手动授权）",
                "screen capture not granted: tap \"Start now\" in the system prompt and retry (or grant it in Settings → Screen capture)",
            )
        }
    }
}

class ScreenRecordH264Tool(private val context: Context) : Tool {
    override val name = "screen_record_h264"
    override val description =
        "录制屏幕到 Movies/AndAgent MP4，可选时长与码率。首次调用会弹出系统屏幕捕获授权（一次授权后全自动）。"
    override val parameters = schemaOf(
        "duration_s" to intParam("时长秒数，默认 30，最大 180"),
        "bit_rate" to intParam("码率 bps，默认 4000000"),
    )
    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        val duration = (args.intArg("duration_s") ?: 30).coerceIn(1, 180)
        val bitRate = args.intArg("bit_rate") ?: 4_000_000
        when (ensureCaptureChannel(context)) {
            CaptureChannel.PROJECTION -> {
                // MediaRecorder writes H.264 directly; start → wait → stop
                // gives the same synchronous contract as screen_record.
                val started = projectionStartRecording(context, duration, bitRate)
                if (started.startsWith("ERROR")) return@withContext started
                Thread.sleep(duration * 1000L)
                projectionStopRecording(context) ?: "ERROR: 录屏提前结束，请重试"
            }
            CaptureChannel.NEED_CONSENT -> "ERROR: " + tr(
                "屏幕捕获未授权：请在系统弹窗中点「立即开始」后重试（也可到 设置 → 屏幕捕获 手动授权）",
                "screen capture not granted: tap \"Start now\" in the system prompt and retry (or grant it in Settings → Screen capture)",
            )
        }
    }
}
