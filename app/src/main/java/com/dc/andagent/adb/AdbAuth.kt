package com.dc.andagent.adb

import java.io.File
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 密钥落盘加密包装。JVM 单测无法使用 AndroidKeyStore，故把「加密/解密字节块」抽成接口：
 * 测试侧用固定（硬编码）key 的 [AesGcmKeyWrapper]，Android 侧用 [AndroidKeystoreKeyWrapper]
 * （AES-256 密钥由 AndroidKeyStore 生成并保管）。两个实现共用同一套密文格式。
 */
interface KeyWrapper {
    fun wrap(plain: ByteArray): ByteArray
    fun unwrap(wrapped: ByteArray): ByteArray
}

/**
 * AES-GCM 包装，格式 = 12 字节 IV || 密文(含 16 字节 GCM tag)。
 * JVM / Android 共用，仅 SecretKey 来源不同（硬编码 key / AndroidKeyStore）。
 */
class AesGcmKeyWrapper(private val key: SecretKey) : KeyWrapper {
    override fun wrap(plain: ByteArray): ByteArray {
        val iv = ByteArray(GCM_IV_SIZE).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return iv + cipher.doFinal(plain)
    }

    override fun unwrap(wrapped: ByteArray): ByteArray {
        require(wrapped.size > GCM_IV_SIZE) { "wrapped key too short" }
        val iv = wrapped.copyOfRange(0, GCM_IV_SIZE)
        val ct = wrapped.copyOfRange(GCM_IV_SIZE, wrapped.size)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ct)
    }

    private companion object {
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val GCM_IV_SIZE = 12
        const val GCM_TAG_BITS = 128
    }
}

object AdbAuth {
    private const val KEY_ALGORITHM = "RSA"
    private const val KEY_SIZE = 2048

    /**
     * 可证伪假设（R100 简报）：adb AUTH 签名 = RSA-SHA1（PKCS#1 v1.5 + SHA-1），token 20 字节，
     * 对应 AOSP `RSA_sign(NID_sha1, token, 20, ...)`。本轮以 JDK 私钥签名 → mock 对端 JDK 公钥
     * verify 往返验证（见 AdbAuthTest / AdbClientTest）；未发现反例，故无需改 RAW RSA / RSAPSS，
     * 也不触碰 roadmap 标注的「Keystore 签名兼容性」风险（签名走纯 JDK java.security）。
     */
    private const val SIGN_ALGORITHM = "SHA1withRSA"

    fun generateKeyPair(): KeyPair =
        KeyPairGenerator.getInstance(KEY_ALGORITHM).apply { initialize(KEY_SIZE) }.generateKeyPair()

    fun signToken(privateKey: PrivateKey, token: ByteArray): ByteArray =
        Signature.getInstance(SIGN_ALGORITHM).run {
            initSign(privateKey)
            update(token)
            sign()
        }

    fun verifyToken(publicKey: PublicKey, token: ByteArray, sig: ByteArray): Boolean =
        runCatching {
            Signature.getInstance(SIGN_ALGORITHM).run {
                initVerify(publicKey)
                update(token)
                verify(sig)
            }
        }.getOrDefault(false)

    /**
     * 私钥 PKCS#8 DER + 公钥 X.509 DER 拼成私有容器后交给 wrapper 加密落盘。
     * 容器： [4B privLen(大端)][priv][pub]。Android 落盘路径见 [adbKeyFile]。
     */
    fun save(keyPair: KeyPair, file: File, wrapper: KeyWrapper) {
        val priv = keyPair.private.encoded
        val pub = keyPair.public.encoded
        val container = ByteBuffer.allocate(4 + priv.size + pub.size)
            .putInt(priv.size).put(priv).put(pub).array()
        file.parentFile?.mkdirs()
        file.writeBytes(wrapper.wrap(container))
    }

    fun load(file: File, wrapper: KeyWrapper): KeyPair {
        val container = wrapper.unwrap(file.readBytes())
        val buf = ByteBuffer.wrap(container)
        val privLen = buf.int
        require(privLen > 0 && privLen <= buf.remaining()) { "bad key container" }
        val priv = ByteArray(privLen).also { buf.get(it) }
        val pub = ByteArray(buf.remaining()).also { buf.get(it) }
        val kf = KeyFactory.getInstance(KEY_ALGORITHM)
        return KeyPair(
            kf.generatePublic(X509EncodedKeySpec(pub)),
            kf.generatePrivate(PKCS8EncodedKeySpec(priv)),
        )
    }
}
