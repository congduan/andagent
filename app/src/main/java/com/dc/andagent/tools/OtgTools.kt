package com.dc.andagent.tools

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.math.min

fun isMountedOtg(state: String, emulated: Boolean, removable: Boolean): Boolean =
    state == Environment.MEDIA_MOUNTED && !emulated && removable

/** Parses a relative SAF path. Rejects path traversal and absolute paths. */
fun otgTreeMatchesVolume(treeId: String, uuid: String?): Boolean = uuid != null && treeId == "$uuid:"

fun otgPathParts(path: String): List<String>? {
    if (path.startsWith('/') || path.contains('\\')) return null
    val parts = path.split('/').filter { it.isNotEmpty() && it != "." }
    return if (parts.any { it == ".." || it.contains('\u0000') }) null else parts
}

class OtgTool(private val context: Context, override val name: String) : Tool {
    override val description = when (name) {
        "otg_status" -> "检测已挂载 OTG U 盘及 USB 设备和授权状态。"
        "otg_request" -> "申请 USB 设备权限或打开 SAF U 盘根目录授权选择器；两种授权彼此独立。"
        "otg_transfer" -> "U 盘与手机存储间二进制文件复制。direction=import：U 盘文件→手机 Download/AndAgentOtg/；direction=export：手机本地文件→U 盘指定路径。流式复制，大小上限 max_mb。"
        else -> "通过 SAF 对已授权 OTG U 盘执行 list/read/write/delete/mkdir。root 为 U 盘根目录树 URI，path 为相对路径。"
    }
    override val parameters = when (name) {
        "otg_status" -> schemaOf()
        "otg_request" -> schemaOf("permission" to strParam("usb 或 saf"), required = listOf("permission"))
        "otg_transfer" -> schemaOf(
            "direction" to strParam("import（U 盘→手机）或 export（手机→U 盘）"),
            "root" to strParam("已授权 U 盘根目录 content:// tree URI"),
            "remote_path" to strParam("U 盘根目录内相对路径（含文件名）"),
            "local_path" to strParam("export 时手机侧源文件绝对路径（白名单：Download/AndAgentSsh、Download/AndAgentOtg、app_private ssh_downloads）；import 时省略"),
            "max_mb" to intParam("大小上限 MB，默认 512，钳制 1..4096"),
            required = listOf("direction", "root", "remote_path"),
        )
        else -> schemaOf(
            "root" to strParam("已授权 U 盘根目录 content:// tree URI"),
            "op" to strParam("list | read | write | delete | mkdir"),
            "path" to strParam("U 盘根目录内相对路径；list 根目录传空字符串"),
            "content" to strParam("write 时写入的 UTF-8 文本"),
            required = listOf("root", "op", "path"),
        )
    }
    override val destructive get() = name in setOf("otg_file_ops", "otg_transfer")
    private val storage get() = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
    private val usb get() = context.getSystemService(Context.USB_SERVICE) as UsbManager

    override suspend fun execute(args: JsonObject): String = when (name) {
        "otg_status" -> status()
        "otg_request" -> request(args)
        "otg_transfer" -> withContext(Dispatchers.IO) { transfer(args) }
        else -> withContext(Dispatchers.IO) { fileOps(args) }
    }

    private fun status(): String {
        val volumes = storage.storageVolumes
        return buildJsonObject {
            put("mountedOtg", volumes.any { isMountedOtg(it.state, it.isEmulated, it.isRemovable) })
            put("volumes", buildJsonArray {
                volumes.forEach { v -> add(buildJsonObject {
                    put("uuid", v.uuid ?: "")
                    put("state", v.state)
                    put("emulated", v.isEmulated)
                    put("removable", v.isRemovable)
                    put("otg", isMountedOtg(v.state, v.isEmulated, v.isRemovable))
                    if (Build.VERSION.SDK_INT >= 30) put("directory", v.directory?.absolutePath ?: "")
                }) }
            })
            put("usbDevices", buildJsonArray { usb.deviceList.values.forEach { d -> add(buildJsonObject {
                put("deviceName", d.deviceName)
                put("vendorId", d.vendorId)
                put("productId", d.productId)
                put("permission", usb.hasPermission(d))
            }) } })
            put("safTrees", buildJsonArray { SafTreeStore.list(context).forEach { add(JsonPrimitive(it)) } })
        }.toString()
    }

    private suspend fun request(args: JsonObject): String { return when (args.strArg("permission")) {
        "saf" -> ExternalDataTool(context, dummyPermissionBroker(), "saf_tree_pick").execute(buildJsonObject {})
        "usb" -> {
            val devices = usb.deviceList.values.toList()
            if (devices.isEmpty()) return "ERROR: 未检测到 USB 设备；${status()}"
            val granted = mutableListOf<String>()
            val denied = mutableListOf<String>()
            for (device in devices) {
                if (usb.hasPermission(device) || requestUsb(device)) granted += device.deviceName else denied += device.deviceName
            }
            buildJsonObject { put("granted", buildJsonArray { granted.forEach { add(JsonPrimitive(it)) } }); put("denied", buildJsonArray { denied.forEach { add(JsonPrimitive(it)) } }); put("note", "经系统挂载的 U 盘可只用 SAF 授权；原始 USB 设备权限不能代替 SAF 树授权") }.toString()
        }
        else -> "ERROR: permission 必须是 usb 或 saf"
    } }

    // ExternalDataTool's SAF picker does not use its PermissionBroker. Keep the
    // dependency explicit by injecting the existing broker from MainActivity.
    private var broker: com.dc.andagent.bridge.PermissionBroker? = null
    fun withBroker(value: com.dc.andagent.bridge.PermissionBroker): OtgTool { broker = value; return this }
    private fun dummyPermissionBroker(): com.dc.andagent.bridge.PermissionBroker = broker ?: error("权限代理不可用")

    private suspend fun requestUsb(device: android.hardware.usb.UsbDevice): Boolean = withTimeoutOrNull(60_000) {
        suspendCancellableCoroutine { cont ->
            val action = "${context.packageName}.USB_PERMISSION.${UUID.randomUUID()}"
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    if (intent.action != action) return
                    runCatching { context.unregisterReceiver(this) }
                    if (cont.isActive) cont.resume(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && usb.hasPermission(device))
                }
            }
            try {
                val filter = IntentFilter(action)
                if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                else @Suppress("DEPRECATION") context.registerReceiver(receiver, filter)
                val intent = Intent(action).setPackage(context.packageName)
                val pending = PendingIntent.getBroadcast(context, device.deviceId, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) }; pending.cancel() }
                usb.requestPermission(device, pending)
            } catch (_: Exception) {
                runCatching { context.unregisterReceiver(receiver) }
                if (cont.isActive) cont.resume(false)
            }
        }
    } ?: false

    /** SAF 授权/挂载校验链：otg_transfer 与 fileOps 共用；返回 null=校验通过，否则为错误文案。 */
    private fun safGuard(root: String): String? {
        val volumes = storage.storageVolumes.filter { isMountedOtg(it.state, it.isEmulated, it.isRemovable) }
        if (volumes.isEmpty()) return "ERROR: 未挂载 OTG U 盘；" + status()
        if (root !in SafTreeStore.list(context) || context.contentResolver.persistedUriPermissions.none { it.uri.toString() == root && it.isReadPermission && it.isWritePermission })
            return "ERROR: SAF 树未取得持久读写授权；先调用 otg_request(permission=saf)，选择 U 盘根目录"
        val tree = runCatching { Uri.parse(root) }.getOrNull() ?: return "ERROR: root URI 无效"
        if (tree.scheme != "content") return "ERROR: root 必须是 content:// URI"
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return "ERROR: root 不是 SAF 树 URI"
        if (volumes.none { otgTreeMatchesVolume(rootId, it.uuid) }) return "ERROR: root 必须是当前已挂载 U 盘的根目录树；" + status()
        return null
    }

    /** 按扩展名映射 MIME（SAF createDocument 用）；未知扩展名落 octet-stream。 */
    private fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "mp4" -> "video/mp4"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "pdf" -> "application/pdf"
        "zip" -> "application/zip"
        "json" -> "application/json"
        "txt", "log" -> "text/plain"
        "html" -> "text/html"
        "csv" -> "text/csv"
        "apk" -> "application/vnd.android.package-archive"
        else -> "application/octet-stream"
    }

    /** 手机侧导出源白名单路径 → 绝对 File；不在白名单返回 null。 */
    private fun exportSource(localPath: String): File? {
        val f = File(localPath).normalize()
        if (f.isAbsolute.not()) return null
        val candidates = listOf(
            File(Environment.getExternalStorageDirectory(), "Download/AndAgentSsh"),
            File(Environment.getExternalStorageDirectory(), "Download/AndAgentOtg"),
            File(context.filesDir, "ssh_downloads"),
            File(context.filesDir, "otg_downloads"),
        )
        return candidates.firstOrNull { dir ->
            val canon = runCatching { f.canonicalPath }.getOrNull() ?: return@firstOrNull false
            canon.startsWith(runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath) + "/")
        }?.takeIf { f.isFile && f.canRead() } ?: run { if (f.isFile && f.canRead()) f else null }
    }

    /** R101c: U 盘 ↔ 手机二进制复制（otg_transfer）。流式对拷，max_mb 上限，二进制安全。 */
    private fun transfer(args: JsonObject): String {
        val direction = args.strArg("direction") ?: return "ERROR: 缺少 direction"
        if (direction !in setOf("import", "export")) return "ERROR: direction 必须是 import 或 export"
        val root = args.strArg("root") ?: return "ERROR: 缺少 root"
        val remotePath = args.strArg("remote_path") ?: return "ERROR: 缺少 remote_path"
        val parts = otgPathParts(remotePath) ?: return "ERROR: remote_path 必须是 U 盘内相对路径，不能包含 .. 或反斜杠"
        if (parts.isEmpty()) return "ERROR: remote_path 必须包含文件名"
        val maxBytes = (args.intArg("max_mb") ?: 512).coerceIn(1, 4096).toLong() * 1_048_576
        safGuard(root)?.let { return it }
        val tree = Uri.parse(root)
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val name = parts.last()
        val resolver = context.contentResolver
        return try {
            when (direction) {
                "import" -> {
                    val docId = resolve(tree, rootId, parts, "write")
                        ?: return "ERROR: U 盘文件不存在：$remotePath"
                    val uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
                    val saved = saveImportToDownloads(resolver, name, mimeFor(name), resolver.openInputStream(uri)
                        ?: return "ERROR: 无法打开 U 盘文件", maxBytes)
                    "IMPORTED: $remotePath → $saved"
                }
                "export" -> {
                    val local = args.strArg("local_path") ?: return "ERROR: export 需要 local_path（手机侧源文件）"
                    val src = exportSource(local)
                        ?: return "ERROR: local_path 必须是白名单目录内的可读文件（Download/AndAgentSsh|AndAgentOtg、app_private ssh_downloads|otg_downloads）：$local"
                    if (src.length() > maxBytes) return "ERROR: 文件 ${src.length()} 字节超过 ${maxBytes / 1_048_576}MB 上限"
                    // 逐级建目录（最后一级为文件名）
                    val dirParts = parts.dropLast(1)
                    var parentId = rootId
                    for (dir in dirParts) {
                        val existing = child(tree, parentId, dir)
                        parentId = existing ?: run {
                            val created = DocumentsContract.createDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, parentId), DocumentsContract.Document.MIME_TYPE_DIR, dir)
                            ?: return "ERROR: 无法创建 U 盘目录 $dir"
                            DocumentsContract.getDocumentId(created)
                        }
                    }
                    val docId = child(tree, parentId, name) ?: run {
                        val created = DocumentsContract.createDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, parentId), mimeFor(name), name)
                        ?: return "ERROR: 无法在 U 盘创建文件 $name"
                        DocumentsContract.getDocumentId(created)
                    }
                    val dstUri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
                    val copied = resolver.openOutputStream(dstUri, "wt")?.use { output ->
                        src.inputStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            var total = 0L
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                if (total + n > maxBytes) return "ERROR: 复制超过 ${maxBytes / 1_048_576}MB 上限（已中止）"
                                output.write(buffer, 0, n)
                                total += n
                            }
                            total
                        }
                    } ?: return "ERROR: 无法写入 U 盘文件"
                    "EXPORTED: $local → $remotePath ($copied bytes)"
                }
                else -> "ERROR: 未知方向"
            }
        } catch (e: Exception) { "ERROR: OTG 复制失败：${e.message ?: e.javaClass.simpleName}" }
    }

    /** import 落手机 Downloads/AndAgentOtg/（MediaStore IS_PENDING 原子发布，参照 ssh_download）。 */
    private fun saveImportToDownloads(resolver: android.content.ContentResolver, name: String, mime: String, input: java.io.InputStream, maxBytes: Long): String {
        val relPath = "${Environment.DIRECTORY_DOWNLOADS}/AndAgentOtg"
        val safeName = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "otg_file" }
        if (Build.VERSION.SDK_INT < 29) {
            val root = File(Environment.getExternalStorageDirectory(), relPath).apply { mkdirs() }
            val target = File(root, safeName)
            require(!target.exists()) { "目标文件已存在：${target.absolutePath}" }
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            target.outputStream().use { output ->
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (total + n > maxBytes) { target.delete(); error("复制超过 ${maxBytes / 1_048_576}MB 上限（已中止）") }
                    output.write(buffer, 0, n)
                    total += n
                }
            }
            return target.absolutePath
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: error("无法创建下载文件")
        try {
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            resolver.openOutputStream(uri)?.use { output ->
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (total + n > maxBytes) error("复制超过 ${maxBytes / 1_048_576}MB 上限（已中止）")
                    output.write(buffer, 0, n)
                    total += n
                }
            } ?: error("无法写入下载文件")
            values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            val actualName = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: safeName
            return "/sdcard/$relPath/$actualName ($total bytes)"
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    private fun fileOps(args: JsonObject): String {
        val root = args.strArg("root") ?: return "ERROR: 缺少 root"
        val op = args.strArg("op") ?: return "ERROR: 缺少 op"
        val path = args.strArg("path") ?: return "ERROR: 缺少 path"
        val parts = otgPathParts(path) ?: return "ERROR: path 必须是 U 盘内相对路径，不能包含 .. 或反斜杠"
        val volumes = storage.storageVolumes.filter { isMountedOtg(it.state, it.isEmulated, it.isRemovable) }
        if (volumes.isEmpty()) return "ERROR: 未挂载 OTG U 盘；${status()}"
        if (root !in SafTreeStore.list(context) || context.contentResolver.persistedUriPermissions.none { it.uri.toString() == root && it.isReadPermission && it.isWritePermission })
            return "ERROR: SAF 树未取得持久读写授权；先调用 otg_request(permission=saf)，选择 U 盘根目录"
        val tree = runCatching { Uri.parse(root) }.getOrNull() ?: return "ERROR: root URI 无效"
        if (tree.scheme != "content") return "ERROR: root 必须是 content:// URI"
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return "ERROR: root 不是 SAF 树 URI"
        if (volumes.none { otgTreeMatchesVolume(rootId, it.uuid) }) return "ERROR: root 必须是当前已挂载 U 盘的根目录树；${status()}"
        return try {
            val doc = resolve(tree, rootId, parts, op)
            when (op) {
                "list" -> {
                    val id = doc ?: return "ERROR: 路径不存在"
                    buildJsonObject { put("root", root); put("path", path); put("files", listChildren(tree, id)) }.toString()
                }
                "read" -> {
                    val id = doc ?: return "ERROR: 文件不存在"
                    val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (output.size() <= 100 * 1024) {
                            val n = input.read(buffer, 0, minOf(buffer.size, 100 * 1024 + 1 - output.size()))
                            if (n <= 0) break
                            output.write(buffer, 0, n)
                        }
                        output.toByteArray()
                    } ?: return "ERROR: 无法读取文件"
                    if (bytes.size > 100 * 1024) return "ERROR: 文件超过 100KB，文本读取上限"
                    buildJsonObject { put("path", path); put("content", bytes.toString(Charsets.UTF_8)); put("size", bytes.size); put("uri", uri.toString()) }.toString()
                }
                "write" -> {
                    if (parts.isEmpty()) return "ERROR: 不可覆盖 U 盘根目录"
                    val content = args.strArg("content") ?: return "ERROR: 缺少 content"
                    val id = doc ?: return "ERROR: 无法创建文件"
                    val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(content.toByteArray(Charsets.UTF_8)) } ?: return "ERROR: 无法写入文件"
                    "WRITTEN: $uri (${content.toByteArray(Charsets.UTF_8).size} bytes)"
                }
                "delete" -> {
                    if (parts.isEmpty()) return "ERROR: 不可删除 U 盘根目录"
                    val id = doc ?: return "ERROR: 路径不存在"
                    if (DocumentsContract.deleteDocument(context.contentResolver, DocumentsContract.buildDocumentUriUsingTree(tree, id))) "DELETED: $path" else "ERROR: 删除失败"
                }
                "mkdir" -> if (doc != null) "WRITTEN: 目录已存在 $path" else "ERROR: 无法创建目录"
                else -> "ERROR: op 必须是 list/read/write/delete/mkdir"
            }
        } catch (e: Exception) { "ERROR: SAF 操作失败：${e.message ?: e.javaClass.simpleName}" }
    }

    private fun resolve(tree: Uri, rootId: String, parts: List<String>, op: String): String? {
        var id = rootId
        for ((index, part) in parts.withIndex()) {
            val existing = child(tree, id, part)
            if (existing != null) { id = existing; continue }
            if (op !in setOf("write", "mkdir")) return null
            val mime = if (index < parts.lastIndex || op == "mkdir") DocumentsContract.Document.MIME_TYPE_DIR else "text/plain"
            val created = DocumentsContract.createDocument(context.contentResolver, DocumentsContract.buildDocumentUriUsingTree(tree, id), mime, part) ?: return null
            id = DocumentsContract.getDocumentId(created)
        }
        return id
    }

    private fun child(tree: Uri, parent: String, name: String): String? {
        context.contentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent), arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) if (c.getString(1) == name) return c.getString(0)
        }
        return null
    }

    private fun listChildren(tree: Uri, parent: String) = buildJsonArray {
        var count = 0
        context.contentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent), arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use { c ->
            while (c.moveToNext() && count++ < 200) add(buildJsonObject {
                put("name", c.getString(1) ?: "")
                put("mime", c.getString(2) ?: "")
                put("size", if (c.isNull(3)) 0L else c.getLong(3))
                put("uri", DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)).toString())
            })
        }
    }
}
