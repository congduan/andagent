package com.dc.andagent.tools

import android.app.Application
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.Base64

/**
 * R91.5: ssh_connect 的凭据引用与明文拒绝（不建立真实 SSH 连接）。
 *
 * 所有拒斥分支都在建连前返回，因此这些用例不会触发网络。
 */
@RunWith(RobolectricTestRunner::class)
class SshToolCredentialTest {

    private val app: Application get() = RuntimeEnvironment.getApplication()

    private fun ssh(store: SshCredentialStore? = null) = SshTool(app, "ssh_connect", store)

    @Test
    fun schemaDropsPlaintextSecretParams() {
        val props = ssh().parameters.jsonObject.getValue("properties").jsonObject
        assertFalse("password 必须从 schema 移除", props.containsKey("password"))
        assertFalse("key_passphrase 必须从 schema 移除", props.containsKey("key_passphrase"))
        assertTrue("credential 引用必须在 schema 中", props.containsKey("credential"))
        assertTrue(props.containsKey("key_path"))
    }

    @Test
    fun plaintextPasswordIsRejectedAndNotEchoed() = runBlocking {
        val result = ssh().execute(
            JsonObject(
                mapOf(
                    "host" to JsonPrimitive("example.com"),
                    "user" to JsonPrimitive("root"),
                    "password" to JsonPrimitive("hunter2"),
                ),
            ),
        )
        assertEquals(SshTool.PLAINTEXT_SECRET_REFUSAL, result)
        assertFalse("拒绝文案不得回显密码", result.contains("hunter2"))
    }

    @Test
    fun keyPassphraseIsRejected() = runBlocking {
        val result = ssh().execute(
            JsonObject(
                mapOf(
                    "credential" to JsonPrimitive("vps"),
                    "key_passphrase" to JsonPrimitive("unlock-me"),
                ),
            ),
        )
        assertEquals(SshTool.PLAINTEXT_SECRET_REFUSAL, result)
        assertFalse(result.contains("unlock-me"))
    }

    @Test
    fun missingCredentialPointsToNativeEntry() = runBlocking {
        val result = ssh().execute(
            JsonObject(
                mapOf(
                    "host" to JsonPrimitive("example.com"),
                    "user" to JsonPrimitive("root"),
                ),
            ),
        )
        assertTrue("应提示原生入口", result.contains("SSH 凭据"))
        assertTrue("应带 deeplink", result.contains("andagent://credentials"))
        assertFalse("不应再索要密码", result.contains("请提供密码"))
    }

    @Test
    fun unknownCredentialIsReportedWithGuidance() = runBlocking {
        val result = ssh().execute(JsonObject(mapOf("credential" to JsonPrimitive("ghost"))))
        assertTrue(result.contains("ghost"))
        assertTrue(result.contains("不存在"))
        assertTrue(result.contains("SSH 凭据"))
    }

    @Test
    fun credentialBoundHostCannotBeRedirected() = runBlocking {
        val store = SshCredentialStore(testDir(), Base64Cipher())
        store.put("vps", "10.0.0.9", 22, "root", "password", "topsecret")
        val result = ssh(store).execute(
            JsonObject(
                mapOf(
                    "credential" to JsonPrimitive("vps"),
                    "host" to JsonPrimitive("evil.example.com"),
                ),
            ),
        )
        assertTrue(result.startsWith("ERROR:"))
        assertTrue(result.contains("不一致"))
        assertFalse("冲突报错不得回显 secret", result.contains("topsecret"))
    }

    @Test
    fun credentialDeleteRequiresConfirm() = runBlocking {
        val tool = SshCredentialTool(app, "credential_delete")
        val result = tool.execute(
            JsonObject(
                mapOf(
                    "nickname" to JsonPrimitive("vps"),
                    "confirm" to JsonPrimitive("false"),
                ),
            ),
        )
        assertTrue(result.startsWith("ERROR:"))
        assertTrue(result.contains("confirm=true"))
    }

    @Test
    fun credentialListReportsEmptyStoreWithoutSecrets() = runBlocking {
        val tool = SshCredentialTool(app, "credential_list")
        val result = tool.execute(JsonObject(emptyMap()))
        assertTrue(result.contains("SSH 凭据") || result.contains("凭据"))
        assertFalse(result.contains("secret"))
    }

    private fun testDir(): File =
        File(app.cacheDir, "ssh-cred-test-${System.nanoTime()}").apply { mkdirs() }

    /** 仅供测试的可逆编码（真正的加密强度由 SshCredentialStoreTest 的 AES-GCM 覆盖）。 */
    private class Base64Cipher : KvCipher {
        override fun encrypt(plaintext: String): String =
            "aXY=:" + Base64.getEncoder().encodeToString(plaintext.toByteArray(Charsets.UTF_8))

        override fun decrypt(payload: String): String? = try {
            val sep = payload.indexOf(':')
            String(Base64.getDecoder().decode(payload.substring(sep + 1)), Charsets.UTF_8)
        } catch (_: Throwable) {
            null
        }
    }
}
