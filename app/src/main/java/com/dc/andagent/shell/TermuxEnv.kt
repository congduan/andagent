package com.dc.andagent.shell

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.system.Os
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Official Termux bootstrap is built for /data/data/com.termux/files/usr.
 * dash/bash are validated before advertising the embedded backend; apt/dpkg's
 * compile-time PREFIX is redirected via APT_CONFIG / DPKG_ADMINDIR. */
class TermuxEnv(private val context: Context) {
    private val paths = AndagentTermuxPaths(context)
    private val prefix = paths.prefix
    private val home = paths.home.apply { mkdirs() }
    private val dash = paths.dash
    private val readyFile = File(prefix, ".andagent-ready")
    private var session: TermuxSession? = null
    @Volatile private var embeddedValidated = false
    @Volatile private var lastSetupError = "尚未安装"
    @Volatile private var packageManagerStatus = "未探测"
    private var runtimeConfigSignature: String? = null

    init {
        runCatching { ensureRuntimeConfig() }
            .onFailure { Log.w("TermuxEnv", "apt config init failed", it) }
    }

    /** 让 apt 绕过 ELF 里编译期的 com.termux 目录；bootstrap 重装后也要重做。 */
    private fun ensureRuntimeConfig() {
        val signature = prefix.path
        val config = paths.aptConfig
        if (runtimeConfigSignature == signature && config.isFile &&
            paths.aptRuntimeDirs.all { it.isDirectory }) return
        val text = paths.aptConfigText
        if (runCatching { config.readText() }.getOrNull() != text) {
            config.parentFile?.mkdirs()
            config.writeText(text)
        }
        if (dash.canExecute()) {
            paths.aptRuntimeDirs.forEach { it.mkdirs() }
            // dpkg 解包用的影子目录：包内 /data/data/com.termux/files/usr
            // 通过符号链接落到内嵌 prefix，详见 apt.conf 的 DPkg::Options。
            runCatching {
                paths.pkgRoot.mkdirs()
                if (!paths.pkgRootLink.exists()) {
                    Os.symlink(paths.prefix.path, paths.pkgRootLink.path)
                }
            }.onFailure { Log.w("TermuxEnv", "dpkg instdir link failed", it) }
        }
        runtimeConfigSignature = signature
    }

    fun backend(): String = when {
        readyFile.exists() && dash.canExecute() && paths.bash.canExecute() -> "embedded"
        termuxInstalled() -> "termux_app"
        else -> "none"
    }

    fun status(): String {
        val permission = if (context.checkSelfPermission("com.termux.permission.RUN_COMMAND") == PackageManager.PERMISSION_GRANTED)
            "granted" else "denied"
        return "${backend()}: $lastSetupError · 包管理=$packageManagerStatus · PREFIX=${prefix.path} · PATH=${paths.path} · RUN_COMMAND=$permission"
    }

    @Suppress("DEPRECATION")
    private fun termuxInstalled() = runCatching { context.packageManager.getPackageInfo("com.termux", 0); true }.getOrDefault(false)

    suspend fun setup(): String = withContext(Dispatchers.IO) {
        if (embeddedValidated && backend() == "embedded") return@withContext "embedded 已就绪：$lastSetupError"
        if (android.os.Build.SUPPORTED_ABIS.none { it == "arm64-v8a" }) {
            lastSetupError = "当前仅提供 aarch64 bootstrap；设备架构不匹配"
            return@withContext "ERROR: $lastSetupError；后端 ${backend()}"
        }
        try {
            session?.close()
            session = null
            // 只有二进制缺失才重解压：探测失败不该每次重装 33MB，
            // pkg/apt 的失败也不该让可用的 dash/bash 一起被判死。
            if (!dash.canExecute() || !paths.bash.canExecute()) {
                TermuxBootstrapInstaller(context, paths).install()
            }
            ensureRuntimeConfig()
            probe("dash", dash.path, "-c", "exit 0")
            probe("bash", paths.bash.path, "--noprofile", "--norc", "-c", "exit 0")
            readyFile.writeText("dash bash\n")
            embeddedValidated = true
            lastSetupError = "已就绪"
            // apt/dpkg 的 ELF 仍带官方编译期 PREFIX，属独立能力；这里只如实
            // 记录，不再把整个 shell 后端一票否决。
            packageManagerStatus = runCatching {
                probe("pkg", paths.pkg.path, "list-installed")
                "可用"
            }.getOrElse { "不可用（${it.message}）" }
            "embedded 已安装：${prefix.path}（dash/bash 实测通过；包管理 $packageManagerStatus）"
        } catch (e: Exception) {
            readyFile.delete()
            lastSetupError = "内嵌 bootstrap 安装失败：${e.message ?: e.javaClass.simpleName}"
            "ERROR: $lastSetupError；可用后端 ${backend()}。Termux App 需授权 RUN_COMMAND 并在 termux.properties 开启 allow-external-apps=true"
        }
    }

    private fun probe(label: String, vararg args: String) {
        val proc = ProcessBuilder(*args).directory(home).apply {
            environment().putAll(paths.environment)
        }.start()
        val out = StringBuilder()
        val err = StringBuilder()
        val t1 = drain(proc.inputStream, out, 2000)
        val t2 = drain(proc.errorStream, err, 2000)
        if (!proc.waitFor(8, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            error("$label 实测超时；stdout=${out.toString().take(200)}；stderr=${err.toString().take(200)}")
        }
        t1.join(300); t2.join(300)
        if (proc.exitValue() != 0) {
            error("$label 实测失败(exit=${proc.exitValue()}): ${err.toString().take(300).ifBlank { out.toString().take(300) }}")
        }
    }

    /** Drain both pipes concurrently; an unread stdout pipe can deadlock a probe. */
    private fun drain(input: java.io.InputStream, sink: StringBuilder, cap: Int) = thread(isDaemon = true) {
        runCatching {
            input.bufferedReader().use { reader ->
                val buf = CharArray(1024)
                while (true) {
                    val n = reader.read(buf)
                    if (n < 0) break
                    synchronized(sink) {
                        if (sink.length < cap) sink.append(buf, 0, minOf(n, cap - sink.length))
                    }
                }
            }
        }
    }

    private fun environment(): Map<String, String> = paths.environment

    // ---------------- R27: 对话流内嵌终端（与 shell_exec 共享长驻会话） ----------------

    private val ttyMarkerSeq = java.util.concurrent.atomic.AtomicInteger(0)

    /** 检查+创建在同一把锁里，避免与 execEmbedded() 的懒创建竞态出双会话。 */
    @Synchronized private fun currentSession(): TermuxSession {
        session?.takeIf { it.alive() }?.let { return it }
        return TermuxSession(paths.bash, home, environment()).also { session = it }
    }

    /** 终端执行一行命令：写入共享 bash 的 stdin，附加 tty 弱 marker 标记命令
     * 边界（供前端识别命令结束；execute() 的 UUID marker 不受影响）。不等人，
     * 输出由 terminalPoll() 增量拉取。用户手输命令不走危险命令黑名单。 */
    fun terminalExec(command: String): String {
        if (!paths.bash.canExecute()) return "ERROR: 内嵌 shell 未就绪，请先运行 termux_setup"
        return try {
            val marker = "__ANDAGENT_TTY_${ttyMarkerSeq.incrementAndGet()}"
            currentSession().writeRaw("$command\nprintf '$marker:%s\\n' \"\$?\"\n")
            "ok"
        } catch (e: Exception) {
            "ERROR: 终端执行失败：${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** 终端裸写入 stdin（交互程序应答等），不附加 marker。 */
    fun terminalWrite(input: String): String {
        if (!paths.bash.canExecute()) return "ERROR: 内嵌 shell 未就绪，请先运行 termux_setup"
        return try {
            currentSession().writeRaw(input)
            "ok"
        } catch (e: Exception) {
            "ERROR: 终端写入失败：${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** 拉取终端增量输出；会话还没起来时返回空串（卡片空闲态，不算错误）。 */
    fun terminalPoll(): String = try {
        session?.takeIf { it.alive() }?.terminalPoll() ?: ""
    } catch (_: Exception) {
        ""
    }

    suspend fun exec(command: String, timeoutSeconds: Int): String = withContext(Dispatchers.IO) {
        if (Regex("(?i)(rm\\s+-[a-z]*r[a-z]*f\\s+/($|\\s)|mkfs\\b|dd\\s+[^\\n]*of=/dev/|flash_image\\b)").containsMatchIn(command))
            return@withContext "ERROR: 命令被危险路径黑名单拦截"
        runCatching { ensureRuntimeConfig() }
        val embeddedAvailable = dash.canExecute() && paths.bash.canExecute()
        val hasTermuxApp = termuxInstalled()
        if (!embeddedAvailable && !hasTermuxApp)
            return@withContext "ERROR: 尚无可用脚本后端；请安装 Termux 或先运行 termux_setup"
        // 已有就绪标记时优先内嵌；没标记但装了 Termux App 时直接用它，避免
        // 在已知不可用的内嵌链路上白等一个完整超时。
        val preferEmbedded = backend() == "embedded" || !hasTermuxApp
        if (!preferEmbedded) {
            return@withContext TermuxAppBackend(context).exec(command, timeoutSeconds)
        }
        val result = if (embeddedAvailable) {
            execEmbedded(command, timeoutSeconds)
        } else {
            "ERROR: 内嵌 dash/bash 不可执行"
        }
        if (!result.startsWith("ERROR:") || !hasTermuxApp) return@withContext result
        "$result\n[fallback] 内嵌后端失败，改用 Termux App：\n" +
            TermuxAppBackend(context).exec(command, timeoutSeconds)
    }

    /** Every embedded path is best-effort; failures carry the underlying stderr
     * so exec() can fall back instead of reporting a bare "shell exited". */
    private suspend fun execEmbedded(command: String, timeoutSeconds: Int): String {
        var sessionFailure: String? = null
        val current = try {
            session?.takeIf { it.alive() } ?: synchronized(this) {
                // 双检：终端线程（currentSession）可能刚建好会话。
                session?.takeIf { it.alive() } ?: TermuxSession(paths.bash, home, environment()).also { session = it }
            }
        } catch (e: Exception) {
            sessionFailure = "长驻 bash 启动失败：${e.message}"
            Log.w("TermuxEnv", "session startup failed, using one-shot fallback", e)
            null
        }
        if (current != null) {
            try {
                return current.execute(command, timeoutSeconds)
            } catch (e: Exception) {
                sessionFailure = "长驻 bash 执行失败：${e.message}"
                Log.w("TermuxEnv", "session command failed; using one-shot fallback", e)
                current.close()
                session = null
            }
        }
        // Path order under targetSdk 28:
        //  1. DIRECT exec — data-dir execve is legal (W^X only for targetSdk>=29).
        //  2. memfd — for ROMs that somehow still restrict direct exec.
        //  3. Termux App bridge — handled by exec().
        try {
            val proc = ProcessBuilder(dash.path, "-c", command).directory(home)
                .apply { environment().putAll(environment()) }.start()
            val out = StringBuilder()
            val err = StringBuilder()
            val t1 = drain(proc.inputStream, out, 4000)
            val t2 = drain(proc.errorStream, err, 2000)
            if (!proc.waitFor(timeoutSeconds.toLong(), TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                return "ERROR: 命令超过 ${timeoutSeconds}s"
            }
            t1.join(500); t2.join(500)
            Log.i("TermuxEnv", "exec backend=direct exit=${proc.exitValue()}")
            return formatResult(proc.exitValue(), out.toString(), err.toString(), "direct")
        } catch (e: java.io.IOException) {
            Log.w("TermuxEnv", "direct exec failed (${e.message}); trying memfd", e)
            val directFailure = e.message ?: e.javaClass.simpleName
            val memfdFailure = try {
                val (code, output) = MemExec.exec(
                    elfFile = dash,
                    args = listOf(dash.path, "-c", command),
                    env = environment().toMutableMap(),
                    workDir = home.path,
                    timeoutSeconds = timeoutSeconds.toLong(),
                )
                Log.i("TermuxEnv", "exec backend=memfd exit=$code")
                if (code != 127 || !output.contains("errno=13")) {
                    return formatResult(code, output, "", "memfd")
                }
                "errno=13 (EACCES)，ROM 也拦截 memfd"
            } catch (e2: Exception) {
                Log.w("TermuxEnv", "memfd failed", e2)
                e2.message ?: e2.javaClass.simpleName
            }
            return buildString {
                append("ERROR: 内嵌后端所有执行路径都失败")
                sessionFailure?.let { append("\n[session] ").append(it) }
                append("\n[direct] ").append(directFailure)
                append("\n[memfd] ").append(memfdFailure)
                append("。请安装 Termux App 作回退")
            }
        }
    }

    private fun formatResult(exitCode: Int, stdout: String, stderr: String, backend: String): String =
        buildString {
            append("exit=").append(exitCode).append("\n[backend] ").append(backend).append("\n")
            if (stdout.isNotBlank()) append(stdout.take(4000))
            if (stderr.isNotBlank()) append("\n[stderr] ").append(stderr.take(2000))
        }
}

class TermuxAppBackend(private val context: Context) {
    suspend fun exec(command: String, timeoutSeconds: Int): String = withContext(Dispatchers.IO) {
        if (context.checkSelfPermission("com.termux.permission.RUN_COMMAND") != PackageManager.PERMISSION_GRANTED)
            return@withContext "ERROR: 未获 Termux RUN_COMMAND 权限。在电脑执行 adb shell pm grant com.dc.andagent com.termux.permission.RUN_COMMAND。授权后还需在 Termux 的 termux.properties 设置 allow-external-apps=true 并重启 Termux。"
        val id = UUID.randomUUID().toString()
        val pending = TermuxResultReceiver.Pending(CountDownLatch(1))
        TermuxResultReceiver.pending[id] = pending
        try {
            // BroadcastReceiver result intent: works even though Termux is a
            // background app when it posts the result (startService would be
            // blocked by Android 12+ background service restrictions).
            val reply = Intent(TermuxResultReceiver.ACTION)
                .setPackage(context.packageName)
                .putExtra(TermuxResultReceiver.EXTRA_ID, id)
            val pi = PendingIntent.getBroadcast(
                context, id.hashCode(), reply,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_MUTABLE,
            )
            val intent = Intent("com.termux.RUN_COMMAND").apply {
                setClassName("com.termux", "com.termux.app.RunCommandService")
                putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/sh")
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", command))
                putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pi)
            }
            val started = runCatching { context.startService(intent) }.getOrNull()
                ?: return@withContext "ERROR: 无法启动 Termux 的 RunCommandService；请先手动打开一次 Termux，并确认安装的是同一签名版本（F-Droid 与 Play 版签名不同，混装会 ServiceNotFound）"
            if (!pending.latch.await((timeoutSeconds + 10).toLong(), TimeUnit.SECONDS))
                "ERROR: Termux 命令未在时限内返回。最常见原因：Termux 的 ~/.termux/termux.properties 缺 allow-external-apps=true（改完需重启 Termux）"
            else pending.result ?: "ERROR: Termux 未返回结果"
        } catch (e: Exception) { "ERROR: Termux App 执行失败：${e.message}" }
        finally { TermuxResultReceiver.pending.remove(id) }
    }
}
