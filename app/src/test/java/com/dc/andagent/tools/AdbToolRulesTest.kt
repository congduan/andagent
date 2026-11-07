package com.dc.andagent.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** R100 ②：破坏性命令确认闸 + 会话注册表 + max_mb/校验 纯 JVM 断言。 */
class AdbToolRulesTest {

    @Test
    fun dangerousCommandRequiresConfirm() {
        val cmd = "rm -rf /sdcard/Download"
        assertNotNull("应判定破坏性", AdbToolRules.dangerOf(cmd))
        val gate = AdbToolRules.confirmGate(cmd, false)
        assertNotNull("未 confirm 必须拦截", gate)
        assertTrue(gate!!.startsWith("CONFIRM_REQUIRED"))
        assertTrue(gate.contains("rm -rf"))
        assertNull("confirm=true 必须放行", AdbToolRules.confirmGate(cmd, true))
    }

    @Test
    fun guardDetectsTheSameDangersAsSshExec() {
        listOf(
            "rm -rf /var/log/nginx",
            "mkfs.ext4 /dev/sdb1",
            "reboot",
            "dd if=/dev/zero of=/dev/sda",
        ).forEach { cmd ->
            assertNotNull("应判定为破坏性: $cmd", AdbToolRules.dangerOf(cmd))
            assertNotNull(AdbToolRules.confirmGate(cmd, false))
        }
    }

    @Test
    fun safeCommandPassesGateWithoutConfirm() {
        listOf("echo hi", "getprop ro.product.model", "screencap -p /data/local/tmp/a.png", "ls /sdcard")
            .forEach { cmd -> assertNull("安全命令不应拦截: $cmd", AdbToolRules.confirmGate(cmd, false)) }
    }

    @Test
    fun confirmedDangerousCommandIsAllowed() {
        assertNull(AdbToolRules.confirmGate("mkfs.ext4 /dev/sdb1", true))
        assertNull(AdbToolRules.confirmGate("reboot", true))
    }

    @Test
    fun maxMbMatchesSshDownloadSemantics() {
        assertEquals(512, AdbToolRules.maxMb(null))
        assertEquals(1, AdbToolRules.maxMb(0))
        assertEquals(4096, AdbToolRules.maxMb(99999))
        assertEquals(128, AdbToolRules.maxMb(128))
    }

    @Test
    fun validatesHostPortAndRemotePath() {
        assertNull(AdbToolRules.validateHost("192.168.1.5"))
        assertNotNull(AdbToolRules.validateHost("bad host"))
        assertNotNull(AdbToolRules.validateHost("a/b"))
        assertTrue(AdbToolRules.validPort(5555))
        assertTrue(!AdbToolRules.validPort(0))
        assertNull(AdbToolRules.validateRemotePath("/sdcard/DCIM/a.png"))
        assertNotNull(AdbToolRules.validateRemotePath("../etc/passwd"))
        assertNotNull(AdbToolRules.validateRemotePath("/sdcard/../x"))
        assertNotNull(AdbToolRules.validateRemotePath("relative/path"))
    }

    @Test
    fun registryAddResolveListAndRemove() {
        val reg = AdbSessionRegistry()
        reg.add(AdbTarget("192.168.1.5", 5555))
        reg.add(AdbTarget("10.0.0.2", 37371))
        assertEquals(2, reg.size())
        assertEquals(5555, reg.resolve("192.168.1.5:5555")!!.port)
        assertEquals(5555, reg.resolve("192.168.1.5")!!.port) // 省略端口也可
        assertEquals(37371, reg.resolve("10.0.0.2")!!.port)
        assertNull(reg.resolve("9.9.9.9"))
        assertNotNull(reg.remove("192.168.1.5:5555"))
        assertEquals(1, reg.size())
    }

    @Test
    fun targetsSurviveKvRoundtrip() {
        val targets = listOf(AdbTarget("192.168.1.5", 5555), AdbTarget("10.0.0.2", 37371))
        val decoded = AdbToolRules.decodeTargets(AdbToolRules.encodeTargets(targets))
        assertEquals(targets, decoded)
        // 损坏/缺失字段被跳过，不抛异常
        assertEquals(emptyList<AdbTarget>(), AdbToolRules.decodeTargets(null))
        val reg = AdbSessionRegistry()
        reg.replaceAll(decoded)
        assertEquals(2, reg.size())
    }

    // ---- R103 A: TLS 目标字段 / 配对指纹 KV -------------------------------
    @Test
    fun tlsTargetsSurviveKvRoundtrip() {
        val fp = "ab".repeat(32)
        val targets = listOf(
            AdbTarget("192.168.1.5", 5555),                     // 旧记录：无 tls/fp
            AdbTarget("10.0.0.2", 37371, tls = true, fingerprint = fp),
        )
        val decoded = AdbToolRules.decodeTargets(AdbToolRules.encodeTargets(targets))
        assertEquals(targets, decoded)
        assertEquals(false, decoded[0].tls)
        assertNull(decoded[0].fingerprint)
        assertEquals(true, decoded[1].tls)
        assertEquals(fp, decoded[1].fingerprint)
        // 旧格式 JSON（仅 host/port）向后兼容
        val legacy = kotlinx.serialization.json.Json.parseToJsonElement(
            "[{\"host\":\"h\",\"port\":5555}]",
        )
        assertEquals(AdbTarget("h", 5555), AdbToolRules.decodeTargets(legacy).single())
    }

    @Test
    fun invalidFingerprintIsDropped() {
        assertTrue(AdbToolRules.isValidFingerprint("ab".repeat(32)))
        assertTrue(AdbToolRules.isValidFingerprint("AB".repeat(32)))
        assertFalse(AdbToolRules.isValidFingerprint("short"))
        assertFalse(AdbToolRules.isValidFingerprint("z".repeat(64)))
        assertFalse(AdbToolRules.isValidFingerprint(null))
        val bad = kotlinx.serialization.json.Json.parseToJsonElement(
            "[{\"host\":\"h\",\"port\":5555,\"tls\":true,\"fingerprint\":\"nope\"}]",
        )
        assertNull(AdbToolRules.decodeTargets(bad).single().fingerprint)
    }

    @Test
    fun pairingsSurviveKvRoundtrip() {
        val fp = "cd".repeat(32)
        val map: Map<String, String?> = linkedMapOf("192.168.1.5" to fp, "10.0.0.2" to null)
        val decoded = AdbToolRules.decodePairings(AdbToolRules.encodePairings(map))
        assertEquals(fp, decoded["192.168.1.5"])
        assertTrue("已配对但无指纹的 host 仍在表里（自动 TLS 仍触发）", decoded.containsKey("10.0.0.2"))
        assertNull(decoded["10.0.0.2"])
        assertEquals(emptyMap<String, String?>(), AdbToolRules.decodePairings(null))
    }
}
