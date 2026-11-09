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

    // ---- R103 必修 1: TLS 空指纹守门 -------------------------------
    @Test
    fun tlsPinMissingRejectsBlankFingerprint() {
        val fp = "ab".repeat(32)
        assertTrue("tls=true + null 指纹必须拒绝", AdbToolRules.tlsPinMissing(true, null))
        assertTrue("tls=true + 空串必须拒绝", AdbToolRules.tlsPinMissing(true, ""))
        assertTrue("tls=true + 全空白必须拒绝", AdbToolRules.tlsPinMissing(true, "   "))
        assertFalse("已锁定指纹的 TLS 目标放行", AdbToolRules.tlsPinMissing(true, fp))
        assertFalse("明文路径不受指纹影响", AdbToolRules.tlsPinMissing(false, null))
        assertFalse(AdbToolRules.tlsPinMissing(false, ""))
    }

    // ---- R121f: host 级目标合并 / 防僵尸连接 / 诊断 ---------------------------
    @Test
    fun mergeHostMigratesEntryToNewPortAndRemovesSiblings() {
        val host = "10.104.102.164"
        val reg = AdbSessionRegistry()
        // 真机证据：同一目标机 3 条历史端口残留（connected/offline 混合）。
        reg.add(AdbTarget(host, 39829))
        reg.add(AdbTarget(host, 42359))
        reg.add(AdbTarget(host, 43033))
        assertEquals(3, reg.size())

        // 换端口 connect 成功：条目迁移到新 host:port，保留 TLS/指纹元数据。
        val current = AdbTarget(host, 44500, tls = true, fingerprint = "ab".repeat(32))
        val stale = reg.mergeHost(current)

        assertEquals("同 host 只保留一条", 1, reg.size())
        assertEquals("迁移到新 host:port", "$host:44500", reg.list().single().key)
        assertEquals("保留 TLS/指纹元数据", current, reg.list().single())
        assertEquals(
            setOf("$host:39829", "$host:42359", "$host:43033"),
            stale.toSet(),
        )
        assertTrue("旧端口条目已移除", reg.list().none { it.port != 44500 })
    }

    @Test
    fun pairingSuccessCollapsesToSingleHostEntry() {
        val host = "10.104.102.164"
        val reg = AdbSessionRegistry()
        reg.add(AdbTarget(host, 39829))
        reg.add(AdbTarget(host, 42359, tls = true, fingerprint = "cd".repeat(32)))
        reg.add(AdbTarget(host, 43033))
        assertEquals(3, reg.size())

        // 模拟 Hub.rememberPairing 的登记侧动作：保留一条占位（最新端口）并标记 TLS + 新指纹，
        // 合并掉同 host 旧条目（Hub 另会 closeOtherLive 关掉旧端口 live）。
        val fp = "ef".repeat(32)
        val sameHost = reg.list().filter { it.host.equals(host, ignoreCase = true) }
        reg.mergeHost(sameHost.last().copy(tls = true, fingerprint = fp))

        assertEquals(1, reg.size())
        assertEquals(43033, reg.list().single().port)
        assertTrue(reg.list().single().tls)
        assertEquals(fp, reg.list().single().fingerprint)
    }

    @Test
    fun resolveKeepsOldPortRefsWorkingAfterMigration() {
        val host = "10.104.102.164"
        val reg = AdbSessionRegistry()
        reg.add(AdbTarget(host, 39829))
        reg.mergeHost(AdbTarget(host, 43033))
        assertEquals(43033, reg.resolve("$host:43033")!!.port) // 精确 key
        assertEquals(43033, reg.resolve(host)!!.port)           // 裸 host
        assertEquals(43033, reg.resolve("$host:39829")!!.port) // 迁移前的旧 host:port 引用不断链
        assertNull(reg.resolve("9.9.9.9:5555"))
        assertNull(reg.resolve("$host:abc"))  // 非数字端口不误匹配
        assertNull(reg.resolve("$host:"))     // 空端口不误匹配
    }

    @Test
    fun staleLiveKeysSelectsOldPortsForReconnectCleanup() {
        val host = "10.104.102.164"
        val keys = listOf("$host:39829", "$host:42359", "$host:43033")
        // client() 重连：保留当前端口，清掉历史端口。
        assertEquals(
            listOf("$host:39829", "$host:42359"),
            AdbToolRules.staleLiveKeys(keys, host, "$host:43033"),
        )
        // connect() 建连前清场：keeperKey=null → 该 host 全部 live 都清。
        assertEquals(keys, AdbToolRules.staleLiveKeys(keys, host, null))
        // 不同 host / 前缀碰撞（host 后必须紧跟冒号）不误伤。
        val mixed = keys + listOf("10.104.102.16:5555", "other:1")
        assertEquals(keys, AdbToolRules.staleLiveKeys(mixed, host, null))
        // keeper 大小写不敏感，不会把自己关掉。
        assertEquals(emptyList<String>(), AdbToolRules.staleLiveKeys(listOf("HOST:5555"), "host", "host:5555"))
    }

    @Test
    fun legacyMultiPortKvLoadsAndSelfHealDetectsDuplicates() {
        val host = "10.104.102.164"
        val legacy = kotlinx.serialization.json.Json.parseToJsonElement(
            """[{"host":"$host","port":41265,"tls":true},""" +
                """{"host":"$host","port":39829,"tls":true},""" +
                """{"host":"$host","port":42359,"tls":true},""" +
                """{"host":"$host","port":43033,"tls":true}]""",
        )
        val decoded = AdbToolRules.decodeTargets(legacy) // 老 KV 读取不崩
        assertEquals(4, decoded.size)
        val reg = AdbSessionRegistry()
        reg.replaceAll(decoded)
        val groups = reg.duplicateHostGroups()
        assertEquals(1, groups.size)
        assertEquals(4, groups.single().size)
        // devices() 断言式自愈：合并后不再出现同 host 多条目。
        reg.mergeHost(groups.single().last())
        assertTrue(reg.duplicateHostGroups().isEmpty())
        assertEquals(1, reg.size())
    }

    @Test
    fun removeHostDropsAllPortsOfOneTarget() {
        val reg = AdbSessionRegistry()
        reg.add(AdbTarget("10.104.102.164", 39829))
        reg.add(AdbTarget("10.104.102.164", 43033))
        reg.add(AdbTarget("192.168.1.5", 5555))
        val removed = reg.removeHost("10.104.102.164")
        assertEquals(2, removed.size)
        assertEquals(1, reg.size())
        assertEquals(5555, reg.list().single().port)
        assertTrue(reg.removeHost("9.9.9.9").isEmpty())
    }

    @Test
    fun formatDurationAndShellStallDetection() {
        assertEquals("0s", AdbToolRules.formatDuration(0L))
        assertEquals("12s", AdbToolRules.formatDuration(12_000L))
        assertEquals("1m5s", AdbToolRules.formatDuration(65_000L))
        assertEquals("2h3m", AdbToolRules.formatDuration(7_380_000L))
        assertEquals("0s", AdbToolRules.formatDuration(-5_000L)) // 负值钳制

        assertTrue(
            AdbToolRules.isShellAckStall(
                "ERROR: ADB shell 执行失败：ADB shell 无响应（8 秒内未收到目标确认）…",
            ),
        )
        assertFalse(AdbToolRules.isShellAckStall("ERROR: ADB shell 执行失败：未连接：请先调用 connect()"))
        assertFalse(AdbToolRules.isShellAckStall("hello world"))
    }
}
