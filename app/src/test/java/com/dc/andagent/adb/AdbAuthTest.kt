package com.dc.andagent.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import javax.crypto.spec.SecretKeySpec

/** R100 ①：RSA / SHA1withRSA / AES-GCM 落盘，纯 JVM，无 AndroidKeyStore。 */
class AdbAuthTest {

    /** JVM 版 KeyWrapper：固定（硬编码）AES-256 key，仅测试桩，无安全语义。 */
    private fun jvmWrapper() = AesGcmKeyWrapper(SecretKeySpec(ByteArray(32) { 0x5a.toByte() }, "AES"))

    @Test
    fun generateKeyPairIsRsa2048() {
        val kp = AdbAuth.generateKeyPair()
        assertTrue(kp.private is RSAPrivateKey)
        assertTrue(kp.public is RSAPublicKey)
        assertEquals(2048, (kp.public as RSAPublicKey).modulus.bitLength())
    }

    @Test
    fun signVerifyRoundtrip() {
        val kp = AdbAuth.generateKeyPair()
        val token = ByteArray(20) { (it * 13 + 7).toByte() }
        val sig = AdbAuth.signToken(kp.private, token)
        assertEquals(256, sig.size) // RSA-2048 签名长度
        assertTrue(AdbAuth.verifyToken(kp.public, token, sig))
    }

    @Test
    fun wrongTokenVerifyFails() {
        val kp = AdbAuth.generateKeyPair()
        val token = ByteArray(20) { 1.toByte() }
        val sig = AdbAuth.signToken(kp.private, token)
        val other = ByteArray(20) { 2.toByte() }
        assertFalse(AdbAuth.verifyToken(kp.public, other, sig))
    }

    @Test
    fun tamperedSignatureVerifyFails() {
        val kp = AdbAuth.generateKeyPair()
        val token = ByteArray(20) { 3.toByte() }
        val sig = AdbAuth.signToken(kp.private, token)
        sig[10] = (sig[10].toInt() xor 0x01).toByte()
        assertFalse(AdbAuth.verifyToken(kp.public, token, sig))
    }

    @Test
    fun keySaveLoadRoundtrip() {
        val kp = AdbAuth.generateKeyPair()
        val file = File.createTempFile("adbkey", ".enc")
        try {
            AdbAuth.save(kp, file, jvmWrapper())
            val loaded = AdbAuth.load(file, jvmWrapper())
            assertArrayEquals(kp.private.encoded, loaded.private.encoded)
            assertArrayEquals(kp.public.encoded, loaded.public.encoded)
            // 载入的私钥仍可签名并验签
            val token = ByteArray(20) { 9.toByte() }
            assertTrue(AdbAuth.verifyToken(loaded.public, token, AdbAuth.signToken(loaded.private, token)))
        } finally {
            file.delete()
        }
    }

    @Test
    fun loadTamperedWrappedKeyFails() {
        val kp = AdbAuth.generateKeyPair()
        val file = File.createTempFile("adbkey", ".enc")
        try {
            AdbAuth.save(kp, file, jvmWrapper())
            val bytes = file.readBytes()
            bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x01).toByte() // 篡改 GCM tag
            file.writeBytes(bytes)
            val r = runCatching { AdbAuth.load(file, jvmWrapper()) }
            assertTrue("GCM 完整性必须阻止篡改", r.isFailure)
        } finally {
            file.delete()
        }
    }
}
