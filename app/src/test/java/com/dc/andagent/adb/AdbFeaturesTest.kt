package com.dc.andagent.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbFeaturesTest {
    @Test fun bannerAndFallback() {
        // R124：默认 banner 由可切换的 ACTIVE_FEATURES 决定（真机 A/B 开关）。
        assertEquals(
            "host::features=${AdbFeatures.ACTIVE_FEATURES.joinToString(",")}",
            AdbFeatures.hostBanner(),
        )
        assertEquals("host::features=shell_v2", AdbFeatures.hostBanner(AdbFeatures.FEATURES_MINIMAL))
        assertEquals(
            "host::features=shell_v2,stat_v2,ls_v2",
            AdbFeatures.hostBanner(AdbFeatures.FEATURES_WIRELESS),
        )
        assertEquals(setOf("shell_v2", "cmd"),
            AdbFeatures.parseBanner("device::ro.product.name=x;features=shell_v2,cmd;\u0000".toByteArray()))
        assertTrue(AdbFeatures.useShellV2(AdbFeatures.parseBanner("device::features=shell_v2\u0000".toByteArray())))
        assertFalse(AdbFeatures.useShellV2(AdbFeatures.parseBanner("device::ro.product.name=x\u0000".toByteArray())))
    }

    /** R124：PC adb 完整集（AOSP transport.cpp:1197-1237），但绝不能含 delayed_ack——
     * adbd 协商出 delayed_ack 后会拒绝 A_OPEN(arg1=0)（adb.cpp:507-512）。 */
    @Test fun pcFullFeatureSetIsCompleteAndExcludesDelayedAck() {
        val full = AdbFeatures.FEATURES_PC_FULL
        assertTrue(full.contains("shell_v2"))
        assertTrue(full.contains("cmd"))
        assertTrue(full.contains("sendrecv_v2"))
        assertTrue(full.contains("abb"))
        assertFalse("delayed_ack 会让 A_OPEN(arg1=0) 被 adbd 拒绝", full.contains("delayed_ack"))
        assertEquals(full.size, full.toSet().size)
        assertEquals(full.toSet(),
            AdbFeatures.parseBanner(("host::features=" + full.joinToString(",") + "\u0000").toByteArray()))
    }

    @Test fun serviceKeepsCommandVerbatim() {
        val command = "echo 'a,b' \"c,d\""
        assertEquals("shell,v2:$command", AdbFeatures.shellService(command, true))
        assertEquals("shell:$command", AdbFeatures.shellService(command, false))
        // R124：对齐 iadb-ios 的 `shell,v2,raw:`（ADBClient.swift:218/278）。
        assertEquals("shell,v2,raw:$command", AdbFeatures.shellService(command, true, raw = true))
        assertEquals("shell:$command", AdbFeatures.shellService(command, false, raw = true))
    }

    /** R124 诊断：RX CNXN banner 全文可解码留痕（含 NUL 终止、控制字符转义、限量）。 */
    @Test fun bannerTextDecodesCnxnPayloadForEvidence() {
        val banner = "device::ro.product.name=OnePlus9;ro.product.model=LE2120;" +
            "features=shell_v2,cmd,stat_v2,ls_v2,abb\u0000"
        assertEquals(banner.removeSuffix("\u0000"), AdbFeatures.bannerText(banner.toByteArray()))
        assertEquals("host::features=shell_v2",
            AdbFeatures.bannerText("host::features=shell_v2\u0000trailing".toByteArray()))
        assertEquals("a\\nb\\u0001", AdbFeatures.bannerText("a\nb\u0001".toByteArray()))
        assertTrue(AdbFeatures.bannerText(ByteArray(900) { 'x'.code.toByte() }, limit = 64).length <= 64)
        // OPEN payload（服务名 + NUL）同样可解码。
        assertEquals("shell,v2,raw:getprop ro.build.version.release",
            AdbFeatures.bannerText("shell,v2,raw:getprop ro.build.version.release\u0000".toByteArray()))
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
