package com.dc.andagent.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * R79 — miIO LAN 协议 PoC 的纯函数层（零 Android 依赖，纯 JVM，可直接在 JVM 单测覆盖）。
 *
 * 协议对照权威为 python-miio 的公开协议格式事实；本文件为独立实现，未照搬其代码。
 * 简报「协议事实」段存在多处自相矛盾（16 字节头 / 24 字节头 / 头前 12 字节 / stamp 与
 * 设备 id 偏移 / 「key = token 原文，IV = key+key」等）。按简报授权「以能自洽通过向量测试
 * 的实现为准，并在头注释记录最终采用的字节级事实」，本刀最终采用的字节级事实如下：
 *
 * ── 报文布局（大端，32 字节固定头 + 可选密文正文）──
 *   offset  0 (2B) magic      = 0x2131
 *   offset  2 (2B) length     = 整包总字节数（32 + 密文长度），含头
 *   offset  4 (4B) unknown    = 0x00000000（保留；HELLO 中为 0xFFFFFFFF）
 *   offset  8 (4B) device_id  = 设备 did（u32）；HELLO 中为 0xFFFFFFFF；取自 HELLO 响应头
 *   offset 12 (4B) stamp      = 设备时钟秒 / uptime（u32）；HELLO 中为 0xFFFFFFFF
 *   offset 16 (16B) checksum  = MD5(head16 + token + 密文)；无 token 时固定 0xFF × 16
 *   offset 32 (nB) 密文       = AES-128-CBC(JSON UTF-8)，长度必为 16 的整数倍
 *   其中 head16 = 头的前 16 字节（magic/length/unknown/device_id/stamp），
 *   计算 checksum 时把「本应填 0 的 checksum 字段」位置替换为 16 字节 token 再整体 MD5。
 *
 * ── HELLO（发现握手，整包 32 字节、正文空）──
 *   21310020 + 0xFF × 28。即 magic=0x2131、length=0x0020、unknown/did/stamp 全 F、
 *   checksum 全 F。python-miio 公开常量 21310020ffffffff...（共 32B）与此一致。
 *
 * ── 加密（AES-128-CBC / PKCS5Padding(=PKCS#7)）──
 *   简报的「key = token 16B 原文，IV = key+key」按字面无法落地（AES-CBC 的 IV 必须是
 *   16 字节，而 key+key 为 32 字节），且与 python-miio 公开文档化事实不符。本刀采用
 *   python-miio 的真实派生：key = MD5(token)、iv = MD5(key || token)（各 16B）。
 *   token 本身仍是 16 字节原文（hex 32 / base64 24），只是 AES 密钥与 IV 由其派生。
 *   该选择由 Python 独立计算的已知明密文向量固定（见 MiioCodecTest），与真实设备互通概率
 *   最高；真机覆盖率仍按简报如实标注，不承诺全设备支持。
 *
 * ── 校验 ──
 *   parseResponse 有 token 时校验整包 checksum（常数时间比较）并解密；无 token 时只校验
 *   magic / length / 16 对齐（HELLO 响应为无 token 包，checksum 字段为全 F 或设备 token，
 *   按简报「token 未知提示」不回显其原始字节）。任何垃圾输入安全返回 null，绝不抛异常。
 *
 * ── 命令与错误码 ──
 *   请求 JSON 如 {"id":1,"method":"miIO.info","params":[]}；响应 result/error 判定，
 *   错误码映射见 [ERROR_REASONS]。stamp 取自 HELLO 响应头供下一条命令使用。
 */
object MiioCodec {
    const val MAGIC = 0x2131
    const val HEADER_SIZE = 32
    const val TOKEN_SIZE = 16
    const val CHECKSUM_SIZE = 16
    const val DEFAULT_PORT = 54321

    /** HELLO 常量：21310020 + 0xFF×28，整包 32 字节。 */
    private val HELLO: ByteArray = ByteArray(HEADER_SIZE).also { b ->
        putU16be(b, 0, MAGIC)
        putU16be(b, 2, HEADER_SIZE)
        for (i in 4 until HEADER_SIZE) b[i] = 0xFF.toByte()
    }

    /** 结构化响应：device_id / stamp 为 u32（用 Long 避免符号问题），plainText 为解密后的 JSON。 */
    data class MiioResponse(
        val deviceId: Long,
        val stamp: Long,
        val plainText: String?,
        /** 有 token 且 checksum 不匹配时为 false；无 token 恒为 true（不验 checksum）。 */
        val checksumValid: Boolean,
    )

    /** 命令结果：ok 判定 + 原样透传的 result 元素 + 错误码/可读原因。 */
    data class CommandResult(
        val ok: Boolean,
        val id: Int?,
        val result: JsonElement?,
        val errorCode: Int?,
        val reason: String?,
    )

    /** miIO 公开错误码 → 可读原因（简报给定映射表）。 */
    val ERROR_REASONS: Map<Int, String> = mapOf(
        -1 to "未知错误",
        -30001 to "需重试或请求格式/stamp 偏差过大（设备拒绝该 stamp）",
        -30004 to "参数错误",
        -30008 to "API（method）不存在",
        -30011 to "设备无响应",
    )

    fun errorReason(code: Int): String = ERROR_REASONS[code] ?: "未登记的错误码 $code"

    /** 32B HELLO 常量报文（返回防御性副本，避免调用方污染常量）。 */
    fun discoverRequest(): ByteArray = HELLO.copyOf()

    /**
     * 解析响应包。垃圾输入安全返回 null；有 token 时校验 checksum 并解密，
     * 无 token 时只校验 magic / length / 16 对齐并返回 deviceId / stamp。
     */
    fun parseResponse(bytes: ByteArray, token: ByteArray?): MiioResponse? = try {
        if (bytes.size < HEADER_SIZE) null
        else if (u16be(bytes, 0) != MAGIC) null
        else {
            val declared = u16be(bytes, 2)
            if (declared < HEADER_SIZE || declared > bytes.size) null
            else {
                val bodyLen = declared - HEADER_SIZE
                if (bodyLen % 16 != 0) null
                else {
                    val deviceId = u32be(bytes, 8)
                    val stamp = u32be(bytes, 12)
                    if (token == null) {
                        // 无 token：不验 checksum、不回显其字节、不解密。
                        MiioResponse(deviceId, stamp, null, true)
                    } else if (token.size != TOKEN_SIZE) {
                        null
                    } else {
                        val head16 = bytes.copyOfRange(0, 16)
                        val checksum = bytes.copyOfRange(16, 32)
                        val cipher = if (bodyLen > 0) bytes.copyOfRange(HEADER_SIZE, declared) else ByteArray(0)
                        val expected = md5(head16 + token + cipher)
                        if (!constantTimeEquals(checksum, expected)) {
                            // 结构合法但 checksum 不匹配（token 错 / 篡改）：显式标记，不抛异常。
                            MiioResponse(deviceId, stamp, null, false)
                        } else {
                            val plain = try {
                                String(decrypt(token, cipher), Charsets.UTF_8)
                            } catch (t: Throwable) {
                                null
                            }
                            MiioResponse(deviceId, stamp, plain, true)
                        }
                    }
                }
            }
        }
    } catch (t: Throwable) {
        null
    }

    /** AES-128-CBC 加密：key = MD5(token)，iv = MD5(key || token)，PKCS5Padding。 */
    fun encrypt(token: ByteArray, plain: ByteArray): ByteArray {
        val (key, iv) = derive(token)
        return Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        }.doFinal(plain)
    }

    /** AES-128-CBC 解密（与 encrypt 对称）；密文非法时由调用方 try-catch。 */
    fun decrypt(token: ByteArray, cipher: ByteArray): ByteArray {
        val (key, iv) = derive(token)
        return Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        }.doFinal(cipher)
    }

    /**
     * 构造一条命令报文：deviceId/stamp 通常取自 HELLO 响应头（PoC 中可为 0 占位），
     * JSON 加密为正文，头 length 与 checksum 一并重算。
     */
    fun buildCommand(deviceId: Long, stamp: Long, token: ByteArray, json: String): ByteArray {
        require(token.size == TOKEN_SIZE) { "token 必须 16 字节" }
        val cipher = encrypt(token, json.toByteArray(Charsets.UTF_8))
        val total = HEADER_SIZE + cipher.size
        require(total <= 0xFFFF) { "命令报文超过 u16 length 上限" }
        val out = ByteArray(total)
        putU16be(out, 0, MAGIC)
        putU16be(out, 2, total)
        putU32be(out, 4, 0L)       // unknown
        putU32be(out, 8, deviceId)
        putU32be(out, 12, stamp)
        val head16 = out.copyOfRange(0, 16)
        val checksum = md5(head16 + token + cipher)
        System.arraycopy(checksum, 0, out, 16, CHECKSUM_SIZE)
        System.arraycopy(cipher, 0, out, HEADER_SIZE, cipher.size)
        return out
    }

    /** 判定响应 JSON 的 result / error，并把错误码映射为可读原因；坏 JSON 不抛异常。 */
    fun parseCommandResult(json: String): CommandResult = try {
        val root = Json.parseToJsonElement(json) as? JsonObject
            ?: return CommandResult(false, null, null, null, "响应不是 JSON 对象")
        val id = (root["id"] as? JsonPrimitive)?.content?.toIntOrNull()
        val err = root["error"] as? JsonObject
        when {
            err != null -> {
                val code = (err["code"] as? JsonPrimitive)?.content?.toIntOrNull() ?: -1
                val msg = (err["message"] as? JsonPrimitive)?.content
                val reason = buildString {
                    append(errorReason(code))
                    if (!msg.isNullOrBlank()) append("（设备：").append(msg.take(120)).append("）")
                }
                CommandResult(false, id, null, code, reason)
            }
            root.containsKey("result") -> CommandResult(true, id, root["result"], null, null)
            else -> CommandResult(false, id, null, null, "响应既无 result 也无 error")
        }
    } catch (t: Throwable) {
        CommandResult(false, null, null, null, "响应不是合法 JSON：${t.message?.take(80).orEmpty()}")
    }

    /** token 归一：32 位 hex 或 24 位 base64 → 16 字节；非法抛 IllegalArgumentException。 */
    fun normalizeToken(input: String?): ByteArray {
        val s = input?.trim().orEmpty()
        val bytes: ByteArray = when {
            s.length == 32 && s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' } ->
                s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            s.length == 24 -> try {
                Base64.getDecoder().decode(s)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("token base64 非法")
            }
            else -> throw IllegalArgumentException("token 必须为 32 位 hex 或 24 位 base64（16 字节）")
        }
        if (bytes.size != TOKEN_SIZE) {
            throw IllegalArgumentException("token 解码后应为 16 字节，实际 ${bytes.size} 字节")
        }
        return bytes
    }

    /** token 脱敏：≤8 字符全遮（固定 8 个 *，不泄露长度与内容），否则前 4 + "..." + 后 4。 */
    fun maskToken(input: String?): String {
        val s = input.orEmpty()
        return if (s.length <= 8) "********" else s.take(4) + "..." + s.takeLast(4)
    }

    /* ------------------------------- 字节级工具 ------------------------------- */

    private fun derive(token: ByteArray): Pair<ByteArray, ByteArray> {
        require(token.size == TOKEN_SIZE) { "token 必须 16 字节" }
        val key = md5(token)              // key = MD5(token)
        val iv = md5(key + token)         // iv  = MD5(key || token)
        return key to iv
    }

    private fun md5(data: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(data)

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    private fun u16be(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    private fun u32be(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xFF) shl 24) or
            ((b[off + 1].toLong() and 0xFF) shl 16) or
            ((b[off + 2].toLong() and 0xFF) shl 8) or
            (b[off + 3].toLong() and 0xFF)

    private fun putU16be(b: ByteArray, off: Int, v: Int) {
        b[off] = (v ushr 8).toByte()
        b[off + 1] = v.toByte()
    }

    private fun putU32be(b: ByteArray, off: Int, v: Long) {
        b[off] = (v ushr 24).toByte()
        b[off + 1] = (v ushr 16).toByte()
        b[off + 2] = (v ushr 8).toByte()
        b[off + 3] = v.toByte()
    }
}
