package com.dc.andagent.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbFeaturesTest {
    @Test fun bannerAndFallback() {
        assertEquals("host::features=shell_v2", AdbFeatures.hostBanner())
        assertEquals(setOf("shell_v2", "cmd"),
            AdbFeatures.parseBanner("device::ro.product.name=x;features=shell_v2,cmd;\u0000".toByteArray()))
        assertTrue(AdbFeatures.useShellV2(AdbFeatures.parseBanner("device::features=shell_v2\u0000".toByteArray())))
        assertFalse(AdbFeatures.useShellV2(AdbFeatures.parseBanner("device::ro.product.name=x\u0000".toByteArray())))
    }

    @Test fun serviceKeepsCommandVerbatim() {
        val command = "echo 'a,b' \"c,d\""
        assertEquals("shell,v2:$command", AdbFeatures.shellService(command, true))
        assertEquals("shell:$command", AdbFeatures.shellService(command, false))
    }

    @Test fun decoderHandlesInterleavedChunksAcrossWrites() {
        fun packet(id: Int, data: ByteArray): ByteArray = byteArrayOf(id.toByte(), data.size.toByte(), 0, 0, 0) + data
        val bytes = packet(1, "out".toByteArray()) + packet(2, "err".toByteArray()) + packet(3, byteArrayOf(7))
        val decoder = ShellV2Decoder()
        assertTrue(decoder.feed(bytes.copyOfRange(0, 7)).isEmpty())
        val chunks = decoder.feed(bytes.copyOfRange(7, bytes.size))
        assertEquals(listOf(1, 2, 3), chunks.map { it.id })
        assertEquals(listOf("out", "err"), chunks.take(2).map { String(it.data) })
        assertEquals(7, chunks[2].data.single().toInt())
    }
}
