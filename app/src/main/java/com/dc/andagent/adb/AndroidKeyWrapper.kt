package com.dc.andagent.adb

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val ANDROID_KEYSTORE = "AndroidKeyStore"
private const val WRAP_KEY_ALIAS = "adb_key_wrap"
private const val GCM_IV_SIZE = 12
private const val GCM_TAG_BITS = 128

/** R100 ①：Android 侧密钥落盘路径 filesDir/adb/adbkey（本轮只写好路径，不接 UI/工具）。 */
fun adbKeyFile(context: Context): File = File(File(context.filesDir, "adb"), "adbkey")

/**
 * Android 版 [KeyWrapper]：AES-256-GCM 密钥由 AndroidKeyStore 生成并保管（别名 adb_key_wrap），
 * 密钥材料不出 Keystore。密文格式与 [AesGcmKeyWrapper] 一致（12B IV || 密文+tag）。
 * 仅 Android 运行期使用；JVM 单测不可用 AndroidKeyStore，故走 AesGcmKeyWrapper(固定 key)。
 */
class AndroidKeystoreKeyWrapper : KeyWrapper {
    private val secretKey: SecretKey by lazy { loadOrCreateKey() }

    private fun loadOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(WRAP_KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                WRAP_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    override fun wrap(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun unwrap(wrapped: ByteArray): ByteArray {
        require(wrapped.size > GCM_IV_SIZE) { "wrapped key too short" }
        val iv = wrapped.copyOfRange(0, GCM_IV_SIZE)
        val ct = wrapped.copyOfRange(GCM_IV_SIZE, wrapped.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ct)
    }
}
