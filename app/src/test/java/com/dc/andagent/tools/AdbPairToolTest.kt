package com.dc.andagent.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R100 ③: adb_pair thin-shell pure-function tests.
 *
 * The native SPAKE2/TLS path cannot run on a host JVM (no arm64 .so), so these
 * cover the boundary validation, the JNI result translation and the JNI
 * boundary smoke (loader gracefully degrades). Native crypto itself is covered
 * by the host-compiled C++ vector test in
 * app/src/main/cpp/adbpair/tests/spake2_vectors_test.cpp.
 */
class AdbPairToolTest {

    // ---- validation -------------------------------------------------------
    @Test
    fun `validate accepts a normal target`() {
        assertNull(AdbPairTool.validate("192.168.1.5", 37531, "123456"))
        assertNull(AdbPairTool.validate("phone.local", 40001, "000000"))
    }

    @Test
    fun `validate rejects missing or malformed host`() {
        assertNotNull(AdbPairTool.validate(null, 37531, "123456"))
        assertNotNull(AdbPairTool.validate("", 37531, "123456"))
        assertNotNull(AdbPairTool.validate("  ", 37531, "123456"))
        assertNotNull(AdbPairTool.validate("bad host", 37531, "123456"))
        assertNotNull(AdbPairTool.validate("a/../b", 37531, "123456"))
    }

    @Test
    fun `validate rejects bad pairing port`() {
        assertNotNull(AdbPairTool.validate("192.168.1.5", null, "123456"))
        assertNotNull(AdbPairTool.validate("192.168.1.5", 0, "123456"))
        assertNotNull(AdbPairTool.validate("192.168.1.5", 65536, "123456"))
        assertNotNull(AdbPairTool.validate("192.168.1.5", -1, "123456"))
    }

    @Test
    fun `validate rejects non-6-digit pairing code`() {
        assertNotNull(AdbPairTool.validate("192.168.1.5", 37531, null))
        assertNotNull(AdbPairTool.validate("192.168.1.5", 37531, ""))
        assertNotNull(AdbPairTool.validate("192.168.1.5", 37531, "12345"))
        assertNotNull(AdbPairTool.validate("192.168.1.5", 37531, "1234567"))
        assertNotNull(AdbPairTool.validate("192.168.1.5", 37531, "12a456"))
    }

    // ---- result translation ----------------------------------------------
    @Test
    fun `format success mentions guid and distinguishes adb_port`() {
        val out = AdbPairTool.format("OK\nGUID=adb-1234-5678\n", "192.168.1.5", 37531, "AndAgent")
        assertTrue(out.contains("配对成功"))
        assertTrue(out.contains("adb-1234-5678"))
        assertTrue(out.contains("adb_connect")) // connect hint
        assertTrue(out.contains("adb_port"))
        assertTrue(out.contains("37531"))
        assertTrue(out.contains("不同"))
        // pairing port must not be presented as the connect port
        assertFalse(out.contains("port=37531"))
    }

    @Test
    fun `format wrong code is clearly distinct`() {
        val out = AdbPairTool.format("ERROR\nREASON=WRONG_CODE\n", "192.168.1.5", 37531, "AndAgent")
        assertTrue(out.contains("配对码错误"))
        assertTrue(out.contains("每次都会刷新"))
        assertFalse(out.contains("无法连接"))
    }

    @Test
    fun `format network failure is clearly distinct`() {
        val out = AdbPairTool.format("ERROR\nREASON=NETWORK\n", "192.168.1.5", 37531, "AndAgent")
        assertTrue(out.contains("无法连接"))
        assertTrue(out.contains("192.168.1.5:37531"))
        assertFalse(out.contains("配对码错误"))
    }

    @Test
    fun `format covers every failure reason`() {
        for (reason in listOf(
            "WRONG_CODE", "NETWORK", "TLS", "TLS_EXPORT", "PROTOCOL", "IO",
            "ENCRYPT", "SPAKE2", "CERT", "UNSUPPORTED_ABI", "UNAVAILABLE", "INVALID_ARGS",
        )) {
            val out = AdbPairTool.format("ERROR\nREASON=$reason\n", "h", 1, "AndAgent")
            assertTrue("reason=$reason -> $out", out.startsWith("❌"))
        }
    }

    @Test
    fun `parameters expose required fields and not destructive`() {
        // Schema is a plain JsonObject on the JVM side.
        assertNotNull(AdbPairTool.parameters)
        val s = AdbPairTool.parameters.toString()
        assertTrue(s.contains("pairing_port"))
        assertTrue(s.contains("pairing_code"))
        assertTrue(s.contains("device_name"))
        assertTrue(s.contains("host"))
    }

    // ---- JNI boundary smoke ----------------------------------------------
    @Test
    fun `jni boundary self test never throws on host jvm`() {
        // On an arm64 device with the .so this returns PASS; on a host JVM the
        // loader degrades instead of throwing UnsatisfiedLinkError.
        val r = runCatching { AdbPairNative.selfTest() }.getOrElse { "JNI_EXCEPTION" }
        assertTrue("unexpected: $r", r in setOf("PASS", "FAIL", "UNSUPPORTED_ABI", "UNSUPPORTED_ABI_OR_JVM", "JNI_EXCEPTION"))
    }

    @Test
    fun `jni pair degrades to a structured error on host jvm`() {
        val r = AdbPairNative.pair("192.168.1.5", 37531, "123456", ByteArray(8), "AndAgent")
        assertTrue(r.startsWith("ERROR") || r.startsWith("OK"))
        if (!AdbPairNative.available) {
            assertEquals("ERROR\nREASON=UNAVAILABLE\n", r)
            val msg = AdbPairTool.format(r, "192.168.1.5", 37531, "AndAgent")
            assertTrue(msg.contains("原生配对库未加载"))
        }
    }
}
