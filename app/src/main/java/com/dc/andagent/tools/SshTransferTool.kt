package com.dc.andagent.tools

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpException
import com.jcraft.jsch.SftpProgressMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

val SSH_TRANSFER_TOOLS = listOf("ssh_download", "ssh_upload", "ssh_remote_ls")

/** Path and progress decisions are pure so they can be checked without Android or a server. */
object SshTransferRules {
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

class SshProgress(private val total: Long, private val maxBytes: Long, private val onReport: (Int) -> Unit = {}) : SftpProgressMonitor {
    private var done = 0L
    private var lastReported = 0L
    private var lastPercent = 0
    val lines = ArrayList<String>()
    var exceeded = false
        private set
    override fun init(op: Int, src: String?, dest: String?, max: Long) = Unit
    override fun count(count: Long): Boolean {
        done += count
        if (done > maxBytes) { exceeded = true; return false }
        val percent = if (total > 0) ((done * 100 / total).coerceAtMost(100)).toInt() else 0
        if (done - lastReported >= 1_048_576 || percent - lastPercent >= 5) {
            lines.add("进度: $done/$total 字节${if (total > 0) " ($percent%)" else ""}")
            lastReported = done
            lastPercent = percent
            onReport(percent)
        }
        return true
    }
    override fun end() = Unit
}

class SshTransferTool(private val context: Context, override val name: String) : Tool {
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
        val live = SshSessionPool.resolve(ref) ?: return@withContext "ERROR: SSH 会话不存在：$ref"
        val session = live.session
        if (!session.isConnected) {
            SshSessionPool.removeByRef(ref)
            return@withContext "ERROR: SSH 会话已断开：$ref"
        }
        try {
            synchronized(session) {
                val home = homeFor(session)
                val remote = SshTransferRules.remotePath(args.strArg("remote_path") ?: if (name == "ssh_remote_ls") "~" else return@withContext "ERROR: 缺少 remote_path", home)
                val sftp = session.openChannel("sftp") as ChannelSftp
                try {
                    sftp.connect(10_000)
                    when (name) {
                        "ssh_download" -> download(sftp, remote, args)
                        "ssh_upload" -> upload(sftp, remote, args)
                        else -> list(sftp, remote)
                    }
                } finally { sftp.disconnect() }
            }
        } catch (e: Exception) {
            if (!session.isConnected) SshSessionPool.removeByRef(ref)
            "ERROR: SFTP ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun homeFor(session: Session): String = homes.getOrPut(session) {
        val channel = session.openChannel("exec") as ChannelExec
        try {
            channel.setCommand("printf %s \"\$HOME\"")
            val input = channel.inputStream
            channel.connect(10_000)
            val value = input.bufferedReader().use { it.readText().take(4096) }.trim()
            require(value.startsWith('/')) { "无法获取远程 HOME" }
            value
        } finally { channel.disconnect() }
    }

    private fun download(sftp: ChannelSftp, remote: String, args: JsonObject): String {
        val attrs = sftp.stat(remote)
        if (attrs.isDir) return "ERROR: 不支持目录下载；可先用 ssh_exec 打包为 tar 文件"
        val max = SshTransferRules.maxMb(args.intArg("max_mb")).toLong() * 1_048_576
        if (attrs.size > max) return "ERROR: 文件 ${attrs.size} 字节超过 ${max / 1_048_576}MB 上限"
        val name = SshTransferRules.safeName(args.strArg("filename") ?: remote.substringAfterLast('/'))
        val dir = args.strArg("dir") ?: "downloads"
        if (dir !in listOf("downloads", "app_private")) return "ERROR: dir 只能是 downloads 或 app_private"
        val progress = SshProgress(attrs.size, max) { percent ->
            runCatching { SshKeepAliveService.progress(context, name, percent) }
        }
        val temp = File.createTempFile("ssh-", ".part", context.cacheDir)
        try {
            temp.outputStream().use { output -> sftp.get(remote, output, progress) }
            if (progress.exceeded) return "ERROR: 下载超过大小上限"
            val saved = if (dir == "app_private") {
                val root = File(context.filesDir, "ssh_downloads").apply { mkdirs() }
                val target = File(root, name)
                if (target.exists()) return "ERROR: 目标文件已存在：${target.absolutePath}"
                temp.copyTo(target)
                target.absolutePath
            } else saveToDownloads(temp, name)
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

    private fun upload(sftp: ChannelSftp, remote: String, args: JsonObject): String {
        val path = args.strArg("local_path") ?: return "ERROR: 缺少 local_path"
        val file = File(path).canonicalFile
        // 只允许上传下载产物，禁止把凭据等任意 app 私有文件传出设备。
        val privateRoot = File(context.filesDir, "ssh_downloads").canonicalFile
        val downloadRoot = File(Environment.getExternalStorageDirectory(), "Download/AndAgentSsh").canonicalFile
        require(file.startsWith(privateRoot) || file.startsWith(downloadRoot)) { "本地路径超出授权目录：仅限 Download/AndAgentSsh 或 app_private（ssh_downloads）" }
        require(file.isFile && file.canRead()) { "本地文件不存在或不可读" }
        val max = SshTransferRules.maxMb(args.intArg("max_mb")).toLong() * 1_048_576
        if (file.length() > max) return "ERROR: 文件 ${file.length()} 字节超过 ${max / 1_048_576}MB 上限（可用 max_mb 调整，最高 4096）"
        val overwriteConfirmed = args.boolArg("overwrite") == true && args.boolArg("confirm") == true
        if (!overwriteConfirmed) {
            val exists = try { sftp.stat(remote); true } catch (e: SftpException) {
                if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) false else throw e
            }
            if (exists) {
                return "CONFIRM_REQUIRED: 远程目标已存在，覆盖将替换原文件。请向用户说明风险；确认后传 overwrite=true、confirm=true。"
            }
            // TOCTOU 防护：先传到随机临时名，再原子 rename——rename 在目标已存在时会失败，不会静默覆盖。
            val tmp = "$remote.andagent-tmp-${java.util.UUID.randomUUID().toString().take(8)}"
            val progress = SshProgress(file.length(), max)
            try {
                file.inputStream().use { sftp.put(it, tmp, progress, ChannelSftp.OVERWRITE) }
                if (progress.exceeded) return "ERROR: 上传超过大小上限"
                try {
                    sftp.rename(tmp, remote)
                } catch (e: SftpException) {
                    return "CONFIRM_REQUIRED: 远程目标在传输期间被创建（${e.message ?: "rename 失败"}）。覆盖需 overwrite=true、confirm=true。"
                }
            } finally {
                runCatching { sftp.rm(tmp) }
            }
            return "uploadedPath=$remote\nbytes=${file.length()}\n" + progress.lines.joinToString("\n")
        }
        val progress = SshProgress(file.length(), max)
        file.inputStream().use { sftp.put(it, remote, progress, ChannelSftp.OVERWRITE) }
        if (progress.exceeded) return "ERROR: 上传超过大小上限"
        return "uploadedPath=$remote\nbytes=${file.length()}\n" + progress.lines.joinToString("\n")
    }

    private fun list(sftp: ChannelSftp, remote: String): String {
        val entries = sftp.ls(remote).filter { it.filename != "." && it.filename != ".." }
        return buildString {
            appendLine("remotePath=$remote count=${entries.size.coerceAtMost(500)}${if (entries.size > 500) " truncated=true" else ""}")
            entries.take(500).forEach { e -> appendLine("${e.filename}\tsize=${e.attrs.size}\tmtime=${e.attrs.mTime}\tpermissions=${e.attrs.permissionsString}") }
        }.trimEnd()
    }

    companion object {
        private val homes = ConcurrentHashMap<Session, String>()
        fun forgetHome(session: Session) { homes.remove(session) }
    }
}
