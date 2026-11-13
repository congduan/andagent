package com.dc.andagent.llama

import android.app.ActivityManager
import android.content.Context
import com.dc.andagent.tools.Tool
import com.dc.andagent.tools.intParam
import com.dc.andagent.tools.numParam
import com.dc.andagent.tools.schemaOf
import com.dc.andagent.tools.strParam
import com.dc.andagent.tools.strArg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.coroutineContext

val LLAMA_TOOLS = listOf("llama_status", "llama_model_download", "llama_model_delete", "llama_load", "llama_generate", "llama_unload")

class LlamaTool(private val context: Context, override val name: String) : Tool {
    companion object {
        private val http = OkHttpClient()
        private val busy = AtomicBoolean(false)
        private val nativeRunning = AtomicBoolean(false)
        @Volatile private var loadedName: String? = null
        @Volatile private var downloadName: String? = null
        @Volatile private var downloadBytes: Long = 0
        @Volatile private var downloadTotalBytes: Long = -1
        const val RECOMMENDED_NAME = "Qwen2.5-0.5B-Instruct-Q4_K_M.gguf"
        const val RECOMMENDED_URL = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf"
    }
    private val dir get() = File(context.filesDir, "llama-models").apply { mkdirs() }
    override val description = "本机 llama.cpp CPU 推理；0.5B 模型仅适合格式化、分类和轻问答。"
    override val destructive = name == "llama_model_download" || name == "llama_model_delete"
    override val parameters = when (name) {
        "llama_model_download" -> schemaOf("url" to strParam("HTTPS GGUF 下载地址；省略使用推荐模型"), "name" to strParam("文件名；省略使用推荐模型名"))
        "llama_model_delete", "llama_load" -> schemaOf("name" to strParam("模型文件名，须先下载"))
        "llama_generate" -> schemaOf("prompt" to strParam("用户输入"), "max_tokens" to intParam("1..512，默认 256"), "temperature" to numParam("0..2，默认 0.7；0 为贪心"))
        else -> schemaOf()
    }

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        if (name == "llama_status") return@withContext status()
        if (nativeRunning.get() || !busy.compareAndSet(false, true)) return@withContext "ERROR: 本地模型正在执行另一任务"
        try {
            when (name) {
                "llama_model_download" -> download(args)
                "llama_model_delete" -> delete(args)
                "llama_load" -> load(args)
                "llama_generate" -> generate(args)
                "llama_unload" -> { LlamaNative.unload(); loadedName = null; "OK: 模型已卸载" }
                else -> "ERROR: 未知工具"
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            "ERROR: ${e.message ?: e.javaClass.simpleName}"
        } finally { busy.set(false) }
    }

    private fun status(): String {
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        return buildJsonObject {
            put("loaded", loadedName ?: "")
            put("busy", busy.get())
            put("totalMemoryBytes", info.totalMem)
            put("availableMemoryBytes", info.availMem)
            put("recommendedName", RECOMMENDED_NAME)
            put("recommendedUrl", RECOMMENDED_URL)
            put("downloadName", downloadName ?: "")
            put("downloadBytes", downloadBytes)
            put("downloadTotalBytes", downloadTotalBytes)
            putJsonArray("models") { dir.listFiles()?.filter { GgufFiles.valid(it) }?.forEach { file ->
                add(buildJsonObject { put("name", file.name); put("bytes", file.length()) })
            } }
        }.toString()
    }

    private suspend fun download(args: JsonObject): String {
        val url = args.strArg("url") ?: RECOMMENDED_URL
        val name = args.strArg("name") ?: RECOMMENDED_NAME
        if (!url.startsWith("https://") || !GgufFiles.safeName(name) || !name.endsWith(".gguf", true)) return "ERROR: 需要 HTTPS GGUF 地址和安全文件名"
        val target = File(dir, name)
        if (target.exists()) return "ERROR: 文件已存在；先删除再下载"
        val part = File(dir, "$name.part")
        val offset = part.length()
        downloadName = name
        downloadBytes = offset
        downloadTotalBytes = -1
        try {
        val request = Request.Builder().url(url).apply { if (offset > 0) header("Range", "bytes=$offset-") }.build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return "ERROR: HTTP ${response.code}"
            val append = offset > 0 && response.code == 206 && response.header("Content-Range")?.startsWith("bytes $offset-") == true
            if (offset > 0 && !append && response.code != 200) return "ERROR: 服务端不支持有效断点续传"
            val body = response.body ?: return "ERROR: 响应无内容"
            val expected = body.contentLength().takeIf { it >= 0 }?.let { it + if (append) offset else 0 }
            downloadTotalBytes = expected ?: -1
            RandomAccessFile(part, "rw").use { output ->
                if (!append) output.setLength(0)
                output.seek(if (append) offset else 0)
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        downloadBytes = output.filePointer
                    }
                }
                output.fd.sync()
            }
            if (expected != null && part.length() != expected) return "ERROR: 下载截断；已保留 ${part.length()} 字节供续传"
        }
        if (!GgufFiles.valid(part)) return "ERROR: GGUF magic 错误或文件截断；已保留 .part"
        if (!part.renameTo(target)) return "ERROR: 无法保存模型；已保留 .part"
        return "OK: 已下载 $name (${target.length()} 字节)"
        } finally { downloadName = null }
    }

    private fun delete(args: JsonObject): String {
        val name = args.strArg("name") ?: return "ERROR: 缺少 name"
        if (!GgufFiles.safeName(name)) return "ERROR: 文件名不合法"
        if (loadedName == name) { LlamaNative.unload(); loadedName = null }
        val file = File(dir, name)
        return if (file.delete()) "OK: 已删除 $name" else "ERROR: 模型不存在或删除失败"
    }

    private suspend fun load(args: JsonObject): String {
        val name = args.strArg("name") ?: return "ERROR: 缺少 name"
        if (!GgufFiles.safeName(name)) return "ERROR: 文件名不合法"
        val file = File(dir, name)
        if (!GgufFiles.valid(file)) return "ERROR: GGUF 文件无效或缺失"
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        if (info.totalMem < 4L * 1024 * 1024 * 1024) return "ERROR: 设备物理内存不足 4GB"
        if (info.availMem < file.length() * 2 + 256L * 1024 * 1024) return "ERROR: 当前可用内存不足"
        val threads = bigCoreCount()
        LlamaNative.resetCancellation()
        nativeRunning.set(true)
        val error = suspendCancellableCoroutine<String> { continuation ->
            continuation.invokeOnCancellation { LlamaNative.cancel() }
            Thread({
                try {
                    val result = runCatching { LlamaNative.load(file.absolutePath, threads) }
                        .getOrElse { it.message ?: it.javaClass.simpleName }
                    if (continuation.isActive) continuation.resume(result)
                } finally { nativeRunning.set(false) }
            }, "llama-load").start()
        }
        loadedName = if (error.isEmpty()) name else null
        return if (error.isEmpty()) "OK: 已加载 $name；上下文 2048；线程 $threads" else "ERROR: $error"
    }

    private suspend fun generate(args: JsonObject): String {
        if (loadedName == null) return "ERROR: 先调用 llama_load"
        val prompt = args.strArg("prompt")?.takeIf { it.isNotBlank() } ?: return "ERROR: 缺少 prompt"
        if (prompt.length > 16_384) return "ERROR: prompt 最长 16384 字符"
        val maxTokens = args["max_tokens"]?.toString()?.toIntOrNull() ?: 256
        val temperature = args["temperature"]?.toString()?.toFloatOrNull() ?: 0.7f
        if (maxTokens !in 1..512 || temperature !in 0f..2f || !temperature.isFinite()) return "ERROR: 参数超出范围"
        val start = System.nanoTime()
        LlamaNative.resetCancellation()
        nativeRunning.set(true)
        val text = try { withTimeout(120_000) {
            suspendCancellableCoroutine<String?> { continuation ->
                continuation.invokeOnCancellation { LlamaNative.cancel() }
                Thread({
                    try {
                        val result = runCatching { LlamaNative.generate(prompt, maxTokens, temperature) }.getOrNull()
                        if (continuation.isActive) continuation.resume(result)
                    } finally { nativeRunning.set(false) }
                }, "llama-generate").start()
            }
        } } catch (_: TimeoutCancellationException) {
            return "ERROR: 本地生成超过 120 秒，已取消"
        } ?: return "ERROR: 生成中断或失败"
        val tokens = LlamaNative.generatedTokens()
        val seconds = (System.nanoTime() - start) / 1e9
        return buildJsonObject { put("text", text); put("tokens", tokens); put("tokensPerSecond", tokens / seconds) }.toString()
    }

    private fun bigCoreCount(): Int {
        val count = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val frequencies = (0 until count).mapNotNull { cpu ->
            File("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq")
                .runCatching { readText().trim().toLong() }.getOrNull()
        }
        if (frequencies.size != count) return count.coerceAtMost(4)
        val peak = frequencies.maxOrNull() ?: return count.coerceAtMost(4)
        return frequencies.count { it >= peak * 8 / 10 }.coerceIn(1, 4)
    }
}
