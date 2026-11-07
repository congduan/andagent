package com.dc.andagent.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R103 B：AdbScrcpyToolSpec 纯函数单测（对齐 AdbScrcpyTest.kt 的协议助手测试）。
 * 只调用不含 Android 框架调用的纯助手；真机侧 server 推送 / 解码 / MediaMuxer
 * 列在战报真机清单（本环境无法真测）。
 */
class AdbScrcpyToolSpecTest {

    @Test
    fun namesAndDescriptions() {
        assertEquals(listOf("adb_screencast", "adb_record"), AdbScrcpyToolSpec.NAMES)
        for (n in AdbScrcpyToolSpec.NAMES) {
            assertTrue("$n 描述应非空", AdbScrcpyToolSpec.description(n).isNotBlank())
        }
        assertTrue(AdbScrcpyToolSpec.description(AdbScrcpyToolSpec.SCREENCAST).contains("H.264"))
        assertTrue(AdbScrcpyToolSpec.description(AdbScrcpyToolSpec.RECORD).contains("MP4"))
    }

    @Test
    fun parametersExposeTargetRequired() {
        for (n in AdbScrcpyToolSpec.NAMES) {
            val s = AdbScrcpyToolSpec.parameters(n).toString()
            assertTrue("$n 需要 target", s.contains("target"))
        }
        assertTrue(AdbScrcpyToolSpec.parameters(AdbScrcpyToolSpec.RECORD).toString().contains("duration_s"))
        assertTrue(AdbScrcpyToolSpec.parameters(AdbScrcpyToolSpec.SCREENCAST).toString().contains("max_size"))
    }

    @Test
    fun clampsStayWithinServerLimits() {
        assertEquals(256, AdbScrcpyToolSpec.clampMaxSize(0))
        assertEquals(1920, AdbScrcpyToolSpec.clampMaxSize(9999))
        assertEquals(1280, AdbScrcpyToolSpec.clampMaxSize(null))
        assertEquals(500_000, AdbScrcpyToolSpec.clampBitRate(1))
        assertEquals(20_000_000, AdbScrcpyToolSpec.clampBitRate(99_999_999))
        assertEquals(1, AdbScrcpyToolSpec.clampDuration(-5))
        assertEquals(180, AdbScrcpyToolSpec.clampDuration(999))
        assertEquals(30, AdbScrcpyToolSpec.clampDuration(null))
    }

    @Test
    fun validateTarget() {
        assertNull(AdbScrcpyToolSpec.validateTarget("192.168.1.5:5555"))
        assertNotNull(AdbScrcpyToolSpec.validateTarget(null))
        assertNotNull(AdbScrcpyToolSpec.validateTarget("  "))
    }

    @Test
    fun serverPresentParsesLsOutput() {
        assertTrue(AdbScrcpyToolSpec.serverPresent(
            "-rw-rw-rw- 1 shell shell 71200 2026-11-07 12:00 andagent-scrcpy-server-v2.7.jar\n",
        ))
        assertFalse(AdbScrcpyToolSpec.serverPresent("ls: /data/local/tmp/...: No such file or directory"))
        assertFalse(AdbScrcpyToolSpec.serverPresent("ERROR: ADB shell 执行失败"))
        assertFalse(AdbScrcpyToolSpec.serverPresent("(空)"))
    }

    @Test
    fun buildServerPushCommandsChunksAndDecodes() {
        assertEquals(emptyList<String>(), AdbScrcpyToolSpec.buildServerPushCommands(""))
        assertEquals(emptyList<String>(), AdbScrcpyToolSpec.buildServerPushCommands("ABC", 0))
        val cmds = AdbScrcpyToolSpec.buildServerPushCommands("ABCDEFGH", 3)
        // rm + 3 chunks("ABC","DEF","GH") + base64 -d = 5
        assertEquals(5, cmds.size)
        assertTrue(cmds.first().startsWith("rm -f"))
        assertTrue("首块", cmds[1].contains("'ABC'"))
        assertTrue("末块", cmds[3].contains("'GH'"))
        assertTrue("收尾解码", cmds.last().contains("base64 -d"))
        assertTrue("写入 jar 路径", cmds.last().contains("andagent-scrcpy-server-v2.7.jar"))
        // base64 不含单引号，拼接不会被 shell 引号打断
        val b64 = "YWJjZA=="
        assertTrue(AdbScrcpyToolSpec.buildServerPushCommands(b64).size >= 3)
    }

    @Test
    fun nalUnitsSplitsOn3And4ByteStartCodes() {
        val bytes = byteArrayOf(
            0, 0, 0, 1, 0x67, 0x42, 0, 0x1e, // SPS
            0, 0, 1, 0x68, 0x01, 0x02, 0x03,   // PPS（3 字节起始码，payload 值无关紧要）
        )
        val units = AdbScrcpyToolSpec.nalUnits(bytes)
        assertEquals(2, units.size)
        assertEquals(0x67.toByte(), units[0][4])
        assertEquals(0x68.toByte(), units[1][3])
    }

    @Test
    fun findSpsPpsExtractsCsd() {
        val config = byteArrayOf(
            0, 0, 0, 1, 0x67, 0x42, 0, 0x1e,
            0, 0, 0, 1, 0x68, 0x01, 0x02, 0x03,
        )
        val csd = AdbScrcpyToolSpec.findSpsPps(config)
        assertNotNull(csd)
        assertEquals(7, csd!!.first[4].toInt() and 31)
        assertEquals(8, csd.second[4].toInt() and 31)
        // 缺 PPS / 空输入 → null
        assertNull(AdbScrcpyToolSpec.findSpsPps(byteArrayOf(0, 0, 0, 1, 0x67, 0x42)))
        assertNull(AdbScrcpyToolSpec.findSpsPps(ByteArray(0)))
    }

    @Test
    fun adbToolRegistrationIncludesScrcpyNames() {
        assertTrue(ADB_TOOLS.contains("adb_screencast"))
        assertTrue(ADB_TOOLS.contains("adb_record"))
        assertEquals(ADB_TOOLS.distinct().size, ADB_TOOLS.size) // 无重复注册
    }
}
