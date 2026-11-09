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

/**
 * R103 必修 3：export 源白名单 canonical 前缀匹配（纯函数，便于 JVM 单测）。
 * 只认「目录 canonical + /」前缀：目录自身路径不算命中，防 `AndAgentSshEvil` 之类前缀碰撞。
 */
internal fun exportWhitelisted(canonPath: String, whitelistDirs: List<File>): Boolean =
    whitelistDirs.any { dir ->
        val root = runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath)
        canonPath.startsWith(root + "/")
    }

/**
 * R111 review 必修 1：目录条目白名单——与 [exportWhitelisted] 的区别是允许路径**等于**
 * 白名单根目录本身（把 Download/AndAgentOtg 整目录作为 local_path 递归导出是合法用法；
 * 文件级仍走 exportWhitelisted 严格要求在目录内）。
 */
internal fun exportWhitelistedDir(canonPath: String, whitelistDirs: List<File>): Boolean =
    whitelistDirs.any { dir ->
        val root = runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath)
        canonPath == root || canonPath.startsWith(root + "/")
    }


/**
 * R103 必修 5：import 输入流统一用 use 消费，保证成功 / 超限 / 异常三条路径都关闭。
 * 纯函数，便于 JVM 单测用「未关闭会抛异常的包装 InputStream」验证。
 */
internal fun useOtgImportInput(
    input: java.io.InputStream?,
    save: (java.io.InputStream) -> String,
): String? = input?.use(save)

/* --------------------------- R111 纯函数（JVM 可测） --------------------------- */

/** R111: 名称清洗——去掉路径分隔符与非法字符，空名兜底。import/export 文件名共用。 */
internal fun sanitizeName(name: String): String =
    name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "otg_file" }

/** import 落点对应的 MediaStore collection。 */
internal enum class ImportCollection { DOWNLOADS, MOVIES, PICTURES }
internal data class ImportPlan(val collection: ImportCollection, val relativePath: String)

/**
 * R111: destination → MediaStore collection + RELATIVE_PATH（纯函数）。
 * downloads 落 Download/AndAgentOtg/（现状不变）；movies/pictures 落 Movies/DCIM 下
 * AndAgentOtg/ 并写入 Video/Images collection，视频一步到位进相册。
 * 未知/缺省一律回落 downloads，保证旧行为不变。
 */
internal fun importPlan(destination: String?): ImportPlan = when (destination?.lowercase()) {
    "movies" -> ImportPlan(ImportCollection.MOVIES, "Movies/AndAgentOtg")
    "pictures" -> ImportPlan(ImportCollection.PICTURES, "DCIM/AndAgentOtg")
    else -> ImportPlan(ImportCollection.DOWNLOADS, "Download/AndAgentOtg")
}

/**
 * R111 review 必修 2：按 MIME 收口 collection 一致性——MediaProvider 会拒绝
 * 「collection 与 MIME 类型不匹配」的插入（如 octet-stream 塞进 Video/Images）。
 * mimeFor 不认识的扩展名（.mkv/.mov/.heic 等）落 octet-stream 时回落 DOWNLOADS，
 * 保证导入不失败；未知/缺省 destination 维持旧行为直接落 Download/AndAgentOtg。纯函数。
 */
internal fun resolveImportPlan(destination: String?, mime: String): ImportPlan {
    val plan = importPlan(destination)
    if (plan.collection == ImportCollection.DOWNLOADS) return plan
    val isVideo = mime.startsWith("video/")
    val isImage = mime.startsWith("image/")
    val matches = when (plan.collection) {
        ImportCollection.MOVIES -> isVideo
        ImportCollection.PICTURES -> isImage
        else -> true
    }
    return if (matches) plan else ImportPlan(ImportCollection.DOWNLOADS, "Download/AndAgentOtg")
}

/**
 * R111: auto_cleanup 最终口径——仅 direction=export 且源在白名单目录内才生效；
 * import 一律忽略（不删 U 盘文件）。纯函数，方便 JVM 单测锁定语义。
 */
internal fun shouldAutoCleanup(direction: String, autoCleanup: Boolean, canonPath: String, whitelistDirs: List<File>): Boolean =
    direction == "export" && autoCleanup && exportWhitelisted(canonPath, whitelistDirs)

/** 目录递归上传：本地文件（相对 local 目录的路径 + 字节数）。 */
internal data class UploadEntry(val localRel: String, val size: Long)
internal data class PlannedUpload(val localRel: String, val remoteParts: List<String>, val size: Long, val oversize: Boolean, val conflict: Boolean = false)

/**
 * R111: 目录递归路径规划 + 名称清洗 + 尺寸预算（纯函数）。
 * 远端路径 = baseParts + 本地相对路径分段（逐段清洗）；逐文件标 oversize 供跳过。
 *
 * R111 review 必修 2：名称清洗可能碰撞（a:b.mp4 与 a?b.mp4 都清洗为 a_b.mp4）——
 * 后写会覆盖先写；叠加 auto_cleanup 时两个源文件都会被删，静默丢数据。规划阶段
 * 检测远端路径冲突：第二组及以后撞名的整组标 oversize=false 但改标冲突——用
 * duplicated=true 语义（PlannedUpload 复用 oversize 槽位标「不可上传」，冲突即跳过，
 * 错误文案在 exportDirectory 用 remote 路径区分）。这里以 oversize=true 标记跳过，
 * 并在 [PlannedUpload.conflict] 单独记录冲突供文案区分。
 */
internal fun planRecursiveUpload(baseParts: List<String>, entries: List<UploadEntry>, maxBytes: Long): List<PlannedUpload> {
    val planned = entries.map { e ->
        val relParts = e.localRel.split('/').filter { it.isNotEmpty() }.map { sanitizeName(it) }
        PlannedUpload(e.localRel, baseParts + relParts, e.size, e.size > maxBytes, false)
    }
    val seen = HashSet<String>()
    return planned.map { p ->
        val key = p.remoteParts.joinToString("/").lowercase()
        if (key in seen) p.copy(oversize = true, conflict = true) else { seen += key; p }
    }
}


/**
 * R111 review 必修 1：逐文件白名单复检（纯函数）——递归遍历前只校验了顶层目录，
 * 目录内的符号链接可把单个文件指到白名单外。toRealPath 解析真实路径后必须仍在
 * 白名单目录内才允许读取；悬空链接/不存在/解析失败一律拒绝（File.canonicalFile 对
 * 悬空链接会返回链接自身路径导致放行，故必须用 NIO toRealPath）。防越界与循环。
 */
internal fun fileInsideWhitelist(dir: File, localRel: String, whitelistDirs: List<File>): Boolean =
    realPathInsideWhitelist(dir, localRel, whitelistDirs) != null

/**
 * R111 review 二轮必修 2：[fileInsideWhitelist] 的返回真实路径版——调用方在已验证的
 * toRealPath 产物上打开文件并做删除前身份复检（canonicalPath 仍等于 realPath 才删），
 * 收窄校验→打开之间的 TOCTOU 窗口。解析失败/越界返回 null。
 */
internal fun realPathInsideWhitelist(dir: File, localRel: String, whitelistDirs: List<File>): String? {
    val canon = runCatching { java.nio.file.Paths.get(dir.absolutePath, localRel).toRealPath().toString() }
        .getOrNull() ?: return null
    return if (exportWhitelisted(canon, whitelistDirs)) canon else null
}

class OtgTool(private val context: Context, override val name: String) : Tool {
    override val description = when (name) {
        "otg_status" -> "检测已挂载 OTG U 盘及 USB 设备和授权状态；safTrees 为 {uri,treeId,usable} 对象数组，recommendedRoot 可直接作为 root。"
        "otg_request" -> "申请 USB 设备权限或打开 SAF U 盘根目录授权选择器；permission=saf 成功返回 {granted,root,note}，root 可直接用于 otg_transfer/otg_file_ops；两种授权彼此独立。"
        "otg_transfer" -> "U 盘与手机存储间二进制文件复制。direction=import：U 盘文件→手机，destination 可指定 downloads（默认，Download/AndAgentOtg/）/movies（Movies/AndAgentOtg，MediaStore.Video，视频一步到位进相册）/pictures（DCIM/AndAgentOtg，MediaStore.Images）；direction=export：手机白名单目录文件→U 盘指定路径。流式复制，大小上限 max_mb。auto_cleanup 仅 export 生效：成功复制到 U 盘后删除手机侧白名单目录内的源文件（防 AndAgentOtg 中间目录膨胀），import 忽略。export 时 remote_path 以 / 结尾或传 remote_dir 即递归上传 local_path 整个目录，返回逐文件成功/失败列表 JSON。"
        else -> "通过 SAF 对已授权 OTG U 盘执行 list/read/write/delete/mkdir。root 用 otg_request(saf) 返回的 root 或 otg_status 的 recommendedRoot，path 为相对路径。"
    }
    override val parameters = when (name) {
        "otg_status" -> schemaOf()
        "otg_request" -> schemaOf("permission" to strParam("usb 或 saf"), required = listOf("permission"))
        "otg_transfer" -> schemaOf(
            "direction" to strParam("import（U 盘→手机）或 export（手机→U 盘）"),
            "root" to strParam("已授权 U 盘根目录 content:// tree URI（otg_request(saf) 返回的 root / otg_status 的 recommendedRoot 可直接使用）"),
            "remote_path" to strParam("U 盘根目录内相对路径（含文件名）；export 时以 / 结尾或配合 remote_dir 触发目录递归上传"),
            "local_path" to strParam("export 时手机侧源文件绝对路径（白名单：Download/AndAgentSsh、Download/AndAgentOtg、app_private ssh_downloads、otg_downloads）；目录递归时为本地目录；import 时省略"),
            "remote_dir" to strParam("export 目录递归时 U 盘目标目录（相对路径）；给定时 local_path 视为本地目录并整目录上传"),
            "destination" to strParam("import 落点：downloads（默认，Download/AndAgentOtg/）| movies（Movies/AndAgentOtg，MediaStore.Video，直接进相册）| pictures（DCIM/AndAgentOtg，MediaStore.Images）"),
            "auto_cleanup" to boolParam("仅 export 生效：成功复制到 U 盘后删除手机侧白名单目录内的源文件，默认 false；import 一律忽略"),
            "max_mb" to intParam("大小上限 MB，默认 512，钳制 1..4096（目录递归时逐文件受限）"),
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

    private data class TreeInfo(val uri: String, val treeId: String, val usable: Boolean)

    private fun status(): String {
        val volumes = storage.storageVolumes
        // R111 review 必修 3：挂载判定与 safGuard 同源（不依赖 uuid 非空）；uuid 仅用于 recommendedRoot 匹配。
        val mountedOtg = volumes.any { isMountedOtg(it.state, it.isEmulated, it.isRemovable) }
        val mountedOtgUuids = volumes.filter { isMountedOtg(it.state, it.isEmulated, it.isRemovable) }.mapNotNull { it.uuid }
        val persisted = context.contentResolver.persistedUriPermissions
        // R111: safTrees 结构化对象数组（uri/treeId/usable），一次调用即可拿到可直接使用的 root。
        val trees = SafTreeStore.list(context).map { uri ->
            val treeId = runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(uri)) }.getOrNull() ?: ""
            val usable = persisted.any { it.uri.toString() == uri && it.isReadPermission && it.isWritePermission }
            TreeInfo(uri, treeId, usable)
        }
        // R111 review 建议：只推荐当前挂载 U 盘匹配的 usable 树——旧设备的树会被 safGuard 拒绝，
        // 推荐出去只会浪费一轮工具调用。无匹配时留空并给引导。
        val recommendedRoot = trees.firstOrNull { it.usable && mountedOtgUuids.any { uuid -> otgTreeMatchesVolume(it.treeId, uuid) } }?.uri
            ?: ""
        return buildJsonObject {
            put("mountedOtg", mountedOtg)
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
            put("safTrees", buildJsonArray {
                trees.forEach { t -> add(buildJsonObject {
                    put("uri", t.uri)
                    put("treeId", t.treeId)
                    put("usable", t.usable)
                }) }
            })
            put("recommendedRoot", recommendedRoot)
        }.toString()
    }

    private suspend fun request(args: JsonObject): String { return when (args.strArg("permission")) {
        // R111: 复用 ExternalDataTool.pickTree() 的结构化结果，成功时返回可直接使用的 root；
        // 失败（取消/超时/选择器不可用）沿用原工具文本。saf_tree_pick 自身输出格式不变。
        // pickTree 内部 latch.await 阻塞，须与旧 ExternalDataTool.execute 一致放到 IO 线程。
        "saf" -> {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    ExternalDataTool(context, dummyPermissionBroker(), "saf_tree_pick").pickTree()
                }.getOrElse { TreePickResult.Rejected("ERROR: SAF 目录选择失败：${it.message ?: it.javaClass.simpleName}") }
            }
            when (outcome) {
                is TreePickResult.Granted -> buildJsonObject {
                    put("granted", true)
                    put("root", outcome.uri)
                    put("note", "root 可直接用于 otg_transfer / otg_file_ops 的 root 参数；批量视频 import 可加 destination=movies 直接进相册")
                }.toString()
                is TreePickResult.Rejected -> outcome.text
            }
        }
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

    /**
     * R111: 失败文案附当前已授权树列表 + 格式示例，让 AI 能直接复制一个可使用的 root，
     * 不必再盲猜 URI。otg_status 的 recommendedRoot / otg_request(saf) 的 root 也来自此列表。
     */
    private fun authorizedTreesHint(): String {
        val trees = SafTreeStore.list(context)
        val format = "根 URI 形如 content://com.android.externalstorage.documents/tree/xxxx%3A（xxxx 为卷 UUID）"
        return if (trees.isEmpty())
            "当前已授权树列表：[]；先调用 otg_request(permission=saf) 选择 U 盘根目录，把返回的 root 复制过来；$format"
        else
            "当前已授权树列表（可直接复制作为 root）：" + trees.joinToString(" | ") + "；$format"
    }

    /** SAF 授权/挂载校验链：otg_transfer 与 fileOps 共用；返回 null=校验通过，否则为错误文案。 */
    private fun safGuard(root: String): String? {
        val volumes = storage.storageVolumes.filter { isMountedOtg(it.state, it.isEmulated, it.isRemovable) }
        if (volumes.isEmpty()) return "ERROR: 未挂载 OTG U 盘；" + status()
        if (root !in SafTreeStore.list(context) || context.contentResolver.persistedUriPermissions.none { it.uri.toString() == root && it.isReadPermission && it.isWritePermission })
            return "ERROR: SAF 树未取得持久读写授权；先调用 otg_request(permission=saf)，选择 U 盘根目录；" + authorizedTreesHint()
        val tree = runCatching { Uri.parse(root) }.getOrNull() ?: return "ERROR: root URI 无效；" + authorizedTreesHint()
        if (tree.scheme != "content") return "ERROR: root 必须是 content:// URI；" + authorizedTreesHint()
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return "ERROR: root 不是 SAF 树 URI；" + authorizedTreesHint()
        if (volumes.none { otgTreeMatchesVolume(rootId, it.uuid) }) return "ERROR: root 必须是当前已挂载 U 盘的根目录树（可能授权的是旧 U 盘）；" + authorizedTreesHint() + "；" + status()
        return null
    }

    /** 按扩展名映射 MIME（SAF createDocument 用）；未知扩展名落 octet-stream。 */
    private fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "mp4" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        "webm" -> "video/webm"
        "avi" -> "video/x-msvideo"
        "heic" -> "image/heic"
        "heif" -> "image/heif"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "flac" -> "audio/flac"
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

    /** 手机侧导出源白名单目录（exportSource 与 auto_cleanup 共用）。 */
    private fun whitelistDirs(): List<File> = listOf(
        File(Environment.getExternalStorageDirectory(), "Download/AndAgentSsh"),
        File(Environment.getExternalStorageDirectory(), "Download/AndAgentOtg"),
        File(context.filesDir, "ssh_downloads"),
        File(context.filesDir, "otg_downloads"),
    )

    /** 手机侧导出源白名单路径 → 绝对 File；不在白名单返回 null（无 fallback，必修 3）。 */
    private fun exportSource(localPath: String): File? {
        val f = File(localPath).normalize()
        if (f.isAbsolute.not()) return null
        val canon = runCatching { f.canonicalPath }.getOrNull() ?: return null
        // 白名单外一律返回 null（走既有 ERROR 文案），不再放行任意可读文件。
        return if (exportWhitelisted(canon, whitelistDirs()) && f.isFile && f.canRead()) f else null
    }

    /** R101c: U 盘 ↔ 手机二进制复制（otg_transfer）。流式对拷，max_mb 上限，二进制安全。 */
    private suspend fun transfer(args: JsonObject): String {
        val direction = args.strArg("direction") ?: return "ERROR: 缺少 direction"
        if (direction !in setOf("import", "export")) return "ERROR: direction 必须是 import 或 export"
        val root = args.strArg("root") ?: return "ERROR: 缺少 root"
        val remotePath = args.strArg("remote_path") ?: return "ERROR: 缺少 remote_path"
        val remoteDir = args.strArg("remote_dir")
        // R111: remote_path 以 / 结尾或显式传 remote_dir → 整目录递归上传。
        val isDirUpload = remotePath.endsWith("/") || remoteDir != null
        val parts = otgPathParts(remotePath) ?: return "ERROR: remote_path 必须是 U 盘内相对路径，不能包含 .. 或反斜杠"
        if (!isDirUpload && parts.isEmpty()) return "ERROR: remote_path 必须包含文件名"
        val maxBytes = (args.intArg("max_mb") ?: 512).coerceIn(1, 4096).toLong() * 1_048_576
        val destination = args.strArg("destination")
        val autoCleanup = args.boolArg("auto_cleanup") ?: false
        safGuard(root)?.let { return it }
        val tree = Uri.parse(root)
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val resolver = context.contentResolver
        return try {
            when (direction) {
                "import" -> {
                    val docId = resolve(tree, rootId, parts, "write")
                        ?: return "ERROR: U 盘文件不存在：$remotePath"
                    val uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
                    val name = parts.last()
                    val mime = mimeFor(name)
                    // R111: destination=movies/pictures 走 MediaStore collection（见 saveImport）。
                    val plan = resolveImportPlan(destination, mime)
                    // R111 review 必修 1：API 26-28 直写公共目录（Movies/DCIM）需 WRITE_EXTERNAL_STORAGE；
                    // onPreDraw 时系统会把 WRITE 随 READ 一起授予（同 legacy 组），实测缺失时如实报错并给引导。
                    if (Build.VERSION.SDK_INT < 29 && plan.collection != ImportCollection.DOWNLOADS) {
                        val broker = this.broker
                        val ok = broker != null && broker.ensure(listOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE))
                        if (!ok) return "ERROR: 旧版 Android（<10）写相册需要「存储」写权限（WRITE_EXTERNAL_STORAGE）；请在系统设置授予后重试，或改用 destination=downloads（落 Download/AndAgentOtg/）"
                    }
                    // R103 必修 5：use 包住整个调用，成功/超限/异常三条路径都关闭输入流。
                    val saved = useOtgImportInput(resolver.openInputStream(uri)) { input ->
                        saveImport(resolver, plan, name, mime, input, maxBytes)
                    } ?: return "ERROR: 无法打开 U 盘文件"
                    "IMPORTED: $remotePath → $saved"
                }
                "export" -> {
                    val local = args.strArg("local_path") ?: return "ERROR: export 需要 local_path（手机侧源文件）"
                    if (isDirUpload) {
                        val baseParts = remoteDir?.let { otgPathParts(it) ?: return "ERROR: remote_dir 必须是 U 盘内相对路径" } ?: parts
                        exportDirectory(tree, rootId, resolver, local, baseParts, maxBytes, autoCleanup)
                    } else {
                        exportSingle(tree, rootId, resolver, local, parts, maxBytes, autoCleanup)
                    }
                }
                else -> "ERROR: 未知方向"
            }
        } catch (e: Exception) { "ERROR: OTG 复制失败：${e.message ?: e.javaClass.simpleName}" }
    }

    /** 单文件 export：手机白名单源文件 → U 盘指定路径（现状行为，另加 auto_cleanup）。 */
    private fun exportSingle(
        tree: Uri, rootId: String, resolver: android.content.ContentResolver,
        local: String, parts: List<String>, maxBytes: Long, autoCleanup: Boolean,
    ): String {
        val name = parts.last()
        val src = exportSource(local)
            ?: return "ERROR: local_path 必须是白名单目录内的可读文件（Download/AndAgentSsh|AndAgentOtg、app_private ssh_downloads|otg_downloads）：$local"
        if (src.length() > maxBytes) return "ERROR: 文件 ${src.length()} 字节超过 ${maxBytes / 1_048_576}MB 上限"
        val parentId = ensureDirChain(tree, rootId, resolver, parts.dropLast(1))
            ?: return "ERROR: 无法创建 U 盘目录"
        val docId = child(tree, parentId, name) ?: run {
            val created = DocumentsContract.createDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, parentId), mimeFor(name), name)
                ?: return "ERROR: 无法在 U 盘创建文件 $name"
            DocumentsContract.getDocumentId(created)
        }
        val dstUri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
        // R111 review 必修 2：child() 命中已存在同名文件时旧实现直接 "wt" 截断，写一半超限/中断会毁掉原文件。
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
        val copied = copyToExistingAtomic(resolver, src, tree, parentUri, docId, name, maxBytes)
            ?: return "ERROR: 无法写入 U 盘文件"
        if (shouldAutoCleanup("export", autoCleanup, src.canonicalPath, whitelistDirs())) {
            // review 建议：删源失败如实标注（copy 成功但 cleanup 失败仍算成功传输）。
            val cleanupNote = if (src.delete()) "" else "（cleanup 失败：源文件未能删除）"
            return "EXPORTED: $local → ${parts.joinToString("/")} ($copied bytes)$cleanupNote"
        }
        return "EXPORTED: $local → ${parts.joinToString("/")} ($copied bytes)"
    }

    /**
     * R111 review 必修 2：写入可能已存在的 U 盘目标时保证原文件不被半写破坏。
     * API 29+（deleteDocument/renameDocument 可用）：新建临时文档以 "w"（流式打开零截断）
     * 完整写入，成功后先删旧目标再 renameDocument 顶替（二轮 review 必修 1：删除失败或
     * rename 失败/返回 null 一律如实报失败，绝不先截断毁掉原文件；删除成功而 rename 失败
     * 时会把临时文档改回目标名止损）。任何失败路径原目标要么完整保留，要么已被以完整
     * 内容的临时文档顶替，不会出现 0 字节残骸。
     * API 26-28 无 renameDocument，退回直接 "wt" 覆写（无目标存在性检查的前提下，
     * 调用方先 child() 探测，未存在时等价新建，风险与旧实现一致）。
     * 注意 renameDocument 不改 MIME 元数据（保持 createDocument 传入的 mimeFor(name)）。
     */
    private fun copyToExistingAtomic(
        resolver: android.content.ContentResolver, src: File,
        tree: Uri, parentUri: Uri, docId: String, name: String, maxBytes: Long,
    ): Long? {
        val targetUri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
        if (Build.VERSION.SDK_INT < 29) return copyFileToUri(resolver, src, targetUri, maxBytes)
        val mime = mimeFor(name)
        val created = runCatching {
            DocumentsContract.createDocument(resolver, parentUri, mime, ".$name.otgtmp")
        }.getOrNull() ?: return null
        val tmpUri = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getDocumentId(created))
        fun dropTmp() { runCatching { DocumentsContract.deleteDocument(resolver, tmpUri) } }
        val copied = try {
            copyFileToUri(resolver, src, tmpUri, maxBytes, mode = "w")
        } catch (e: Exception) {
            dropTmp(); return null
        }
        if (copied == null) { dropTmp(); return null }
        // R111 review 二轮必修 1：renameDocument 必须先成功才允许碰目标——旧实现先 0 字节截断
        // 再 rename，rename 失败时目标已被毁；且 renameDocument 返回 null（提供方未替换）也被
        // isFailure 吞掉虚报成功。正确顺序：rename 成功（返回非 null 的新 URI）→ 旧目标已被
        // 提供方移除/替换，此时临时文档以目标名顶替，无窗口。renameDocument 遇同名冲突时
        // 行为由提供方决定（ExternalStorageProvider 会带 "(1)" 后缀），故先删旧目标（此时
        // 临时文档已在提供方一侧持有完整内容，删除旧目标最多丢失旧版本——正是覆写的本意），
        // 再 rename；任一步失败原目标不动，只清临时文档。
        val deletedOld = runCatching {
            // 三轮 review 必修 2：child() 只按名匹配，同名目录会命中并把整个目录树连内容
            // deleteDocument 掉。删除旧目标前必须确认它是普通文件（MIME != DIR 且非目录 flag）；
            // 命中目录时报失败返回，原目标（目录）不动。递归上传清洗后的文件名与已有目录名
            // 相撞时同样被此闸拦下。
            val isDir = runCatching {
                resolver.query(targetUri, arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val mime = c.getString(0)
                        mime == DocumentsContract.Document.MIME_TYPE_DIR
                    } else false
                } ?: false
            }.getOrDefault(false)
            if (isDir) return null
            DocumentsContract.deleteDocument(resolver, targetUri)
        }.getOrDefault(false)
        val renamed = runCatching { DocumentsContract.renameDocument(resolver, tmpUri, name) }.getOrNull()
        if (!deletedOld || renamed == null) {
            // 三轮 review 必修 1：旧目标已删而 rename 失败时，临时文档是唯一完整副本——
            // 绝不 dropTmp()（否则新旧副本双丢）。止损重试 rename 回目标名；仍失败则保留
            // .name.otgtmp 临时文档（数据可手动恢复），如实报失败。旧目标未删（deletedOld=false）
            // 时原文件完好，临时文档可直接清理。
            if (deletedOld) {
                val rescue = runCatching { DocumentsContract.renameDocument(resolver, tmpUri, name) }.getOrNull()
                if (rescue != null) return null // 顶替成功但首删/首改失败过——报失败让调用方如实转述，不虚报成功
            } else {
                dropTmp()
            }
            return null
        }
        return copied
    }

/**
 * R111: export 目录递归——local 目录内逐文件 createDocument（目录逐级创建），
 * 单文件仍受 max_mb 限制，返回逐文件成功/失败列表 JSON。
 */
private fun exportDirectory(
    tree: Uri, rootId: String, resolver: android.content.ContentResolver,
    local: String, baseParts: List<String>, maxBytes: Long, autoCleanup: Boolean,
): String {
    val dir = File(local).normalize()
    if (!dir.isDirectory || !dir.canRead()) return "ERROR: 目录递归时 local_path 必须是白名单目录内的可读目录：$local"
    val canonDir = runCatching { dir.canonicalPath }.getOrNull() ?: return "ERROR: 路径无效"
    // review 必修 1：目录入口允许等于白名单根目录（导出整个 AndAgentOtg 是合法用法）。
    if (!exportWhitelistedDir(canonDir, whitelistDirs())) return "ERROR: local_path 目录不在白名单内：$local"
    val entries = collectUploadEntries(dir)
    val plans = planRecursiveUpload(baseParts, entries, maxBytes)
    val dirs = whitelistDirs()
    val dirCache = HashMap<String, String>()
    val results = mutableListOf<Pair<Boolean, String>>()
    for (p in plans) {
        results += runCatching {
            when {
                // R111 review 必修 1：白名单目录内的符号链接可指向目录外，逐文件复检真实路径。
                !fileInsideWhitelist(dir, p.localRel, dirs) -> false to "源文件真实路径越出白名单（疑似符号链接），已跳过"
                // R111 review 必修 2：名称清洗碰撞（如 a:b.mp4 与 a?b.mp4 同落 a_b.mp4），撞名组跳过防覆盖+auto_cleanup 误删。
                p.conflict -> false to "目标路径与同批其他文件清洗后冲突（远端已存在同路径文件），已跳过：${p.remoteParts.joinToString("/")}"
                p.oversize -> false to "超过 ${maxBytes / 1_048_576}MB 上限（已跳过）"
                else -> {
                    val src = File(dir, p.localRel)
                    if (!src.isFile || !src.canRead()) return@runCatching false to "源文件不可读"
                    // R111 review 二轮必修 2：toRealPath 校验通过后再按原路径 File(...) 打开存在
                    // TOCTOU 窗口（公共目录文件可在两步之间被换成指向白名单外的符号链接）。
                    // 改为在已验证的真实路径上打开与删源；首检仍保留作快速失败。
                    if (!fileInsideWhitelist(dir, p.localRel, dirs)) return@runCatching false to "源文件真实路径越出白名单（疑似符号链接），已跳过"
                    // 改为直接在已验证的 canonical 真实路径上打开：realPathInsideWhitelist 返回的
                    // realPath 即 toRealPath 的产物，删除前再以 canonicalPath == realPath 复检身份。
                    val realPath = realPathInsideWhitelist(dir, p.localRel, dirs)
                        ?: return@runCatching false to "源文件真实路径越出白名单（疑似符号链接），已跳过"
                    val srcFile = File(realPath)
                    if (!srcFile.isFile || !srcFile.canRead()) return@runCatching false to "源文件不可读"
                    val fileParts = p.remoteParts
                    val fileName = sanitizeName(fileParts.last())
                    // R111 review 必修 3：单文件异常就地捕获记为失败，不中断整批（防 auto_cleanup 半程删除后丢结果）。
                    val parentId = ensureDirChain(tree, rootId, resolver, fileParts.dropLast(1), dirCache)
                        ?: return@runCatching false to "无法创建 U 盘目录"
                    val docId = child(tree, parentId, fileName) ?: run {
                        val created = DocumentsContract.createDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, parentId), mimeFor(fileName), fileName)
                            ?: return@run null
                        DocumentsContract.getDocumentId(created)
                    } ?: return@runCatching false to "无法在 U 盘创建文件"
                    val parentUri = DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
                    // R111 review 必修 2：与 exportSingle 同一套原子覆写（临时文档 + renameDocument），防同名目标半写损坏。
                    val copied = copyToExistingAtomic(resolver, srcFile, tree, parentUri, docId, fileName, maxBytes)
                        ?: return@runCatching false to "写入 U 盘失败"
                    // 二轮必修 2：cleanup 删源前复检身份——canonicalPath 仍等于校验过的 realPath
                    // 才删，防校验后文件被换成同名符号链接时误删链接外的目标文件。
                    if (shouldAutoCleanup("export", autoCleanup, realPath, dirs) &&
                        runCatching { srcFile.canonicalPath }.getOrNull() == realPath) {
                        // review 建议：删源失败如实标注（copy 成功但 cleanup 失败仍算成功传输）。
                        if (srcFile.delete()) return@runCatching true to "$copied bytes"
                        return@runCatching true to "$copied bytes（cleanup 失败：源文件未能删除）"
                    }
                    true to "$copied bytes"
                }
            }
        }.getOrElse { e -> false to "失败：${e.message ?: e.javaClass.simpleName}" }
    }
    return buildJsonObject {
        put("direction", "export")
        put("directory", true)
        put("local_dir", local)
        put("remote_dir", baseParts.joinToString("/"))
        put("exported", results.count { it.first })
        put("failed", results.count { !it.first })
        put("files", buildJsonArray {
            plans.forEachIndexed { i, p ->
                add(buildJsonObject {
                    put("local", p.localRel)
                    put("remote", p.remoteParts.joinToString("/"))
                    put("ok", results[i].first)
                    put("detail", results[i].second)
                })
            }
        })
    }.toString()
}

    /** 逐级确保 U 盘目录存在并返回末级 docId；cache 复用已建目录避免重复查询。 */
    private fun ensureDirChain(
        tree: Uri, rootId: String, resolver: android.content.ContentResolver,
        dirParts: List<String>, cache: MutableMap<String, String> = HashMap(),
    ): String? {
        var parentId = rootId
        val acc = mutableListOf<String>()
        for (dir in dirParts) {
            acc += dir
            val key = acc.joinToString("/")
            val cached = cache[key]
            if (cached != null) { parentId = cached; continue }
            val existing = child(tree, parentId, dir)
            val id = if (existing != null) existing else {
                val created = DocumentsContract.createDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, parentId), DocumentsContract.Document.MIME_TYPE_DIR, dir)
                    ?: return null
                DocumentsContract.getDocumentId(created)
            }
            cache[key] = id
            parentId = id
        }
        return parentId
    }

    /** 流式复制本地文件到 U 盘 document URI；超限/打不开返回 null（二进制安全，use 关流）。mode=w 用于临时文档零破坏写入。 */
    private fun copyFileToUri(resolver: android.content.ContentResolver, src: File, dstUri: Uri, maxBytes: Long, mode: String = "wt"): Long? {
        val output = resolver.openOutputStream(dstUri, mode) ?: return null
        var total = 0L
        var exceeded = false
        output.use { out ->
            src.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (total + n > maxBytes) { exceeded = true; break }
                    out.write(buffer, 0, n)
                    total += n
                }
            }
        }
        return if (exceeded) null else total
    }

    /**
     * import 落手机 MediaStore（IS_PENDING 原子发布，参照 ssh_download）。
     * R111: 按 destination 选 collection 与 RELATIVE_PATH；movies/pictures 发布后同样
     * 回读实际文件名拼绝对路径返回，视频可一步到位进相册。
     */
    private fun saveImport(resolver: android.content.ContentResolver, plan: ImportPlan, name: String, mime: String, input: java.io.InputStream, maxBytes: Long): String {
        val relPath = plan.relativePath
        val safeName = sanitizeName(name)
        if (Build.VERSION.SDK_INT < 29) {
            // R111 review 必修 1：旧系统直写公共目录需 WRITE_EXTERNAL_STORAGE；调用方已确保，
            // 缺权限时明确报错（不再是模糊的 IOException）。写完触发媒体扫描保证相册立即可见。
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
            runCatching { android.media.MediaScannerConnection.scanFile(this@OtgTool.context, arrayOf(target.absolutePath), arrayOf(mime), null) }
            return target.absolutePath
        }
        val collectionUri = when (plan.collection) {
            ImportCollection.DOWNLOADS -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            ImportCollection.MOVIES -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            ImportCollection.PICTURES -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collectionUri, values) ?: error("无法创建下载文件")
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
            // R111 review 建议：发布失败的行保持 pending（对 owner app 仍可见），返回值如实标注。
            val published = resolver.update(uri, values, null, null) > 0
            val actualName = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: safeName
            val suffix = if (published) "" else "（注意：IS_PENDING 发布未确认，相册可能暂不可见）"
            return "/sdcard/$relPath/$actualName ($total bytes)$suffix"
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
        // R111: 与 otg_transfer 共用同一校验链（含自文档化错误文案），消除重复判断。
        safGuard(root)?.let { return it }
        val tree = Uri.parse(root)
        val rootId = DocumentsContract.getTreeDocumentId(tree)
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

/**
 * R111 review 二轮必修：递归遍历排除符号链接目录（onEnter 阶段拒绝）——目录符号链接
 * 可指回源树形成环，eager toList() 会在进入逐文件守卫之前无限遍历。符号链接目录整体
 * 跳过（内容不再收集）；符号链接文件仍会进入列表，由逐文件 toRealPath 守卫拒绝。
 * 顶层 internal 纯函数：OtgToolsTest 直接以顶层符号调用（不可为 OtgTool 成员）。
 */
internal fun collectUploadEntries(dir: File): List<UploadEntry> =
    dir.walkTopDown()
        .onEnter { d -> d == dir || runCatching { !java.nio.file.Files.isSymbolicLink(d.toPath()) }.getOrDefault(false) }
        .filter { it.isFile }
        .map { f -> UploadEntry(f.relativeTo(dir).path.replace(File.separatorChar, '/'), f.length()) }
        .toList()
