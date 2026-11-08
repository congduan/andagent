package com.dc.andagent.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R107a / R102：PrivilegeRouter 纯逻辑覆盖（JVM，不触碰 Android Settings / AdbSessionHub）。
 * 端口读取与 ADB 连接依赖 Android 环境，接口留缝由真机迭代；这里只测判定 / 引导 /
 * 端口变化重试决策三个纯函数，保持断言语义与后端无关。
 */
class PrivilegeRouterTest {

    /* ① 端口读取 / 引导文案生成 */

    @Test
    fun `guidance with detected port embeds the actual loopback endpoint`() {
        val g = PrivilegeRouter.resolveGuidance(37811)
        assertTrue(g.contains("127.0.0.1:37811"))
        assertTrue(g.contains("无线调试"))
        assertTrue(g.contains("Wireless debugging"))
    }

    @Test
    fun `guidance without a port tells the user to enable wireless debugging`() {
        for (p in listOf(-1, 0)) {
            val g = PrivilegeRouter.resolveGuidance(p)
            // 无端口时仍给出连接位置，但用占位符而非具体端口数字。
            assertTrue(g.contains("127.0.0.1:<端口>"))
            assertFalse(g.contains("127.0.0.1:37811"))
            assertTrue(g.contains("开发者选项→无线调试"))
            assertTrue(g.contains("未检测到无线调试端口"))
            assertTrue(g.contains("Wireless debugging"))
        }
    }

    @Test
    fun `static ADB_REQUIRED guidance is bilingual and mentions on-device ADB`() {
        assertTrue(PrivilegeRouter.ADB_REQUIRED.contains("无线调试"))
        assertTrue(PrivilegeRouter.ADB_REQUIRED.contains("Wireless debugging"))
        assertTrue(PrivilegeRouter.ADB_REQUIRED.contains("127.0.0.1"))
    }

    @Test
    fun `backend availability is decided purely by a valid port`() {
        assertTrue(PrivilegeRouter.isBackendAvailable(5555))
        assertTrue(PrivilegeRouter.isBackendAvailable(37811))
        assertFalse(PrivilegeRouter.isBackendAvailable(-1))
        assertFalse(PrivilegeRouter.isBackendAvailable(0))
        assertFalse(PrivilegeRouter.isBackendAvailable(65536))
    }

    @Test
    fun `loopback key is host colon port`() {
        assertEquals("127.0.0.1:37811", PrivilegeRouter.loopbackKey(37811))
        assertEquals("127.0.0.1:5555", PrivilegeRouter.loopbackKey(5555))
    }

    /* ② 端口变化重试逻辑 */

    @Test
    fun `retry when reread port is a valid new port`() {
        assertEquals(39123, PrivilegeRouter.retryPortOrNull(37811, 39123))
    }

    @Test
    fun `no retry when reread port is unchanged`() {
        assertNull(PrivilegeRouter.retryPortOrNull(37811, 37811))
    }

    @Test
    fun `no retry when reread port is missing or out of range`() {
        assertNull(PrivilegeRouter.retryPortOrNull(37811, -1))
        assertNull(PrivilegeRouter.retryPortOrNull(37811, 0))
        assertNull(PrivilegeRouter.retryPortOrNull(37811, 70000))
    }

    @Test
    fun `ShellResult ok and asToolResult mirror the old result contract`() {
        val ok = ShellResult(0, "Success", "")
        assertTrue(ok.ok)
        assertTrue(ok.asToolResult().contains("Success"))
        val fail = ShellResult(-1, "", PrivilegeRouter.ADB_REQUIRED)
        assertFalse(fail.ok)
        assertTrue(fail.asToolResult().contains("exit=-1"))
        assertTrue(fail.asToolResult().contains("无线调试"))
    }
}
