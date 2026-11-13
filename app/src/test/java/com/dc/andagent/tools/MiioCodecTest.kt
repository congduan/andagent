package com.dc.andagent.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * R79 miIO 协议 PoC 的 JVM 单测：
 *  1) MiioCodec 纯函数（HELLO 常量、垃圾输入、AES 已知明密文向量、头字节、checksum 篡改、
 *     token 归一、maskToken、错误码表、parseCommandResult）
 *  2) 三工具在 127.0.0.1:1 不可达 / 参数越界 / 缺参 / 非法 token 时的结构化容错
 *  3) 注册表 / schema / destructive 对齐
 * 不需要 Robolectric：MiioTool 的 Context 可空，DatagramSocket / javax.crypto 均为纯 Java。
 *
 * 已知明密文向量由 Python（pycryptodome + hashlib）独立生成，与 Kotlin 实现无共享代码：
 *   token = 00112233445566778899aabbccddeeff
 *   key   = MD5(token) = 6e8311168ee16d6aa1aa48c64145003c
 *   iv    = MD5(key||token) = 6f434fa9acd75da73e5fb999f641cda2
 *   plain = {"id":1,"method":"miIO.info","params":[]}  (41B, PKCS#7 → 48B 密文)
 *   checksum = MD5(head16 + token + ciphertext)
 */
class MiioCodecTest {

    private val TOKEN_HEX = "00112233445566778899aabbccddeeff"
    private val TOKEN_B64 = "ABEiM0RVZneImaq7zN3u/w=="
    private val TOKEN: ByteArray get() = MiioCodec.normalizeToken(TOKEN_HEX)
    private val PLAIN = """{"id":1,"method":"miIO.info","params":[]}"""
    private val CIPHER_HEX =
        "a5516ec6151955dc2bb2d43e7c84c18352a39a7f22fd2903d282a5783c6d56ac8c9c7629130a25fac17d38175c1a7799"
    private val PACKET_HEX =
        "21310050000000001234567800000010dba5bd4512a5320b47d7cdd8d784f14fa5516ec6151955dc2bb2d43e7c84c1835" +
            "2a39a7f22fd2903d282a5783c6d56ac8c9c7629130a25fac17d38175c1a7799"

    /* --------------------------------- HELLO 常量 --------------------------------- */

    @Test fun discoverRequestIs32ByteHelloConstant() {
        val hello = MiioCodec.discoverRequest()
        assertEquals(32, hello.size)
        assertEquals(0x21.toByte(), hello[0])
        assertEquals(0x31.toByte(), hello[1])
        assertEquals(0x00.toByte(), hello[2])
        assertEquals(0x20.toByte(), hello[3])
        assertTrue((4 until 32).all { hello[it] == 0xFF.toByte() })
        assertEquals("21310020" + "ff".repeat(28), hello.hex())
    }

    @Test fun discoverRequestReturnsDefensiveCopy() {
        val a = MiioCodec.discoverRequest()
        a[0] = 0x00
        assertEquals(0x21.toByte(), MiioCodec.discoverRequest()[0])
    }

    /* ------------------------------ parseResponse 垃圾输入 ------------------------------ */

    @Test fun parseResponseRejectsGarbage() {
        assertNull(MiioCodec.parseResponse(ByteArray(0), null))
        assertNull(MiioCodec.parseResponse(ByteArray(16), null))
        assertNull(MiioCodec.parseResponse(ByteArray(31), null))
        // magic 错误
        assertNull(MiioCodec.parseResponse(ByteArray(32).also { it[0] = 0x00; it[1] = 0x00; it[2] = 0; it[3] = 32 }, null))
        // 声明长度大于实际
        assertNull(MiioCodec.parseResponse(ByteArray(32).also { it[0] = 0x21; it[1] = 0x31; it[2] = 0; it[3] = 40 }, null))
        // 正文非 16 对齐
        val misaligned = responsePacket(1, 2, ByteArray(3))
        assertNull(MiioCodec.parseResponse(misaligned, null))
        // 带 token 的非法 token 长度
        assertNull(MiioCodec.parseResponse(responsePacket(1, 2, ByteArray(16)), ByteArray(8)))
    }

    @Test fun parseResponseNoTokenParsesHelloLikeReply() {
        val pkt = responsePacket(0x12345678L, 0x00000010L, ByteArray(0))
        val r = MiioCodec.parseResponse(pkt, null)!!
        assertEquals(0x12345678L, r.deviceId)
        assertEquals(0x10L, r.stamp)
        assertNull(r.plainText)
        assertTrue(r.checksumValid)
    }

    @Test fun parseResponseWithTokenRoundTripsBuildCommand() {
        val pkt = MiioCodec.buildCommand(0x12345678L, 0x10L, TOKEN, PLAIN)
        val r = MiioCodec.parseResponse(pkt, TOKEN)!!
        assertEquals(0x12345678L, r.deviceId)
        assertEquals(0x10L, r.stamp)
        assertTrue(r.checksumValid)
        assertEquals(PLAIN, r.plainText)
    }

    @Test fun parseResponseDetectsTamperedChecksum() {
        val pkt = MiioCodec.buildCommand(0x12345678L, 0x10L, TOKEN, PLAIN)
        pkt[pkt.size - 1] = (pkt[pkt.size - 1].toInt() xor 0x01).toByte() // 篡改密文 1 字节
        val r = MiioCodec.parseResponse(pkt, TOKEN)
        assertNotNull(r)
        assertFalse(r!!.checksumValid)
        assertNull(r.plainText)

        val pkt2 = MiioCodec.buildCommand(0x12345678L, 0x10L, TOKEN, PLAIN)
        pkt2[20] = (pkt2[20].toInt() xor 0x01).toByte() // 篡改 checksum 字段 1 字节
        assertFalse(MiioCodec.parseResponse(pkt2, TOKEN)!!.checksumValid)
    }

    @Test fun parseResponseWrongTokenFailsChecksum() {
        val pkt = MiioCodec.buildCommand(0x12345678L, 0x10L, TOKEN, PLAIN)
        val wrong = MiioCodec.normalizeToken("ffeeddccbbaa99887766554433221100")
        val r = MiioCodec.parseResponse(pkt, wrong)
        assertNotNull(r)
        assertFalse(r!!.checksumValid)
        assertNull(r.plainText)
    }

    /* ---------------------------- AES 已知明密文向量 ---------------------------- */

    @Test fun encryptMatchesKnownVector() {
        val ct = MiioCodec.encrypt(TOKEN, PLAIN.toByteArray(Charsets.UTF_8))
        assertEquals(CIPHER_HEX, ct.hex())
    }

    @Test fun decryptMatchesKnownVector() {
        assertArrayEquals(PLAIN.toByteArray(Charsets.UTF_8), MiioCodec.decrypt(TOKEN, hex(CIPHER_HEX)))
    }

    @Test fun encryptDecryptRoundtrip() {
        val plain = """{"id":7,"method":"set_power","params":[true],"note":"灯€"}""".toByteArray(Charsets.UTF_8)
        assertArrayEquals(plain, MiioCodec.decrypt(TOKEN, MiioCodec.encrypt(TOKEN, plain)))
    }

    @Test fun encryptOutputIs16ByteAligned() {
        val ct = MiioCodec.encrypt(TOKEN, PLAIN.toByteArray(Charsets.UTF_8))
        assertEquals(0, ct.size % 16)
        assertTrue(ct.size >= 16)
    }

    /* ------------------------------ buildCommand 头字节 ------------------------------ */

    @Test fun buildCommandHeaderBytesMatchKnownVector() {
        val pkt = MiioCodec.buildCommand(0x12345678L, 0x10L, TOKEN, PLAIN)
        assertEquals(0x21.toByte(), pkt[0])
        assertEquals(0x31.toByte(), pkt[1])
        val len = ((pkt[2].toInt() and 0xFF) shl 8) or (pkt[3].toInt() and 0xFF)
        assertEquals(pkt.size, len)
        assertEquals(32 + 48, pkt.size)
        // device_id @ offset 8..12
        assertArrayEquals(byteArrayOf(0x12, 0x34, 0x56, 0x78), pkt.copyOfRange(8, 12))
        // stamp @ offset 12..16
        assertArrayEquals(byteArrayOf(0x00, 0x00, 0x00, 0x10), pkt.copyOfRange(12, 16))
        // 整包字节级向量（含独立计算的 checksum）
        assertEquals(PACKET_HEX, pkt.hex())
    }

    @Test fun buildCommandChecksumFieldIsMd5OfHeadTokenBody() {
        val pkt = MiioCodec.buildCommand(0x12345678L, 0x10L, TOKEN, PLAIN)
        val head16 = pkt.copyOfRange(0, 16)
        val body = pkt.copyOfRange(32, pkt.size)
        val expected = MessageDigest.getInstance("MD5").digest(head16 + TOKEN + body)
        assertArrayEquals(expected, pkt.copyOfRange(16, 32))
    }

    /* ------------------------------ 命令结果与错误码 ------------------------------ */

    @Test fun parseCommandResultSuccessPassesThroughResult() {
        val r = MiioCodec.parseCommandResult("""{"id":1,"result":{"model":"chuangmi.plug.m3","ip":"192.168.1.5"}}""")
        assertTrue(r.ok)
        assertEquals(1, r.id!!)
        assertNotNull(r.result)
        assertTrue(r.result.toString().contains("chuangmi.plug.m3"))
        assertNull(r.errorCode)
        assertNull(r.reason)
    }

    @Test fun parseCommandResultErrorMapsCodeAndMessage() {
        val r = MiioCodec.parseCommandResult("""{"id":1,"error":{"code":-30001,"message":"invalid stamp"}}""")
        assertFalse(r.ok)
        assertEquals(-30001, r.errorCode!!)
        assertTrue(r.reason!!.contains("重试"))
        assertTrue(r.reason!!.contains("invalid stamp"))
        assertNull(r.result)
    }

    @Test fun errorReasonCoversFullTable() {
        assertEquals(5, MiioCodec.ERROR_REASONS.size)
        for (code in listOf(-1, -30001, -30004, -30008, -30011)) {
            assertTrue(MiioCodec.ERROR_REASONS[code]!!.isNotBlank())
            assertEquals(MiioCodec.ERROR_REASONS[code], MiioCodec.errorReason(code))
        }
        assertTrue(MiioCodec.errorReason(999).contains("未登记"))
    }

    @Test fun parseCommandResultRejectsBadJsonAndMalformedShapes() {
        assertFalse(MiioCodec.parseCommandResult("{not json").ok)
        assertFalse(MiioCodec.parseCommandResult("[1,2,3]").ok) // 非对象
        assertFalse(MiioCodec.parseCommandResult("""{"id":1}""").ok) // 既无 result 也无 error
        assertTrue(MiioCodec.parseCommandResult("{not json").reason!!.contains("合法 JSON"))
    }

    /* -------------------------------- token 归一 / 脱敏 -------------------------------- */

    @Test fun normalizeTokenHex() {
        assertEquals(16, TOKEN.size)
        assertEquals(TOKEN_HEX, TOKEN.hex())
        assertEquals(TOKEN_HEX, MiioCodec.normalizeToken(TOKEN_HEX.uppercase()).hex())
        assertEquals(TOKEN_HEX, MiioCodec.normalizeToken("  $TOKEN_HEX  ").hex())
    }

    @Test fun normalizeTokenBase64() {
        assertEquals(TOKEN_HEX, MiioCodec.normalizeToken(TOKEN_B64).hex())
    }

    @Test fun normalizeTokenRejectsInvalid() {
        for (bad in listOf(
            null, "", "  ", "abc", "A".repeat(31), "A".repeat(33),
            "z".repeat(32), "!".repeat(24), TOKEN_B64 + "XX",
        )) {
            assertThrows(IllegalArgumentException::class.java) { MiioCodec.normalizeToken(bad) }
        }
    }

    @Test fun maskTokenBoundaries() {
        assertEquals("********", MiioCodec.maskToken(null))
        assertEquals("********", MiioCodec.maskToken(""))
        assertEquals("********", MiioCodec.maskToken("abcd"))
        assertEquals("********", MiioCodec.maskToken("12345678"))
        assertEquals("0011...eeff", MiioCodec.maskToken(TOKEN_HEX))
        assertEquals("ABEi.../w==", MiioCodec.maskToken(TOKEN_B64))
    }

    @Test fun maskTokenNeverLeaksMiddle() {
        val m = MiioCodec.maskToken(TOKEN_HEX)
        assertFalse(m.contains("2233445566778899aabbccdd"))
    }

    /* --------------------- 工具：127.0.0.1:1 不可达结构化容错 --------------------- */

    @Test fun sendUnreachableReturnsStructuredErrorAndMasksToken() {
        val args = buildJsonObject {
            put("ip", "127.0.0.1:1")
            put("token", TOKEN_HEX)
            put("command", """{"id":1,"method":"set_power","params":[true]}""")
            put("timeout_ms", 1000)
            put("retries", 0)
        }.toString()
        val text = call("miio_send", args)
        val r = obj(text)
        assertFalse(r["ok"]!!.jsonPrimitive.boolean)
        assertNotNull(r["error"])
        assertTrue(r["reason"]!!.jsonPrimitive.content.isNotBlank())
        assertEquals("0011...eeff", r["token"]!!.jsonPrimitive.content)
        assertFalse(text.contains(TOKEN_HEX))
        assertFalse(text.contains(TOKEN_B64))
    }

    @Test fun infoUnreachableReturnsStructuredErrorAndMasksToken() {
        val args = buildJsonObject {
            put("ip", "127.0.0.1:1")
            put("token", TOKEN_B64)
            put("timeout_ms", 1000)
            put("retries", 0)
        }.toString()
        val text = call("miio_info", args)
        val r = obj(text)
        assertFalse(r["ok"]!!.jsonPrimitive.boolean)
        assertNotNull(r["error"])
        assertTrue(r["reason"]!!.jsonPrimitive.content.isNotBlank())
        assertFalse(text.contains(TOKEN_HEX))
        assertFalse(text.contains(TOKEN_B64))
    }

    /* -------------------------- 工具：参数校验失败分支 -------------------------- */

    @Test fun outOfRangeParamsAreRejected() {
        for ((key, value) in listOf(
            "timeout_ms" to 999, "timeout_ms" to 5001,
            "retries" to -1, "retries" to 3,
        )) {
            val m = buildJsonObject {
                put("ip", "127.0.0.1:1")
                put("token", TOKEN_HEX)
                put("command", """{"id":1,"method":"set_power","params":[true]}""")
            }.toMutableMap()
            m[key] = JsonPrimitive(value)
            val r = obj(call("miio_send", JsonObject(m).toString()))
            assertFalse("expected rejection for $key=$value", r["ok"]!!.jsonPrimitive.boolean)
            assertEquals("invalid_args", r["error"]!!.jsonPrimitive.content)
        }
    }

    @Test fun missingAndInvalidArgsAreStructured() {
        val missing = obj(call("miio_send", """{"ip":"127.0.0.1:1"}"""))
        assertFalse(missing["ok"]!!.jsonPrimitive.boolean)
        assertEquals("invalid_args", missing["error"]!!.jsonPrimitive.content)

        val badToken = obj(call("miio_info", buildJsonObject {
            put("ip", "127.0.0.1:1")
            put("token", "not-a-token")
            put("retries", 0)
        }.toString()))
        assertFalse(badToken["ok"]!!.jsonPrimitive.boolean)
        assertEquals("invalid_args", badToken["error"]!!.jsonPrimitive.content)

        val badCommand = obj(call("miio_send", buildJsonObject {
            put("ip", "127.0.0.1:1")
            put("token", TOKEN_HEX)
            put("command", "not json")
            put("retries", 0)
        }.toString()))
        assertFalse(badCommand["ok"]!!.jsonPrimitive.boolean)
        assertEquals("invalid_args", badCommand["error"]!!.jsonPrimitive.content)
    }

    @Test fun discoverWithNullContextIsStructuredAndNeverCrashes() {
        val r = obj(call("miio_discover", "{}"))
        assertFalse(r["ok"]!!.jsonPrimitive.boolean)
        assertEquals(0, r["count"]!!.jsonPrimitive.int)
        assertNotNull(r["reason"])
    }

    /* ------------------------- 注册表 / schema / destructive ------------------------- */

    @Test fun miioToolsRegistryHasThreeAlignedNames() {
        assertEquals(listOf("miio_discover", "miio_send", "miio_info"), MIIO_TOOLS)
    }

    @Test fun toolSchemasExposeExpectedParameterKeys() {
        val send = MiioTool(name = "miio_send").parameters as JsonObject
        assertEquals(
            setOf("ip", "token", "command", "timeout_ms", "retries"),
            send["properties"]!!.jsonObject.keys,
        )
        assertEquals(
            listOf("ip", "token", "command"),
            send["required"]!!.jsonArray.map { it.jsonPrimitive.content },
        )

        val info = MiioTool(name = "miio_info").parameters as JsonObject
        assertEquals(setOf("ip", "token", "timeout_ms", "retries"), info["properties"]!!.jsonObject.keys)
        assertEquals(listOf("ip", "token"), info["required"]!!.jsonArray.map { it.jsonPrimitive.content })

        val discover = MiioTool(name = "miio_discover").parameters as JsonObject
        assertTrue(discover["properties"]!!.jsonObject.isEmpty())
        assertFalse(discover.containsKey("required"))
    }

    @Test fun destructiveOnlyForSend() {
        assertTrue(MiioTool(name = "miio_send").destructive)
        assertFalse(MiioTool(name = "miio_discover").destructive)
        assertFalse(MiioTool(name = "miio_info").destructive)
    }

    /* ---------------------------------- helpers ---------------------------------- */

    private fun call(name: String, json: String): String =
        runBlocking { MiioTool(name = name).execute(Json.parseToJsonElement(json) as JsonObject) }

    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject

    private fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun responsePacket(
        did: Long,
        stamp: Long,
        body: ByteArray,
        checksum: ByteArray = ByteArray(16) { 0xFF.toByte() },
    ): ByteArray {
        val total = 32 + body.size
        val b = ByteArray(total)
        b[0] = 0x21; b[1] = 0x31
        b[2] = (total ushr 8).toByte(); b[3] = total.toByte()
        putU32(b, 8, did); putU32(b, 12, stamp)
        System.arraycopy(checksum, 0, b, 16, 16)
        System.arraycopy(body, 0, b, 32, body.size)
        return b
    }

    private fun putU32(b: ByteArray, off: Int, v: Long) {
        b[off] = (v ushr 24).toByte()
        b[off + 1] = (v ushr 16).toByte()
        b[off + 2] = (v ushr 8).toByte()
        b[off + 3] = v.toByte()
    }
}
