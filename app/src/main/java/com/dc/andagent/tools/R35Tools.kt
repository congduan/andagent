package com.dc.andagent.tools

import android.Manifest
import android.content.ContentProviderOperation
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.CallLog
import android.provider.ContactsContract
import androidx.core.content.FileProvider
import com.dc.andagent.bridge.PermissionBroker
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * R35: 8 high-value capability gaps. All paths use platform APIs only — no
 * GMS, no Play Services model download, no accessibility service, no root.
 *
 *  - contacts_add / call_log_read : ContactsContract + CallLog (runtime perms)
 *  - app_uninstall / apk_extract  : PackageManager + system installer / FileProvider
 *  - qr_generate / qr_scan        : vendored ZXing core (pure Java, zero perms)
 *  - sensor_record                : SensorManager continuous sampling -> CSV
 *  - file_encrypt                 : AES-256-GCM + PBKDF2 (javax.crypto, zero perms)
 */

val R35_TOOLS = listOf(
    "contacts_add", "call_log_read", "app_uninstall", "apk_extract",
    "qr_generate", "qr_scan", "sensor_record", "file_encrypt",
)

/* ------------------------------ pure logic ------------------------------ */

/** Guards destructive app operations. Pure Kotlin so it is unit-testable. */
object AppGuard {
    private val PROTECTED = setOf(
        "android", "com.dc.andagent",
        "com.android.systemui", "com.android.settings", "com.android.phone",
        "com.android.vending", "com.google.android.gms",
        "com.google.android.gsf", "com.google.android.permissioncontroller",
        "com.termux",
        "com.android.launcher3", "com.android.inputmethod.latin",
    )
    private val PROTECTED_PREFIXES = listOf("android.", "com.android.server.", "com.android.providers.")
    private val PACKAGE_RE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

    /** Also used as the shell-injection guard before interpolating into `pm`. */
    fun isSafePackageName(pkg: String?): Boolean = pkg != null && PACKAGE_RE.matches(pkg)

    fun isProtected(pkg: String): Boolean =
        pkg in PROTECTED || PROTECTED_PREFIXES.any { pkg.startsWith(it) }
}

/** CSV escaping/serialisation for sensor_record (pure, unit-testable). */
object SensorCsv {
    fun escape(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' })
            "\"" + value.replace("\"", "\"\"") + "\"" else value

    fun build(header: List<String>, rows: List<List<String>>): String = buildString {
        append(header.joinToString(",") { escape(it) }).append('\n')
        rows.forEach { append(it.joinToString(",") { escape(it) }).append('\n') }
    }
}

/** QR encode/decode via ZXing core. Works on the JVM too (unit tests). */
object QrCodec {
    data class QrResult(val text: String, val format: String)

    fun encode(content: String, size: Int, ec: ErrorCorrectionLevel = ErrorCorrectionLevel.M): BitMatrix {
        require(content.isNotEmpty()) { "二维码内容不能为空" }
        require(size in 64..2048) { "二维码尺寸需在 64..2048 之间" }
        val hints = mapOf<EncodeHintType, Any>(
            EncodeHintType.ERROR_CORRECTION to ec,
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to 2,
        )
        return QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
    }

    /** Simple ASCII rendering, handy for tests and text-only clients. */
    fun renderAscii(matrix: BitMatrix, step: Int = 2): String = buildString {
        var y = 0
        while (y < matrix.height) {
            var x = 0
            while (x < matrix.width) {
                append(if (matrix.get(x, y)) '#' else ' ')
                x += step
            }
            append('\n')
            y += step
        }
    }

    /** Decode ARGB pixels (e.g. Bitmap.getPixels) into text + barcode format. */
    fun decode(pixels: IntArray, width: Int, height: Int): QrResult {
        val source = RGBLuminanceSource(width, height, pixels)
        val binary = BinaryBitmap(HybridBinarizer(source))
        val hints = mapOf<DecodeHintType, Any>(
            DecodeHintType.POSSIBLE_FORMATS to listOf(
                BarcodeFormat.QR_CODE, BarcodeFormat.DATA_MATRIX, BarcodeFormat.AZTEC,
                BarcodeFormat.PDF_417, BarcodeFormat.CODE_128, BarcodeFormat.CODE_39,
                BarcodeFormat.CODE_93, BarcodeFormat.EAN_13, BarcodeFormat.EAN_8,
                BarcodeFormat.UPC_A, BarcodeFormat.UPC_E, BarcodeFormat.ITF,
                BarcodeFormat.CODABAR,
            ),
            DecodeHintType.TRY_HARDER to true,
            DecodeHintType.CHARACTER_SET to "UTF-8",
        )
        val result = MultiFormatReader().decode(binary, hints)
        return QrResult(result.text, result.barcodeFormat.name)
    }
}

/** AES-256-GCM file encryption with a PBKDF2-derived key (pure JVM, testable). */
object FileCrypto {
    private const val MAGIC = "ANDAGENT1"
    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val ITERATIONS = 65_536
    private const val KEY_BITS = 256
    private const val TAG_BITS = 128

    fun isEncrypted(blob: ByteArray): Boolean =
        blob.size >= MAGIC.length && String(blob, 0, MAGIC.length, Charsets.US_ASCII) == MAGIC

    fun encrypt(plain: ByteArray, password: CharArray): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(SALT_LEN).also { random.nextBytes(it) }
        val iv = ByteArray(IV_LEN).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(TAG_BITS, iv))
        val body = cipher.doFinal(plain)
        return MAGIC.toByteArray(Charsets.US_ASCII) + salt + iv + body
    }

    fun decrypt(blob: ByteArray, password: CharArray): ByteArray {
        require(isEncrypted(blob)) { "文件不是 AndAgent 加密格式（缺少魔数）" }
        val head = MAGIC.length
        require(blob.size >= head + SALT_LEN + IV_LEN + 16) { "密文过短或已损坏" }
        val salt = blob.copyOfRange(head, head + SALT_LEN)
        val iv = blob.copyOfRange(head + SALT_LEN, head + SALT_LEN + IV_LEN)
        val body = blob.copyOfRange(head + SALT_LEN + IV_LEN, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(body)
    }

    private fun deriveKey(password: CharArray, salt: ByteArray): javax.crypto.SecretKey {
        val spec = PBEKeySpec(password, salt, ITERATIONS, KEY_BITS)
        return SecretKeySpec(
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded,
            "AES",
        )
    }
}

/* -------------------------------- tool ---------------------------------- */

class R35Tool(
    private val context: Context,
    private val permissions: PermissionBroker,
    override val name: String,
) : Tool {
    override val description: String = when (name) {
        "contacts_add" -> "新建联系人（姓名必填，可带电话/邮箱/备注）。优先用 WRITE_CONTACTS 静默写入；未授权时降级为打开系统联系人编辑页并预填信息，由用户手动保存。"
        "call_log_read" -> "只读查询通话记录：按号码/类型/天数过滤，返回号码、类型、时间、时长；需 READ_CALL_LOG，隐私敏感，仅查询不修改。"
        "app_uninstall" -> "卸载已安装应用：打开系统卸载确认页由用户确认（普通应用无静默卸载权限）。破坏性：必须 confirm=true；系统组件与 AndAgent 自身在保护名单内。"
        "apk_extract" -> "提取已安装应用的 APK 到 Downloads/AndAgent，可选 share=true 打开系统分享面板。App Bundle 拆包应用仅能导出 base.apk。"
        "qr_generate" -> "把文本生成二维码 PNG（纯离线 ZXing，零权限），保存到 Pictures/AndAgent 并在工具卡片中展示缩略图。"
        "qr_scan" -> "识别图片中的二维码/条码（纯离线 ZXing），source 支持本地路径、content:// URI、相册 id 或 base64。"
        "sensor_record" -> "连续采样传感器（加速度计/陀螺仪等）指定时长，导出 CSV 到 Downloads/AndAgent，返回预览行。"
        "file_encrypt" -> "用密码加密/解密小文件（AES-256-GCM + PBKDF2，密码不存储）。op=encrypt|decrypt；覆盖已有输出需 confirm=true。"
        else -> "R35 工具"
    }

    override val parameters: JsonObject = when (name) {
        "contacts_add" -> schemaOf(
            "name" to strParam("联系人姓名（必填）"),
            "phone" to strParam("电话号码"),
            "email" to strParam("邮箱"),
            "note" to strParam("备注"),
            required = listOf("name"),
        )
        "call_log_read" -> schemaOf(
            "number" to strParam("按号码过滤（模糊匹配）"),
            "type" to strParam("通话类型：incoming/outgoing/missed/blocked/rejected"),
            "days" to intParam("仅查最近 N 天，默认 7，上限 90"),
            "limit" to intParam("返回条数，默认 20，上限 100"),
        )
        "app_uninstall" -> schemaOf(
            "package" to strParam("应用包名"),
            "action" to strParam("uninstall（默认）|disable|enable"),
            "confirm" to boolParam("必须设为 true 才会执行"),
            required = listOf("package", "confirm"),
        )
        "apk_extract" -> schemaOf(
            "package" to strParam("应用包名"),
            "share" to boolParam("true 时打开系统分享面板"),
            "confirm" to boolParam("目标已存在时须为 true 才覆盖"),
            required = listOf("package"),
        )
        "qr_generate" -> schemaOf(
            "text" to strParam("二维码内容（文本/链接）"),
            "size" to intParam("像素尺寸，默认 512，范围 64..2048"),
            "ec" to strParam("纠错等级 L/M/Q/H，默认 M"),
            required = listOf("text"),
        )
        "qr_scan" -> schemaOf(
            "source" to strParam("本地图片路径 / content:// URI / 相册 id / base64"),
            required = listOf("source"),
        )
        "sensor_record" -> schemaOf(
            "sensor" to strParam("传感器：accelerometer（默认）/gyroscope/magnetometer/light/pressure/gravity/linear_acceleration/rotation_vector"),
            "duration_ms" to intParam("采样时长毫秒，默认 3000，范围 200..60000"),
            "hz" to intParam("采样频率，默认 50，范围 1..200"),
        )
        "file_encrypt" -> schemaOf(
            "path" to strParam("输入文件绝对路径"),
            "password" to strParam("密码（本次使用，不存储）"),
            "op" to strParam("encrypt（默认）|decrypt"),
            "output" to strParam("输出路径，省略时 encrypt 追加 .enc / decrypt 去掉 .enc"),
            "confirm" to boolParam("输出文件已存在时须为 true"),
            required = listOf("path", "password"),
        )
        else -> schemaOf()
    }

    /** Write / external-action tools require explicit user confirmation. */
    override val destructive: Boolean = name in setOf(
        "contacts_add", "app_uninstall", "apk_extract", "file_encrypt",
    )

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        runCatching { dispatch(args) }
            .getOrElse { "ERROR: $name 执行失败：${it.message ?: it.javaClass.simpleName}" }
    }

    private suspend fun dispatch(args: JsonObject): String = when (name) {
        "contacts_add" -> contactsAdd(args)
        "call_log_read" -> callLogRead(args)
        "app_uninstall" -> appUninstall(args)
        "apk_extract" -> apkExtract(args)
        "qr_generate" -> qrGenerate(args)
        "qr_scan" -> qrScan(args)
        "sensor_record" -> sensorRecord(args)
        "file_encrypt" -> fileEncrypt(args)
        else -> "ERROR: 未知 R35 工具 $name"
    }

    /* --------------------------- contacts_add ---------------------------- */

    private suspend fun contactsAdd(args: JsonObject): String {
        val display = args.strArg("name") ?: return "ERROR: 缺少 name（联系人姓名）"
        val phone = args.strArg("phone")
        val email = args.strArg("email")
        val note = args.strArg("note")
        if (!permissions.ensure(listOf(Manifest.permission.WRITE_CONTACTS))) {
            return openContactInsert(display, phone, email, note)
        }
        val resolver = context.contentResolver
        // Atomic write: RawContact + data rows in one applyBatch, so a mid-way
        // failure leaves no half-built contact behind.
        val batch = mutableListOf<ContentProviderOperation>()
        batch += ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
            .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null as String?)
            .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null as String?)
            .build()
        fun dataInsert(mime: String, block: ContentProviderOperation.Builder.() -> Unit) {
            batch += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, mime)
                .apply(block)
                .build()
        }
        dataInsert(ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE) {
            withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, display)
        }
        if (phone != null) dataInsert(ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE) {
            withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone)
            withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
        }
        if (email != null) dataInsert(ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE) {
            withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, email)
            withValue(ContactsContract.CommonDataKinds.Email.TYPE, ContactsContract.CommonDataKinds.Email.TYPE_OTHER)
        }
        if (note != null) dataInsert(ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE) {
            withValue(ContactsContract.CommonDataKinds.Note.NOTE, note)
        }
        val results = runCatching { resolver.applyBatch(ContactsContract.AUTHORITY, ArrayList(batch)) }
            .getOrElse {
                return "ERROR: 创建联系人失败（系统通讯录拒绝写入）：${it.message ?: it.javaClass.simpleName}"
            }
        val rawUri = results.firstOrNull()?.uri
            ?: return "ERROR: 创建联系人失败（未返回联系人记录）"
        val rawId = rawUri.lastPathSegment?.toLongOrNull()
            ?: return "ERROR: 创建联系人失败（无法取得联系人 id）"
        return buildJsonObject {
            put("written", true)
            put("name", display)
            put("phone", phone ?: "")
            put("email", email ?: "")
            put("rawContactId", rawId)
            put("uri", rawUri.toString())
        }.toString()
    }

    /** 降级路径：无 WRITE_CONTACTS 时打开系统联系人编辑页（无需权限）。 */
    private fun openContactInsert(display: String, phone: String?, email: String?, note: String?): String {
        val intent = Intent(ContactsContract.Intents.Insert.ACTION).apply {
            type = ContactsContract.RawContacts.CONTENT_TYPE
            putExtra(ContactsContract.Intents.Insert.NAME, display)
            phone?.let { putExtra(ContactsContract.Intents.Insert.PHONE, it) }
            email?.let { putExtra(ContactsContract.Intents.Insert.EMAIL, it) }
            note?.let { putExtra(ContactsContract.Intents.Insert.NOTES, it) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            "OPENED: 未授予 WRITE_CONTACTS，已打开系统联系人编辑页并预填「$display」，请手动保存"
        }.getOrElse { "ERROR: 无法打开系统联系人编辑页：${it.message ?: "设备无通讯录应用"}" }
    }

    /* -------------------------- call_log_read ---------------------------- */

    private suspend fun callLogRead(args: JsonObject): String {
        if (!permissions.ensure(listOf(Manifest.permission.READ_CALL_LOG)))
            return "ERROR: 通话记录读取权限未授予（READ_CALL_LOG）；本工具仅只读查询，不会修改记录"
        val days = (args.intArg("days") ?: 7).coerceIn(1, 90)
        val since = System.currentTimeMillis() - days * 86_400_000L
        val conditions = mutableListOf("${CallLog.Calls.DATE} >= ?")
        val values = mutableListOf(since.toString())
        args.strArg("number")?.let {
            conditions += "${CallLog.Calls.NUMBER} LIKE ?"
            values += "%$it%"
        }
        args.strArg("type")?.let { raw ->
            val code = when (raw.lowercase()) {
                "incoming" -> CallLog.Calls.INCOMING_TYPE
                "outgoing" -> CallLog.Calls.OUTGOING_TYPE
                "missed" -> CallLog.Calls.MISSED_TYPE
                "blocked" -> CallLog.Calls.BLOCKED_TYPE
                "rejected" -> CallLog.Calls.REJECTED_TYPE
                else -> return "ERROR: type 仅支持 incoming/outgoing/missed/blocked/rejected"
            }
            conditions += "${CallLog.Calls.TYPE} = ?"
            values += code.toString()
        }
        val limit = (args.intArg("limit") ?: 20).coerceIn(1, 100)
        val rows = buildJsonArray {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(
                    CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME,
                    CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION,
                ),
                conditions.joinToString(" AND "), values.toTypedArray(),
                "${CallLog.Calls.DATE} DESC",
            )?.use { c ->
                var count = 0
                while (c.moveToNext() && count++ < limit) {
                    add(buildJsonObject {
                        put("id", c.getLong(0))
                        put("number", c.getString(1) ?: "")
                        put("name", c.getString(2) ?: "")
                        put("type", callTypeName(c.getInt(3)))
                        put("date", c.getLong(4))
                        put("duration_s", c.getLong(5))
                    })
                }
            }
        }
        return buildJsonObject {
            put("readonly", true)
            put("days", days)
            put("count", rows.size)
            put("calls", rows)
        }.toString()
    }

    private fun callTypeName(code: Int): String = when (code) {
        CallLog.Calls.INCOMING_TYPE -> "incoming"
        CallLog.Calls.OUTGOING_TYPE -> "outgoing"
        CallLog.Calls.MISSED_TYPE -> "missed"
        CallLog.Calls.BLOCKED_TYPE -> "blocked"
        CallLog.Calls.REJECTED_TYPE -> "rejected"
        else -> "other($code)"
    }

    /* -------------------------- app_uninstall ---------------------------- */

    private suspend fun appUninstall(args: JsonObject): String {
        val pkg = args.strArg("package") ?: return "ERROR: 缺少 package（应用包名）"
        if (!AppGuard.isSafePackageName(pkg)) return "ERROR: 包名格式非法：$pkg"
        val action = args.strArg("action") ?: "uninstall"
        if (action !in setOf("uninstall", "disable", "enable"))
            return "ERROR: action 仅支持 uninstall/disable/enable"
        if (args.boolArg("confirm") != true)
            return "ERROR: 此操作会改变已安装应用状态，请显式传 confirm=true 确认"
        if (AppGuard.isProtected(pkg))
            return "ERROR: $pkg 在保护名单内（系统组件 / AndAgent 自身 / 工具链），拒绝执行 $action"
        runCatching { context.packageManager.getApplicationInfo(pkg, 0) }
            .getOrElse { return "ERROR: 未找到已安装应用：$pkg" }
        if (action != "uninstall") {
            return "ERROR: 普通应用无法 disable/enable 其他应用；请在系统设置 → 应用 中操作"
        }
        return runCatching {
            context.startActivity(
                Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:$pkg"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            buildJsonObject {
                put("action", action)
                put("package", pkg)
                put("message", "已打开系统卸载确认页，请手动确认")
            }.toString()
        }.getOrElse { "ERROR: 无法打开卸载确认页：${it.message}" }
    }

    /* --------------------------- apk_extract ----------------------------- */

    private fun apkExtract(args: JsonObject): String {
        val pkg = args.strArg("package") ?: return "ERROR: 缺少 package（应用包名）"
        if (!AppGuard.isSafePackageName(pkg)) return "ERROR: 包名格式非法：$pkg"
        val info = runCatching { context.packageManager.getApplicationInfo(pkg, 0) }
            .getOrElse { return "ERROR: 未找到已安装应用：$pkg" }
        val source = File(info.sourceDir)
        if (!source.isFile || !source.canRead())
            return "ERROR: 无法读取该应用的 APK（可能被系统保护）：${info.sourceDir}"
        val version = runCatching { context.packageManager.getPackageInfo(pkg, 0).versionName }
            .getOrNull() ?: "unknown"
        val safeVersion = version.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "AndAgent")
        if (!dir.exists() && !dir.mkdirs()) return "ERROR: 无法创建下载目录：${dir.path}"
        val dest = File(dir, "${pkg}_${safeVersion}.apk")
        if (dest.exists() && args.boolArg("confirm") != true)
            return "ERROR: 目标文件已存在 ${dest.path}，覆盖请传 confirm=true"
        source.inputStream().use { input -> dest.outputStream().use { output -> input.copyTo(output) } }

        var shared = false
        var shareUri = ""
        if (args.boolArg("share") == true) {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", dest)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.android.package-archive"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(send, "分享 APK").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            shared = runCatching { context.startActivity(chooser); true }.getOrDefault(false)
            shareUri = if (shared) uri.toString() else ""
        }
        return buildJsonObject {
            put("package", pkg)
            put("version", version)
            put("path", dest.absolutePath)
            put("size", dest.length())
            put("sourceDir", info.sourceDir)
            put("splitCount", info.splitSourceDirs?.size ?: 0)
            put("note", if ((info.splitSourceDirs?.size ?: 0) > 0) "该应用使用 App Bundle 拆包，仅导出了 base.apk，单独安装可能缺少资源" else "")
            put("shared", shared)
            put("shareUri", shareUri)
        }.toString()
    }

    /* ---------------------------- qr_generate ---------------------------- */

    private fun qrGenerate(args: JsonObject): String {
        val content = args.strArg("text") ?: return "ERROR: 缺少 text（二维码内容）"
        val size = (args.intArg("size") ?: 512).coerceIn(64, 2048)
        val ec = when (args.strArg("ec")?.lowercase()) {
            "l" -> ErrorCorrectionLevel.L
            "q" -> ErrorCorrectionLevel.Q
            "h" -> ErrorCorrectionLevel.H
            else -> ErrorCorrectionLevel.M
        }
        val matrix = QrCodec.encode(content, size, ec)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (y in 0 until size) {
            for (x in 0 until size) {
                bitmap.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
            }
        }
        val path = ImageEditEngine.save(context, bitmap, png = true)
        val thumbnail = ImageEditEngine.thumbnail(bitmap)
        bitmap.recycle()
        return buildJsonObject {
            put("text", content)
            put("size", size)
            put("ec", ec.name)
            put("savedPath", path)
            put("thumbnailBase64", thumbnail)
            put("thumbnailMime", "image/png")
        }.toString()
    }

    /* ------------------------------ qr_scan ------------------------------ */

    private fun qrScan(args: JsonObject): String {
        val src = args.strArg("source") ?: return "ERROR: 缺少 source（图片路径 / content:// URI / 相册 id / base64）"
        val bitmap = runCatching { ImageEditEngine.decode(context, src) }
            .getOrElse { return "ERROR: 图片解码失败：${it.message ?: "文件不是有效图片"}" }
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val result = runCatching { QrCodec.decode(pixels, w, h) }
            .getOrElse {
                bitmap.recycle()
                return "ERROR: 未在图片中识别到二维码/条码（请确认图片清晰、码完整且方向正确）"
            }
        bitmap.recycle()
        return buildJsonObject {
            put("text", result.text)
            put("format", result.format)
            put("width", w)
            put("height", h)
        }.toString()
    }

    /* ---------------------------- sensor_record -------------------------- */

    private suspend fun sensorRecord(args: JsonObject): String {
        val sensorName = (args.strArg("sensor") ?: "accelerometer").lowercase()
        val sensorType = when (sensorName) {
            "accelerometer" -> Sensor.TYPE_ACCELEROMETER
            "gyroscope" -> Sensor.TYPE_GYROSCOPE
            "magnetometer" -> Sensor.TYPE_MAGNETIC_FIELD
            "light" -> Sensor.TYPE_LIGHT
            "pressure" -> Sensor.TYPE_PRESSURE
            "gravity" -> Sensor.TYPE_GRAVITY
            "linear_acceleration" -> Sensor.TYPE_LINEAR_ACCELERATION
            "rotation_vector" -> Sensor.TYPE_ROTATION_VECTOR
            else -> return "ERROR: 不支持的传感器 $sensorName（可选 accelerometer/gyroscope/magnetometer/light/pressure/gravity/linear_acceleration/rotation_vector）"
        }
        val durationMs = (args.longArg("duration_ms") ?: 3000L).coerceIn(200L, 60_000L)
        val hz = (args.intArg("hz") ?: 50).coerceIn(1, 200)
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            ?: return "ERROR: 设备无传感器服务"
        val sensor = manager.getDefaultSensor(sensorType)
            ?: return "ERROR: 设备不支持 $sensorName 传感器"

        val thread = HandlerThread("andagent-sensor-record").apply { start() }
        val handler = Handler(thread.looper)
        val samples = Collections.synchronizedList(mutableListOf<List<String>>())
        val firstTimestamp = AtomicLong(0)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val ts = event.timestamp
                firstTimestamp.compareAndSet(0, ts)
                val tMs = (ts - firstTimestamp.get()) / 1_000_000.0
                fun value(i: Int) = if (i < event.values.size) event.values[i].toString() else ""
                samples.add(
                    listOf(
                        String.format(Locale.US, "%.3f", tMs),
                        value(0), value(1), value(2),
                        event.accuracy.toString(),
                    ),
                )
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        try {
            manager.registerListener(listener, sensor, 1_000_000 / hz, 0, handler)
            delay(durationMs)
        } finally {
            manager.unregisterListener(listener)
            thread.quitSafely()
        }
        val rows = synchronized(samples) { samples.toList() }
        if (rows.isEmpty())
            return "ERROR: 采样期间没有收到传感器数据（模拟器或传感器不可用）"
        val csv = SensorCsv.build(listOf("t_ms", "x", "y", "z", "accuracy"), rows)
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "AndAgent")
        if (!dir.exists() && !dir.mkdirs()) return "ERROR: 无法创建下载目录：${dir.path}"
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "sensor_${sensorName}_$stamp.csv")
        file.writeText(csv, Charsets.UTF_8)
        return buildJsonObject {
            put("sensor", sensorName)
            put("durationMs", durationMs)
            put("hz", hz)
            put("count", rows.size)
            put("path", file.absolutePath)
            put("preview", csv.lineSequence().take(6).joinToString("\n"))
        }.toString()
    }

    /* ---------------------------- file_encrypt --------------------------- */

    private fun fileEncrypt(args: JsonObject): String {
        val rawPath = args.strArg("path") ?: return "ERROR: 缺少 path（文件绝对路径）"
        val op = args.strArg("op") ?: "encrypt"
        if (op !in setOf("encrypt", "decrypt")) return "ERROR: op 仅支持 encrypt/decrypt"
        val password = args.strArg("password") ?: return "ERROR: 缺少 password（本次使用，不会存储）"
        if (password.length < 4) return "ERROR: 密码至少 4 位"
        val src = runCatching { File(rawPath).canonicalFile }.getOrElse { return "ERROR: 路径无效" }
        if (!src.isFile) return "ERROR: 文件不存在：$rawPath"
        if (src.length() > 20L * 1024 * 1024) return "ERROR: 文件超过 20MB，本工具仅用于加密小文件"
        if (!isSafePath(src)) return "ERROR: 拒绝操作系统保护目录内的文件：${src.path}"

        val outPath = args.strArg("output") ?: when (op) {
            "encrypt" -> src.absolutePath + ".enc"
            else -> if (src.name.endsWith(".enc")) src.absolutePath.removeSuffix(".enc") else src.absolutePath + ".dec"
        }
        val dest = runCatching { File(outPath).canonicalFile }.getOrElse { return "ERROR: 输出路径无效" }
        if (dest == src) return "ERROR: 输出文件不能与输入文件相同（请用 output 指定其它路径）"
        if (!isSafePath(dest)) return "ERROR: 拒绝写入系统保护目录：${dest.path}"
        if (dest.exists() && args.boolArg("confirm") != true)
            return "ERROR: 目标文件已存在 ${dest.path}，覆盖请传 confirm=true"
        val bytes = src.readBytes()
        val output = runCatching {
            when (op) {
                "encrypt" -> FileCrypto.encrypt(bytes, password.toCharArray())
                else -> FileCrypto.decrypt(bytes, password.toCharArray())
            }
        }.getOrElse {
            return if (op == "decrypt")
                "ERROR: 解密失败：密码错误或文件已损坏（${it.javaClass.simpleName}）"
            else "ERROR: 加密失败：${it.message ?: it.javaClass.simpleName}"
        }
        dest.parentFile?.let { if (!it.exists() && !it.mkdirs()) return "ERROR: 无法创建输出目录：${it.path}" }
        // Write to a sibling temp file then rename, so a failure mid-write can
        // never leave the target truncated.
        val tmp = File(dest.parentFile, dest.name + ".tmp_" + System.currentTimeMillis())
        try {
            tmp.writeBytes(output)
            if (!tmp.renameTo(dest)) tmp.copyTo(dest, overwrite = true)
        } finally {
            tmp.delete()
        }
        return buildJsonObject {
            put("op", op)
            put("source", src.absolutePath)
            put("output", dest.absolutePath)
            put("inputBytes", bytes.size.toLong())
            put("outputBytes", output.size.toLong())
        }.toString()
    }

    private fun isSafePath(file: File): Boolean {
        val blocked = listOf(
            "/system", "/vendor", "/proc", "/dev", "/sys", "/apex",
            "/data", "/mnt", "/etc", "/firmware", "/persist", "/odm", "/product",
        )
        val p = file.path.trimEnd('/')
        if (file.path == "/") return false
        val ownDirs = listOfNotNull(
            runCatching { context.filesDir.canonicalPath }.getOrNull(),
            runCatching { context.cacheDir.canonicalPath }.getOrNull(),
            runCatching { context.getExternalFilesDir(null)?.canonicalPath }.getOrNull(),
            runCatching { context.getDatabasePath("x").parentFile?.canonicalPath }.getOrNull(),
        ).filter { it.isNotEmpty() }
        if (ownDirs.any { p == it || p.startsWith(it + "/") }) return true
        return blocked.none { p == it || p.startsWith("$it/") }
    }
}
