package com.dc.andagent.tools

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 离线 OCR 引擎：ML Kit `text-recognition-chinese` 的 **bundled** 变体。
 *
 * 中文模型（含拉丁字母/数字）直接打进 APK，识别全程本地推理，不联网、不依赖
 * Google Play 服务运行时 —— 与 R35 的「零 GMS 依赖」原则一致。代价是 APK 体积，
 * 换来的好处是首次识别无需下载模型、飞行模式下可用。
 *
 * TextRecognizer 实例是线程安全的，全进程复用一个（懒加载：首次 process 时才
 * 初始化内置模型，约数百 ms~2s），避免每次调用重复加载模型。
 */
internal object MlKitOcr {
    /** 首次调用含模型加载，给足时间；超时只影响本次返回，不销毁已加载模型。 */
    const val TIMEOUT_MS = 60_000L

    private val client: TextRecognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    /** 识别单张位图（位图生命周期由调用方管理：本方法返回后 ML Kit 不再引用它）。 */
    suspend fun recognize(bitmap: Bitmap): Text = withTimeout(TIMEOUT_MS) {
        suspendCancellableCoroutine { cont ->
            client.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                .addOnFailureListener { e -> if (cont.isActive) cont.resumeWithException(e) }
                .addOnCanceledListener { if (cont.isActive) cont.cancel() }
        }
    }
}

/** OCR 需要的解码分辨率：编辑链路 2048 太小，截图/文档里的小字会被采样掉。 */
internal object OcrImageLimits {
    const val MAX_EDGE = 4096
    /** ~16MP ARGB_8888 = 64MB，长边 4096 的 4:3 照片仍能原样通过。 */
    const val MAX_PIXELS = 16_000_000L
}

/**
 * ocr_text：图片文字识别（OCR）。
 *
 * source 复用 image_edit 的图片来源约定（本地路径 / content:// / 相册 id / base64），
 * 所以截图（screen_capture、camera_capture 落盘的照片）可以直接接着识别。
 */
class OcrTextTool(private val context: Context) : Tool {
    override val name = "ocr_text"
    override val description =
        "识别图片中的文字（OCR，ML Kit 中文离线模型内置在 APK 内，全程本地推理、不联网、不需要 Google Play 服务）：" +
            "中英混排、多语言、横竖排均可识别；返回整体文本，可选逐行文本与行坐标框、" +
            "或按段落分组。source 支持本地绝对路径（如截图/拍照落盘的文件）、content:// URI、相册图片 id 或 base64。"
    override val parameters = schemaOf(
        "source" to strParam("图片：本地绝对路径 / content:// URI / 相册图片 id / base64（可带 data:image/...;base64, 前缀）"),
        "lines" to boolParam("是否返回逐行文本与坐标，默认 true"),
        "blocks" to boolParam("是否按段落分组返回，默认 false"),
    )

    override suspend fun execute(args: JsonObject): String {
        val source = args.strArg("source")
            ?: return "ERROR: 缺少 source（图片路径 / content:// URI / 相册 id / base64）"
        val wantLines = args.boolArg("lines") ?: true
        val wantBlocks = args.boolArg("blocks") ?: false

        return withContext(Dispatchers.IO) {
            val bitmap = try {
                ImageEditEngine.decode(
                    context,
                    source,
                    maxEdge = OcrImageLimits.MAX_EDGE,
                    maxPixels = OcrImageLimits.MAX_PIXELS,
                )
            } catch (e: Throwable) {
                return@withContext "ERROR: 图片解码失败：${e.message ?: e.javaClass.simpleName}"
            }
            val width = bitmap.width
            val height = bitmap.height

            // ML Kit 在任务结束前一直持有位图；超时后任务仍在跑，此时 recycle 会打到
            // native 层，所以只在任务确定结束后回收。
            var finished = false
            val text: Text = try {
                MlKitOcr.recognize(bitmap).also { finished = true }
            } catch (e: TimeoutCancellationException) {
                return@withContext "ERROR: OCR 超时（${MlKitOcr.TIMEOUT_MS / 1000} 秒）；" +
                    "首次识别需要加载内置模型，稍后重试通常可成功"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                finished = true
                return@withContext "ERROR: OCR 识别失败：${e.message ?: e.javaClass.simpleName}"
            } finally {
                if (finished) runCatching { bitmap.recycle() }
            }

            val blocks = text.textBlocks
            val lineCount = blocks.sumOf { it.lines.size }
            val fullText = text.text.trim()

            buildJsonObject {
                put("status", if (fullText.isEmpty()) "NO_TEXT" else "OK")
                put("text", fullText)
                put("charCount", fullText.length)
                put("lineCount", lineCount)
                put("blockCount", blocks.size)
                put("width", width)
                put("height", height)
                if (fullText.isEmpty()) {
                    put("hint", "图中未识别到文字；确认图片清晰、文字占比不过小（建议文字高度 ≥16px）")
                }
                if (wantLines) {
                    put(
                        "lines",
                        buildJsonArray {
                            blocks.forEach { block ->
                                block.lines.forEach { line ->
                                    add(
                                        buildJsonObject {
                                            put("text", line.text)
                                            put("lang", line.recognizedLanguage)
                                            putBox(line.boundingBox)
                                        },
                                    )
                                }
                            }
                        },
                    )
                }
                if (wantBlocks) {
                    put(
                        "blocks",
                        buildJsonArray {
                            blocks.forEach { block ->
                                add(
                                    buildJsonObject {
                                        put("text", block.text)
                                        put("lang", block.recognizedLanguage)
                                        put("lineCount", block.lines.size)
                                        putBox(block.boundingBox)
                                    },
                                )
                            }
                        },
                    )
                }
            }.toString()
        }
    }
}

/** 坐标框按 [left, top, width, height] 输出，对齐 image_edit 的 crop 参数语义。 */
private fun kotlinx.serialization.json.JsonObjectBuilder.putBox(box: android.graphics.Rect?) {
    if (box == null) return
    put(
        "box",
        buildJsonArray {
            add(box.left)
            add(box.top)
            add(box.width())
            add(box.height())
        },
    )
}
