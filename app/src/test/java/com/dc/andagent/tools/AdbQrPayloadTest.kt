package com.dc.andagent.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class AdbQrPayloadTest {
    @Test fun settingsCompatibleFormatAndEscaping() {
        assertEquals("WIFI:T:ADB;S:studio-abc;P:1234567890;;",
            AdbQrPayload.build("studio-abc", "1234567890"))
        assertEquals("WIFI:T:ADB;S:a\\;b\\:c\\,d\\\\e;P:p\\;q\\:r\\,s\\\\t;;",
            AdbQrPayload.build("a;b:c,d\\e", "p;q:r,s\\t"))
    }

    @Test fun randomValuesAreValidForDnsSdAndQrPairing() {
        repeat(100) {
            val (ssid, password) = AdbQrPayload.random()
            assertTrue(ssid.matches(Regex("studio-[a-z0-9]{10}")))
            assertTrue(password.matches(Regex("[0-9]{10}")))
            assertTrue(AdbQrPayload.build(ssid, password).startsWith("WIFI:T:ADB;"))
        }
    }

    @Test fun serviceNameSelectsQrPasswordRatherThanEncodingIt() {
        assertEquals("1234567890", AdbQrPayload.passwordForService("studio-abc", "studio-abc", "1234567890"))
        assertNull(AdbQrPayload.passwordForService("studio-other", "studio-abc", "1234567890"))
    }

    @Test fun progressIsMonotonicAndTimeoutNamesTheStalledStage() {
        assertEquals(AdbQrProgress.Stage.RESOLVING, AdbQrProgress.advance(AdbQrProgress.Stage.RESOLVING, AdbQrProgress.Stage.SERVICE_FOUND))
        assertEquals(AdbQrProgress.Stage.CONNECTING, AdbQrProgress.advance(AdbQrProgress.Stage.RESOLVING, AdbQrProgress.Stage.CONNECTING))
        assertTrue(AdbQrProgress.timeout(AdbQrProgress.Stage.DISCOVERING, 120).contains("mDNS 发现"))
        assertTrue(AdbQrProgress.timeout(AdbQrProgress.Stage.CONNECTING, 160).contains("连接或握手"))
    }
}
