package com.dc.andagent.tools

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import net.schmizz.sshj.sftp.Response
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.xfer.TransferListener
import net.schmizz.sshj.common.StreamCopier
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

val SSH_TRANSFER_TOOLS = listOf("ssh_download", "ssh_upload", "ssh_remote_ls")

object SshTransferCancel {
    private val cancelled = ConcurrentHashMap<String, Boolean>()
    fun key(ref: String, path: String) = "$ref\n$path"
    fun request(key: String) { cancelled[key] = true }
    fun isCancelled(key: String) = cancelled[key] == true
    fun clear(key: String) { cancelled.remove(key) }
    fun wasInterrupted(error: Throwable): Boolean = generateSequence(error) { it.cause }
        .any { it.message?.contains("已被用户中断") == true }
}

data class SshTransferEvent(val type: String, val key: String, val name: String, val direction: String,
                            val percent: Int = 0, val bytes: Long = 0, val total: Long = 0,
                            val ok: Boolean = false, val savedPath: String? = null)

/** Path and progress decisions are pure so they can be checked without Android or a server. */
object SshTransferRules {
    fun shouldNotify(lastAt: Long, lastPercent: Int, now: Long, percent: Int): Boolean =
        now - lastAt >= 2_000 && percent - lastPercent >= 10
    fun maxMb(value: Int?): Int = (value ?: 512).coerceIn(1, 4096)
    fun remotePath(path: String, home: String): String {
        require(path.isNotBlank() && !path.contains('\u0000') && !path.contains('\n')) { "远程路径无效" }
        require(path.split('/').none { it == ".." }) { "远程路径不能包含 .." }
        return when {
            path == "~" -> home
            path.startsWith("~/") -> home.trimEnd('/') + path.drop(1)
            path.startsWith("~") -> throw IllegalArgumentException("只支持当前用户的 ~ 路径")
            path.startsWith("/") -> path
            else -> home.trimEnd('/') + "/" + path
        }
    }
    fun safeName(name: String): String {
        require(name.isNotBlank() && name != "." && name != ".." && !name.contains('/') &&
            !name.contains('\\') && !name.contains('\u0000') && name.length <= 160 &&
            name.none { it.code < 0x20 }) { "文件名无效" }
        return name
    }
}

class SshProgress(private val total: Long, private val maxBytes: Long, private val onReport: (Int) -> Unit = {},
                  private val onTransfer: (Int, Long, Long) -> Unit = { _, _, _ -> }, private val cancelled: () -> Boolean = { false },
                  private val now: () -> Long = System::currentTimeMillis) {
    private var done = 0L
    private var lastReported = 0L
    private var lastPercent = 0
    private var lastNotifiedAt = now()
    private var lastNotifiedPercent = 0
    val lines = ArrayList<String>()
    var exceeded = false
        private set
    fun init(op: Int, src: String?, dest: String?, max: Long) = Unit
    fun count(count: Long): Boolean {
        if (cancelled()) throw IOException("已被用户中断")
        done += count
        if (done > maxBytes) { exceeded = true; return false }
        val percent = if (total > 0) ((done * 100 / total).coerceAtMost(100)).toInt() else 0
        if (done - lastReported >= 1_048_576 || percent - lastPercent >= 5) {
            lines.add("进度: $done/$total 字节${if (total > 0) " ($percent%)" else ""}")
            lastReported = done
            lastPercent = percent
            onTransfer(percent, done, total)
        }
        val time = now()
        if (SshTransferRules.shouldNotify(lastNotifiedAt, lastNotifiedPercent, time, percent)) {
            lastNotifiedAt = time
            lastNotifiedPercent = percent
            onReport(percent)
        }
        return true
    }
    fun end() = Unit
}

class SshTransferTool(private val context: Context, override val name: String,
                      private val emitTransfer: (SshTransferEvent) -> Unit = {}) : Tool {
    override val description = when (name) {
        "ssh_download" -> "从 SSH 服务器下载单个文件到 Download/AndAgentSsh 或 app_private，返回 savedPath，下载前可用 ssh_remote_ls 查看。"
        "ssh_upload" -> "将本机文件上传到 SSH 服务器。默认禁止覆盖；覆盖时需 overwrite=true 且 confirm=true。"
        else -> "列出 SSH 服务器远程目录，返回最多 500 项的名称、大小、修改时间和权限。"
    }
    override val destructive = name == "ssh_upload"
    override val parameters = when (name) {
        "ssh_download" -> schemaOf(
            "session_id" to strParam("SSH 会话 ID 或 nickname"),
            "remote_path" to strParam("远程文件路径，支持 ~/"),
            "dir" to strParam("downloads（默认）或 app_private"),
            "filename" to strParam("可选保存文件名"),
            "max_mb" to intParam("大小上限 MB，默认 512，钳制在 1..4096"),
            required = listOf("session_id", "remote_path"))
        "ssh_upload" -> schemaOf(
            "session_id" to strParam("SSH 会话 ID 或 nickname"),
            "local_path" to strParam("本地文件绝对路径，限 Download/AndAgentSsh 或 app_private（ssh_downloads），其他私有文件禁止上传"),
            "remote_path" to strParam("远程目标文件路径，支持 ~/"),
            "overwrite" to boolParam("允许覆盖已有文件，默认 false"),
            "confirm" to boolParam("确认覆盖风险；覆盖时必须 true"),
            "max_mb" to intParam("上传大小上限 MB，默认 512，钳制在 1..4096"),
            required = listOf("session_id", "local_path", "remote_path"))
        else -> schemaOf(
            "session_id" to strParam("SSH 会话 ID 或 nickname"),
            "remote_path" to strParam("远程目录路径，默认 ~"),
            required = listOf("session_id"))
    }

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        val ref = args.strArg("session_id") ?: return@withContext "ERROR: 缺少 session_id"
        val path = args.strArg("remote_path").orEmpty()
        val key = SshTransferCancel.key(ref, path)
        val direction = if (name == "ssh_download") "download" else "upload"
        val fileName = if (name == "ssh_download") args.strArg("filename") ?: path.substringAfterLast('/') else path.substringAfterLast('/')
        val transfer = name == "ssh_download" || name == "ssh_upload"
        if (transfer && path.isBlank()) {
            SshTransferCancel.clear(key)
            return@withContext "ERROR: 缺少 remote_path"
        }
        val live = SshSessionPool.resolve(ref) ?: run {
            SshTransferCancel.clear(key)
            return@withContext "ERROR: SSH 会话不存在：$ref"
        }
        val session = live.session
        if (!session.isConnected) {
            SshSessionPool.removeByRef(ref)
            SshTransferCancel.clear(key)
            return@withContext "ERROR: SSH 会话已断开：$ref"
        }
        val result = try {
            synchronized(session) {
                if (transfer) {
                    if (SshTransferCancel.isCancelled(key)) throw IOException("已被用户中断")
                    emitTransfer(SshTransferEvent("ssh.transfer.progress", key, fileName, direction))
                }
                val home = homeFor(session)
                val remote = SshTransferRules.remotePath(if (transfer) path else args.strArg("remote_path") ?: "~", home)
                val sftp = live.sftpChannel.let { existing ->
                    if (existing != null) existing else {
                        runCatching { existing?.close() }
                        live.sftpChannel = null
                        session.newSFTPClient().also { live.sftpChannel = it }
                    }
                }
                try {
                    when (name) {
                        "ssh_download" -> download(sftp, remote, args, key)
                        "ssh_upload" -> upload(sftp, remote, args, key)
                        else -> list(sftp, remote)
                    }
                } catch (e: Exception) {
                    if (SshTransferCancel.wasInterrupted(e)) {
                        // sshj's transfer listener aborts the SFTP operation; the SSH session stays alive.
                        runCatching { sftp.close() }
                    } else sftp.close()
                    live.sftpChannel = null
                    throw e
                }
            }
        } catch (e: Exception) {
            if (!session.isConnected) SshSessionPool.removeByRef(ref)
            if (SshTransferCancel.wasInterrupted(e) || SshTransferCancel.isCancelled(key)) "ERROR: 已被用户中断"
            else "ERROR: SFTP ${e.message ?: e.javaClass.simpleName}"
        }
        if (transfer) {
            SshTransferCancel.clear(key)
            val savedPath = result.lineSequence().firstOrNull { it.startsWith("savedPath=") || it.startsWith("uploadedPath=") }?.substringAfter('=')
            emitTransfer(SshTransferEvent("ssh.transfer.done", key, fileName, direction,
                ok = !result.startsWith("ERROR:") && !result.startsWith("CONFIRM_REQUIRED:"), savedPath = savedPath))
        }
        result
    }

    private fun homeFor(session: SSHClient): String = homes.getOrPut(session) {
        session.startSession().use { channel ->
            channel.exec("printf %s \"\$HOME\"").use { command ->
                val value = command.inputStream.bufferedReader().use { it.readText().take(4096) }.trim()
                command.join()
                require(value.startsWith('/')) { "无法获取远程 HOME" }
                value
            }
        }
    }

    private fun track(sftp: SFTPClient, progress: SshProgress) {
        sftp.fileTransfer.transferListener = object : TransferListener {
            override fun directory(name: String): TransferListener = this
            override fun file(name: String, size: Long): StreamCopier.Listener = object : StreamCopier.Listener {
                var previous = 0L
                override fun reportProgress(transferred: Long) {
                    if (!progress.count(transferred - previous)) throw IOException("传输超过大小上限")
                    previous = transferred
                }
            }
        }
    }

    private fun download(sftp: SFTPClient, remote: String, args: JsonObject, key: String): String {
        val attrs = sftp.stat(remote)
        if (attrs.type == FileMode.Type.DIRECTORY) return "ERROR: 不支持目录下载；可先用 ssh_exec 打包为 tar 文件"
        val max = SshTransferRules.maxMb(args.intArg("max_mb")).toLong() * 1_048_576
        if (attrs.size > max) return "ERROR: 文件 ${attrs.size} 字节超过 ${max / 1_048_576}MB 上限"
        val name = SshTransferRules.safeName(args.strArg("filename") ?: remote.substringAfterLast('/'))
        val dir = args.strArg("dir") ?: "downloads"
        if (dir !in listOf("downloads", "app_private")) return "ERROR: dir 只能是 downloads 或 app_private"
        emitTransfer(SshTransferEvent("ssh.transfer.progress", key, name, "download", total = attrs.size))
        val progress = SshProgress(attrs.size, max, onReport = { percent ->
            runCatching { SshKeepAliveService.progress(context, name, percent) }
        }, onTransfer = { percent, bytes, total ->
            emitTransfer(SshTransferEvent("ssh.transfer.progress", key, name, "download", percent, bytes, total))
        }, cancelled = { SshTransferCancel.isCancelled(key) })
        val temp = File.createTempFile("ssh-", ".part", context.cacheDir)
        try {
            track(sftp, progress)
            // R131 根因修复：sshj ReadAheadRemoteFileInputStream 构造参数是
            // maxUnconfirmedReads（在途请求数，RemoteFile.java:266）——R97b 误传
            // 1MB（1 shl 20）= 「瞬间发出 100 万个未确认 SFTP READ 请求」，服务器
            // 侧（OpenSSH NetAuditRead 洪泛保护/连接过载）直接挂起/断连——真机
            // 「进度卡 0%」的根因。R97b 想要的「1MB 带宽时延积」语义应该由并发数×
            // 请求大小实现：sshj 单请求大小 = max(1024, len)（RemoteFile.java:315，
            // len 来自 copyTo 的 read buffer）。
            // 修复：显式 RemoteFile.read(offset, len) 循环（256KB×并发8 ≈ 2MB 在途，
            // 对齐 OpenSSH sftp 客户端 pipelining），自带真实进度回调与 idle 超时。
            val remoteFile = sftp.open(remote)
            val fileLen = remoteFile.length()
            val chunk = 256 * 1024
            val buffer = ByteArray(chunk)
            var offset = 0L
            var lastDataAt = System.currentTimeMillis()
            temp.outputStream().buffered(1 shl 20).use { output ->
                while (offset < fileLen) {
                    if (SshTransferCancel.isCancelled(key)) throw IOException("已被用户中断")
                    val len = minOf(chunk.toLong(), fileLen - offset).toInt()
                    // sshj RemoteFile.read 内部 asyncRead.retrieve(timeoutMs)——
                    // requester 超时由会话层 sftp engine 配置控制（SshTools 三层超时）。
                    val n = remoteFile.read(offset, buffer, 0, len)
                    if (n <= 0) throw IOException("服务器提前结束数据流（offset=$offset/${fileLen}）")
                    if (!progress.count(n.toLong())) throw IOException("传输超过大小上限")
                    output.write(buffer, 0, n)
                    offset += n
                    val now = System.currentTimeMillis()
                    if (now - lastDataAt > 60_000) throw IOException("下载停滞超 60 秒（offset=$offset/${fileLen}），请检查服务器或网络")
                    lastDataAt = now
                }
            }
            if (SshTransferCancel.isCancelled(key)) throw IOException("已被用户中断")
            if (progress.exceeded) return "ERROR: 下载超过大小上限"
            val saved = if (dir == "app_private") {
                val root = File(context.filesDir, "ssh_downloads").apply { mkdirs() }
                val target = File(root, name)
                if (target.exists()) return "ERROR: 目标文件已存在：${target.absolutePath}"
                temp.copyTo(target)
                target.absolutePath
            } else saveToDownloads(temp, name)
            if (dir == "downloads") runCatching { SshKeepAliveService.downloadComplete(context, name) }
            return "savedPath=$saved\nbytes=${temp.length()}\n" + progress.lines.joinToString("\n")
        } finally {
            temp.delete()
            runCatching { SshKeepAliveService.progress(context, null, null) }
        }
    }

    private fun saveToDownloads(temp: File, name: String): String {
        val path = "${Environment.DIRECTORY_DOWNLOADS}/AndAgentSsh"
        if (Build.VERSION.SDK_INT < 29) {
            val root = File(Environment.getExternalStorageDirectory(), path).apply { mkdirs() }
            val target = File(root, name)
            require(!target.exists()) { "目标文件已存在：${target.absolutePath}" }
            temp.copyTo(target)
            return target.absolutePath
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH, path)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: error("无法创建下载文件")
        try {
            resolver.openOutputStream(uri)?.use { temp.inputStream().use { input -> input.copyTo(it) } }
                ?: error("无法写入下载文件")
            values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            val actualName = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: name
            return "/sdcard/$path/$actualName"
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    private fun upload(sftp: SFTPClient, remote: String, args: JsonObject, key: String): String {
        val path = args.strArg("local_path") ?: return "ERROR: 缺少 local_path"
        val file = File(path).canonicalFile
        // 只允许上传下载产物，禁止把凭据等任意 app 私有文件传出设备。
        val privateRoot = File(context.filesDir, "ssh_downloads").canonicalFile
        val downloadRoot = File(Environment.getExternalStorageDirectory(), "Download/AndAgentSsh").canonicalFile
        require(file.startsWith(privateRoot) || file.startsWith(downloadRoot)) { "本地路径超出授权目录：仅限 Download/AndAgentSsh 或 app_private（ssh_downloads）" }
        require(file.isFile && file.canRead()) { "本地文件不存在或不可读" }
        val max = SshTransferRules.maxMb(args.intArg("max_mb")).toLong() * 1_048_576
        if (file.length() > max) return "ERROR: 文件 ${file.length()} 字节超过 ${max / 1_048_576}MB 上限（可用 max_mb 调整，最高 4096）"
        emitTransfer(SshTransferEvent("ssh.transfer.progress", key, remote.substringAfterLast('/'), "upload", total = file.length()))
        val overwriteConfirmed = args.boolArg("overwrite") == true && args.boolArg("confirm") == true
        if (!overwriteConfirmed) {
            val exists = try { sftp.stat(remote); true } catch (e: SFTPException) {
                if (e.statusCode == Response.StatusCode.NO_SUCH_FILE) false else throw e
            }
            if (exists) {
                return "CONFIRM_REQUIRED: 远程目标已存在，覆盖将替换原文件。请向用户说明风险；确认后传 overwrite=true、confirm=true。"
            }
            // TOCTOU 防护：先传到随机临时名，再原子 rename——rename 在目标已存在时会失败，不会静默覆盖。
            val tmp = "$remote.andagent-tmp-${java.util.UUID.randomUUID().toString().take(8)}"
            val progress = uploadProgress(file, remote, max, key)
            try {
                track(sftp, progress)
                // R97b: write-ahead 1MB（对照 read-ahead 同理——广域网 RTT 下默认窗口限制吞吐）
                val tmpFile = sftp.open(tmp, java.util.EnumSet.of(net.schmizz.sshj.sftp.OpenMode.CREAT, net.schmizz.sshj.sftp.OpenMode.WRITE))
                tmpFile.use { rf ->
                    java.io.BufferedInputStream(file.inputStream().buffered(1 shl 20), 1 shl 20).use { input ->
                        rf.RemoteFileOutputStream(1L shl 20).use { output ->
                            val chunk = ByteArray(64 * 1024)
                            while (true) {
                                if (SshTransferCancel.isCancelled(key)) throw IOException("已被用户中断")
                                val n = input.read(chunk)
                                if (n < 0) break
                                output.write(chunk, 0, n)
                                progress.count(n.toLong())
                            }
                            output.flush()
                        }
                    }
                }
                if (SshTransferCancel.isCancelled(key)) throw IOException("已被用户中断")
                if (progress.exceeded) return "ERROR: 上传超过大小上限"
                try {
                    sftp.rename(tmp, remote)
                } catch (e: SFTPException) {
                    return "CONFIRM_REQUIRED: 远程目标在传输期间被创建（${e.message ?: "rename 失败"}）。覆盖需 overwrite=true、confirm=true。"
                }
            } finally {
                runCatching { sftp.rm(tmp) }
            }
            return "uploadedPath=$remote\nbytes=${file.length()}\n" + progress.lines.joinToString("\n")
        }
        val progress = uploadProgress(file, remote, max, key)
        track(sftp, progress)
        val remoteFile = sftp.open(remote, java.util.EnumSet.of(net.schmizz.sshj.sftp.OpenMode.CREAT, net.schmizz.sshj.sftp.OpenMode.WRITE))
        remoteFile.use { rf ->
            java.io.BufferedInputStream(file.inputStream().buffered(1 shl 20), 1 shl 20).use { input ->
                rf.RemoteFileOutputStream(1L shl 20).use { output ->
                    val chunk = ByteArray(64 * 1024)
                    while (true) {
                        if (SshTransferCancel.isCancelled(key)) throw IOException("已被用户中断")
                        val n = input.read(chunk)
                        if (n < 0) break
                        output.write(chunk, 0, n)
                        progress.count(n.toLong())
                    }
                    output.flush()
                }
            }
        }
        if (SshTransferCancel.isCancelled(key)) throw IOException("已被用户中断")
        if (progress.exceeded) return "ERROR: 上传超过大小上限"
        return "uploadedPath=$remote\nbytes=${file.length()}\n" + progress.lines.joinToString("\n")
    }

    private fun uploadProgress(file: File, remote: String, max: Long, key: String) =
        SshProgress(file.length(), max, onTransfer = { percent, bytes, total ->
            emitTransfer(SshTransferEvent("ssh.transfer.progress", key, remote.substringAfterLast('/'), "upload", percent, bytes, total))
        }, cancelled = { SshTransferCancel.isCancelled(key) })

    private fun list(sftp: SFTPClient, remote: String): String {
        val entries = sftp.ls(remote).filter { it.name != "." && it.name != ".." }
        return buildString {
            appendLine("remotePath=$remote count=${entries.size.coerceAtMost(500)}${if (entries.size > 500) " truncated=true" else ""}")
            entries.take(500).forEach { e -> appendLine("${e.name}\tsize=${e.attributes.size}\tmtime=${e.attributes.mtime}\tpermissions=${e.attributes.mode.toString()}") }
        }.trimEnd()
    }

    companion object {
        private val homes = ConcurrentHashMap<SSHClient, String>()
        fun forgetHome(session: SSHClient) { homes.remove(session) }
    }
}
