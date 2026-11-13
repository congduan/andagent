package com.dc.andagent.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * R91b: 端口转发纯逻辑（不建真实 SSH 连接、不开真实端口）。
 *
 * 覆盖：端口钳制/特权端口拒绝、host/mode 校验、registry 每会话 8 / 全局 16、
 * tunnel_id 格式与冲突重试、stopSession 级联清理。
 */
class SshTunnelRegistryTest {

    private fun registry(random: SecureRandom = SecureRandom()) =
        SshTunnelRegistry(clock = { 1_700_000_000_000L }, random = random)

    /* ------------------------------- rules ------------------------------- */

    @Test
    fun portsOutside1024To65535AreRejected() {
        listOf(0, -1, 80, 443, 1023).forEach { port ->
            val error = SshTunnelRules.portError(port, "local_port")
            assertNotNull("端口 $port 应被拒绝", error)
            assertTrue("特权端口文案应说明原因：$error", error!!.contains("1024"))
        }
        assertNotNull(SshTunnelRules.portError(65_536, "remote_port"))
        assertTrue(SshTunnelRules.portError(65_536, "remote_port")!!.contains("65535"))
    }

    @Test
    fun boundaryPortsPassAndMissingPortIsNamed() {
        assertNull(SshTunnelRules.portError(1024, "local_port"))
        assertNull(SshTunnelRules.portError(65_535, "local_port"))
        assertEquals("缺少 local_port", SshTunnelRules.portError(null, "local_port"))
        // 特权端口必须明确提示「<1024 不允许」，而不是笼统的范围错误。
        assertTrue(SshTunnelRules.portError(80, "local_port")!!.contains("特权端口"))
    }

    @Test
    fun hostsAndModesAreNormalized() {
        assertNull(SshTunnelRules.hostError("10.0.0.5", "remote_host"))
        assertNull(SshTunnelRules.hostError("db.internal", "remote_host"))
        assertNotNull(SshTunnelRules.hostError(null, "remote_host"))
        assertNotNull(SshTunnelRules.hostError(" ", "remote_host"))
        assertNotNull(SshTunnelRules.hostError("bad host", "remote_host"))
        assertNotNull(SshTunnelRules.hostError("a".repeat(254), "remote_host"))

        assertEquals("L", SshTunnelRules.mode("l"))
        assertEquals("R", SshTunnelRules.mode(" R "))
        assertEquals("D", SshTunnelRules.mode("d"))
        assertNull(SshTunnelRules.mode("x"))
        assertNull(SshTunnelRules.mode(null))
    }

    /* ------------------------------ registry ----------------------------- */

    @Test
    fun addListGetRemove() {
        val reg = registry()
        val info = reg.add("ssh-1", "L", "127.0.0.1", 8080, "10.0.0.5", 3306)
        assertTrue(SshTunnelRules.validId(info.id))
        assertEquals(1, reg.size())
        assertEquals(info, reg.get(info.id))
        assertEquals(listOf(info), reg.list())
        assertEquals(1, reg.countForSession("ssh-1"))
        assertEquals(info, reg.remove(info.id))
        assertEquals(0, reg.size())
        assertNull(reg.get(info.id))
    }

    @Test
    fun perSessionLimitIsEight() {
        val reg = registry()
        repeat(SshTunnelRules.MAX_PER_SESSION) { i ->
            reg.add("ssh-1", "L", "127.0.0.1", 8080 + i, "h", 80)
        }
        val error = runCatching { reg.add("ssh-1", "L", "127.0.0.1", 8099, "h", 80) }
            .exceptionOrNull() as? IllegalArgumentException
        assertNotNull(error)
        assertTrue(error!!.message!!.contains("8"))
        // 另一会话不受影响（未达全局上限）。
        assertNotNull(reg.add("ssh-2", "L", "127.0.0.1", 8098, "h", 80))
    }

    @Test
    fun globalLimitIsSixteen() {
        val reg = registry()
        repeat(SshTunnelRules.MAX_PER_SESSION) { i -> reg.add("ssh-1", "L", "127.0.0.1", 8000 + i, "h", 80) }
        repeat(SshTunnelRules.MAX_PER_SESSION) { i -> reg.add("ssh-2", "L", "127.0.0.1", 8100 + i, "h", 80) }
        val error = runCatching { reg.add("ssh-3", "L", "127.0.0.1", 8200, "h", 80) }
            .exceptionOrNull() as? IllegalArgumentException
        assertNotNull(error)
        assertTrue(error!!.message!!.contains("16"))
        // 已满的会话先报会话级 8 条上限；未建转发的会话才看到全局 16 条上限。
        assertTrue(reg.limitError("ssh-1")!!.contains("8"))
        assertTrue(reg.limitError("ssh-3")!!.contains("16"))
    }

    @Test
    fun removeSessionOnlyDropsThatSession() {
        val reg = registry()
        repeat(2) { i -> reg.add("ssh-1", "L", "127.0.0.1", 8080 + i, "h", 80) }
        val keep = reg.add("ssh-2", "R", "127.0.0.1", 9090, null, 8080)
        val removed = reg.removeSession("ssh-1")
        assertEquals(2, removed.size)
        assertEquals(listOf(keep), reg.list())
        assertTrue(reg.removeSession("ssh-1").isEmpty())
    }

    @Test
    fun idsAreUniqueAndWellFormed() {
        val reg = registry()
        val ids = HashSet<String>()
        repeat(200) { i ->
            val info = reg.add("ssh-${i % 4}", "L", "127.0.0.1", 8080, "h", 80)
            assertTrue(SshTunnelRules.validId(info.id))
            assertTrue("tunnel_id 冲突：${info.id}", ids.add(info.id))
            reg.remove(info.id)
        }
        assertEquals(200, ids.size)
    }

    @Test
    fun idCollisionRetriesUntilUnique() {
        val random = ScriptedRandom()
        val reg = registry(random)
        // 第一次 add：8 个 0 → tun-aaaaaaaa。
        random.script.addAll(List(8) { 0 })
        val first = reg.add("ssh-1", "L", "127.0.0.1", 8080, "h", 80)
        assertEquals("tun-aaaaaaaa", first.id)
        // 第二次 add：前 8 个 0 撞车，重试后 8 个 1 → tun-bbbbbbbb。
        random.script.addAll(List(8) { 0 } + List(8) { 1 })
        val second = reg.add("ssh-1", "L", "127.0.0.1", 8081, "h", 80)
        assertEquals("tun-bbbbbbbb", second.id)
    }

    /* -------------------------------- pool ------------------------------- */

    @Test
    fun stopClosesHandleOnceAndRemovesEntry() {
        val pool = SshTunnelPool(registry())
        var closed = 0
        val info = pool.register("ssh-1", "L", "127.0.0.1", 8080, "h", 80) { closed++ }

        assertTrue(pool.stop(info.id))
        assertEquals(1, closed)
        assertEquals(0, pool.size())
        assertFalse(pool.stop(info.id))
        assertEquals(1, closed)
    }

    @Test
    fun stopSessionCascadesOnlyThatSession() {
        val pool = SshTunnelPool(registry())
        var closedS1 = 0
        var closedS2 = 0
        pool.register("ssh-1", "L", "127.0.0.1", 8080, "h", 80) { closedS1++ }
        pool.register("ssh-1", "R", "127.0.0.1", 9090, null, 8081) { closedS1++ }
        val keep = pool.register("ssh-2", "L", "127.0.0.1", 8082, "h", 80) { closedS2++ }

        assertEquals(2, pool.stopSession("ssh-1"))
        assertEquals(2, closedS1)
        assertEquals(0, closedS2)
        assertEquals(listOf(keep), pool.list())
        // 重复级联是幂等的：不会二次关闭句柄。
        assertEquals(0, pool.stopSession("ssh-1"))
        assertEquals(2, closedS1)
    }

    @Test
    fun registerPropagatesLimitWithoutStoringHandle() {
        val pool = SshTunnelPool(registry())
        repeat(SshTunnelRules.MAX_PER_SESSION) { i ->
            pool.register("ssh-1", "L", "127.0.0.1", 8080 + i, "h", 80) {}
        }
        var closed = 0
        assertNotNull(
            runCatching {
                pool.register("ssh-1", "L", "127.0.0.1", 8099, "h", 80) { closed++ }
            }.exceptionOrNull(),
        )
        assertEquals(SshTunnelRules.MAX_PER_SESSION, pool.size())
        assertEquals(0, closed)
    }
}

/** 可编排的 SecureRandom：按脚本返回 nextInt，用于稳定复现 tunnel_id 撞车重试。 */
private class ScriptedRandom : SecureRandom() {
    val script = ArrayDeque<Int>()

    override fun nextInt(bound: Int): Int {
        check(script.isNotEmpty()) { "ScriptedRandom 脚本已耗尽" }
        return script.removeFirst() % bound
    }
}
