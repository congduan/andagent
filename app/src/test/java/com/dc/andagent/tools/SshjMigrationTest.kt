package com.dc.andagent.tools

import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.userauth.keyprovider.KeyFormat
import net.schmizz.sshj.userauth.keyprovider.KeyProviderUtil
import net.schmizz.sshj.userauth.keyprovider.PKCS8KeyFile
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.SSHClient
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.StringReader
import java.security.KeyPairGenerator
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class SshjMigrationTest {
    @Test fun tofuUsesOpenSshKeyBytesAndExactHostAndPort() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val other = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val file = File.createTempFile("sshj-known-", ".txt")
        try {
            file.writeText("[example.test]:2222 ${SshHostKeys.type(pair.public)} ${SshHostKeys.base64(pair.public)}\n")
            assertTrue(SshHostKeys.verify(file, "example.test", 2222, pair.public))
            assertFalse(SshHostKeys.verify(file, "example.test", 22, pair.public))
            assertFalse(SshHostKeys.verify(file, "evil.test", 2222, pair.public))
            assertFalse(SshHostKeys.verify(file, "example.test", 2222, other.public))
            assertEquals(listOf("ssh-rsa"), SshHostKeys.algorithms(file, "example.test", 2222))
            assertTrue(SshHostKeys.fingerprint(pair.public).startsWith("SHA256:"))
            assertArrayEquals(Buffer.PlainBuffer().putPublicKey(pair.public).compactData, SshHostKeys.blob(pair.public))
            val salt = ByteArray(20) { it.toByte() }
            val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(salt, "HmacSHA1")) }
            val hashed = "|1|${Base64.getEncoder().encodeToString(salt)}|${Base64.getEncoder().encodeToString(mac.doFinal("[example.test]:2222".toByteArray()))}"
            file.writeText("$hashed ${SshHostKeys.type(pair.public)} ${SshHostKeys.base64(pair.public)}\n")
            assertTrue(SshHostKeys.hasHost(file, "example.test", 2222))
            assertTrue(SshHostKeys.verify(file, "example.test", 2222, pair.public))
            assertFalse(SshHostKeys.verify(file, "example.test", 2222, other.public))
        } finally { file.delete() }
    }

    @Test fun passwordAuthenticationUsesSshjPasswordMethod() {
        val client = RecordingClient()
        SshAuthenticator.authenticate(client, "alice", "password", "secret", "", 1_048_576)
        assertEquals("alice:secret", client.passwordCall)
        assertNull(client.keyCall)
    }

    @Test fun encryptedCredentialKeyParsesFromMemoryAndFile() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val secret = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, byteArrayOf('\n'.code.toByte()))
            .encodeToString(pair.private.encoded) + "\n-----END PRIVATE KEY-----\n"
        assertEquals(KeyFormat.PKCS8, KeyProviderUtil.detectKeyFileFormat(StringReader(secret), false))
        val provider = PKCS8KeyFile().apply { init(StringReader(secret)) }
        assertNotNull(provider.private)
        assertNotNull(provider.public)
        val client = RecordingClient()
        SshAuthenticator.authenticate(client, "alice", "key", secret, "", 1_048_576)
        assertNotNull(client.keyCall)
        assertNull(client.passwordCall)
        val file = File.createTempFile("sshj-test-key-", ".pem")
        try {
            file.writeText(secret)
            SshAuthenticator.authenticate(client, "alice", "key", "", file.absolutePath, 1_048_576)
            assertNotNull(client.keyCall)
        } finally { file.delete() }
    }

    private class RecordingClient : SSHClient() {
        var passwordCall: String? = null
        var keyCall: KeyProvider? = null
        override fun authPassword(username: String, password: String) { passwordCall = "$username:$password" }
        override fun authPublickey(username: String, vararg keyProviders: KeyProvider) {
            keyCall = keyProviders.single()
            assertNotNull(keyCall?.private)
        }
    }
}
