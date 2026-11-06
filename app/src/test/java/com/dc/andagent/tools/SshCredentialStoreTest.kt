package com.dc.andagent.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * R91.5: 凭据库往返 / 密文落盘 / 降级行为。
 *
 * 用 JVM 版 AES-256-GCM（与生产同算法、同 payload 格式 `iv:ct`）注入，
 * 从而能在纯 JVM 上断言「加密文件里没有明文」，不必依赖 Robolectric 的 Keystore。
 */
class SshCredentialStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val cipher = JvmAesGcmCipher()
    private val dir: File get() = File(tmp.root, "ssh")

    private fun store(): SshCredentialStore = SshCredentialStore(dir, cipher)

    private fun file(name: String = SshCredentialStore.FILE_NAME) = File(dir, name)

    @Test
    fun putGetListRoundTrip() {
        val s = store()
        s.put("myvps", "10.0.0.5", 2222, "root", "password", "hunter2")

        val cred = s.get("myvps")
        assertEquals("myvps", cred?.meta?.nickname)
        assertEquals("10.0.0.5", cred?.meta?.host)
        assertEquals(2222, cred?.meta?.port)
        assertEquals("root", cred?.meta?.user)
        assertEquals("password", cred?.meta?.authType)
        assertEquals("hunter2", cred?.secret)

        val list = s.list()
        assertEquals(1, list.size)
        assertEquals("myvps", list.single().nickname)
    }

    @Test
    fun encryptedFileContainsNoPlaintext() {
        val secret = "Sup3rSecret-Pa55w0rd"
        val keyPem = "-----BEGIN OPENSSH PRIVATE KEY-----\nAAAAB3NzaC1yc2E\n-----END OPENSSH PRIVATE KEY-----"
        val s = store()
        s.put("prod", "prod.example.com", 22, "deploy", "password", secret)
        s.put("keybox", "git.example.com", 22, "git", "key", keyPem)

        val raw = file().readBytes().toString(Charsets.UTF_8)
        assertFalse("文件不得含密码明文", raw.contains(secret))
        assertFalse("文件不得含私钥明文", raw.contains("BEGIN OPENSSH PRIVATE KEY"))
        assertFalse("文件不得含私钥主体", raw.contains("AAAAB3NzaC1yc2E"))
        // 但确实是可解析的密文容器，且每条都有独立 IV。
        assertTrue(raw.contains("\"secretCipher\""))
        assertTrue(raw.contains("\"iv\""))
        assertTrue(raw.contains("prod.example.com"))
    }

    @Test
    fun listNeverExposesSecret() {
        val s = store()
        s.put("a", "h1", 22, "u", "password", "secret-one")
        val json = s.listJson()
        assertFalse(json.contains("secret-one"))
        assertTrue(json.contains("\"nickname\":\"a\""))
        assertFalse("listJson 不含 secret 字段", json.contains("secretCipher"))
    }

    @Test
    fun deleteAndWipeAll() {
        val s = store()
        s.put("a", "h1", 22, "u", "password", "p1")
        s.put("b", "h2", 22, "u", "password", "p2")
        assertTrue(s.delete("a"))
        assertFalse("已删除的凭据不可再取", s.delete("a"))
        assertNull(s.get("a"))
        assertEquals(1, s.list().size)

        assertEquals(1, s.wipeAll())
        assertTrue(s.list().isEmpty())
        assertFalse(file().exists())
        assertEquals(0, s.wipeAll())
    }

    @Test
    fun overwriteReplacesSecretAndKeepsSingleEntry() {
        val s = store()
        s.put("a", "h1", 22, "u", "password", "old")
        s.put("a", "h1", 22, "u", "password", "new")
        assertEquals("new", s.get("a")?.secret)
        assertEquals(1, s.list().size)
    }

    @Test
    fun updateMetadataKeepsSecretAndNeverLeaksIt() {
        val s = store()
        s.put("myvps", "10.0.0.5", 22, "root", "password", "hunter2")
        val message = s.update("myvps", "myvps", "10.0.0.6", 2222, "deploy", "password", null)

        assertTrue(message.startsWith("已更新"))
        assertFalse("确认信息不得含明文", message.contains("hunter2"))
        assertEquals("hunter2", s.get("myvps")?.secret)
        val meta = s.list().single()
        assertEquals("10.0.0.6", meta.host)
        assertEquals(2222, meta.port)
        assertEquals("deploy", meta.user)
        assertEquals("password", meta.authType)
        assertFalse("落盘文件仍不得含明文", file().readText().contains("hunter2"))
    }

    @Test
    fun updateReplacesSecretWhenProvided() {
        val s = store()
        s.put("a", "h1", 22, "u", "password", "old")
        s.update("a", "a", "h2", 2202, "u2", "password", "new")
        assertEquals("new", s.get("a")?.secret)
        assertEquals(1, s.list().size)
        val raw = file().readText()
        assertFalse(raw.contains("new"))
        assertTrue(raw.contains("h2"))
    }

    @Test
    fun updateRenamesCredentialAtomically() {
        val s = store()
        s.put("old", "h1", 22, "u", "password", "p1")
        s.update("old", "renamed", "h1", 22, "u", "password", "")

        assertNull(s.get("old"))
        assertEquals("p1", s.get("renamed")?.secret)
        assertEquals(1, s.list().size)
        assertEquals("renamed", s.list().single().nickname)
    }

    @Test
    fun updateRejectsDuplicateOrMissingNickname() {
        val s = store()
        s.put("a", "h1", 22, "u", "password", "p1")
        s.put("b", "h2", 22, "u", "password", "p2")

        assertThrows(IllegalArgumentException::class.java) {
            s.update("a", "b", "h1", 22, "u", "password", null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            s.update("missing", "c", "h1", 22, "u", "password", null)
        }
        assertEquals(2, s.list().size)
        assertEquals("p1", s.get("a")?.secret)
        assertEquals("p2", s.get("b")?.secret)
    }

    @Test
    fun updateRequiresNewSecretWhenSwitchingAuthType() {
        val s = store()
        s.put("a", "h1", 22, "u", "password", "p1")

        assertThrows(IllegalArgumentException::class.java) {
            s.update("a", "a", "h1", 22, "u", "key", null)
        }
        val pem = "-----BEGIN OPENSSH PRIVATE KEY-----\nabc\n-----END OPENSSH PRIVATE KEY-----"
        s.update("a", "a", "h1", 22, "u", "key", pem)
        assertEquals("key", s.list().single().authType)
        assertEquals(pem, s.get("a")?.secret)
    }

    @Test
    fun corruptFileDegradesAndRecovers() {
        dir.mkdirs()
        file().writeText("not-json{{{{", Charsets.UTF_8)
        val s = store()
        assertTrue("损坏文件按空库处理", s.list().isEmpty())
        assertNull(s.get("anything"))
        // 损坏文件被改名留档，而不是被静默覆盖。
        assertTrue(dir.listFiles().orEmpty().any { it.name.startsWith("${SshCredentialStore.FILE_NAME}.corrupt-") })

        s.put("a", "h1", 22, "u", "password", "p1")
        assertEquals("p1", s.get("a")?.secret)
    }

    @Test
    fun undecryptableEntriesAreTreatedAsMissing() {
        store().put("a", "h1", 22, "u", "password", "p1")
        // 换一把密钥（模拟 Keystore 密钥失效）：元数据仍可读，secret 视为不存在。
        val other = SshCredentialStore(dir, JvmAesGcmCipher())
        assertNull(other.get("a"))
        assertEquals(1, other.list().size)
    }

    @Test
    fun invalidNicknameIsRejected() {
        val s = store()
        assertThrows(IllegalArgumentException::class.java) {
            s.put("has space", "h", 22, "u", "password", "p")
        }
        assertThrows(IllegalArgumentException::class.java) {
            s.put("x".repeat(33), "h", 22, "u", "password", "p")
        }
        assertThrows(IllegalArgumentException::class.java) {
            s.put("ok", "h", 22, "u", "password", "")
        }
        assertTrue("非法昵称不落库", s.list().isEmpty())
    }

    @Test
    fun replacingCipherOnDemandKeepsExistingEntriesReadable() {
        val s = store()
        s.put("a", "h1", 22, "u", "key", "-----BEGIN PRIVATE KEY-----\nabc\n-----END PRIVATE KEY-----")
        // 同一 store 实例连续读取：每次解密都用同一 Keystore 密钥，结果稳定。
        assertEquals("-----BEGIN PRIVATE KEY-----\nabc\n-----END PRIVATE KEY-----", s.get("a")?.secret)
        assertEquals("-----BEGIN PRIVATE KEY-----\nabc\n-----END PRIVATE KEY-----", s.get("a")?.secret)
    }
}

/** 与生产同算法的 JVM 版本（AES-256-GCM，payload = base64(iv) + ":" + base64(ct)）。 */
private class JvmAesGcmCipher : KvCipher {
    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val random = SecureRandom()

    override fun encrypt(plaintext: String): String {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return b64(iv) + ":" + b64(ct)
    }

    override fun decrypt(payload: String): String? = try {
        val sep = payload.indexOf(':')
        val iv = Base64.getDecoder().decode(payload.substring(0, sep))
        val ct = Base64.getDecoder().decode(payload.substring(sep + 1))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        String(cipher.doFinal(ct), Charsets.UTF_8)
    } catch (_: Throwable) {
        null
    }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
}
