package com.dc.andagent.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R83①：TerminalActivity 的可测纯逻辑（env 组装、退出码文案、标题回退）。
 * 不触碰 Android / pty / JNI；vendored 上游代码按简报不写单测。
 */
class TerminalSessionConfigTest {

    private val base = mapOf(
        "PREFIX" to "/data/user/0/com.dc.andagent/files/usr",
        "HOME" to "/data/user/0/com.dc.andagent/files/home",
        "PATH" to "/data/user/0/com.dc.andagent/files/usr/bin:/system/bin",
        "LD_LIBRARY_PATH" to "/data/user/0/com.dc.andagent/files/usr/lib",
        "SHELL" to "/data/user/0/com.dc.andagent/files/usr/bin/bash",
    )

    private fun envOf(entries: Array<String>) = entries.associate {
        val i = it.indexOf('=')
        require(i > 0) { "invalid env entry: $it" }
        it.substring(0, i) to it.substring(i + 1)
    }

    @Test
    fun `buildEnvironment keeps every base key including the shell_exec PREFIX`() {
        val env = envOf(TerminalSessionConfig.buildEnvironment(base))
        for ((key, value) in base) assertEquals(value, env[key])
        assertEquals(base["PREFIX"], env["PREFIX"])
        assertEquals(base["PATH"], env["PATH"])
    }

    @Test
    fun `buildEnvironment injects TERM so TUI programs can run`() {
        val env = envOf(TerminalSessionConfig.buildEnvironment(base))
        assertEquals("xterm-256color", env["TERM"])
        assertEquals(TerminalSessionConfig.TERM, env["TERM"])
    }

    @Test
    fun `buildEnvironment forces TERM even when base supplies another value`() {
        val env = envOf(TerminalSessionConfig.buildEnvironment(base + ("TERM" to "dumb")))
        assertEquals("xterm-256color", env["TERM"])
    }

    @Test
    fun `buildEnvironment emits valid KEY=value entries with no duplicates`() {
        val entries = TerminalSessionConfig.buildEnvironment(base)
        val keys = mutableSetOf<String>()
        for (entry in entries) {
            val i = entry.indexOf('=')
            assertTrue("missing '=' in $entry", i > 0)
            assertTrue("empty key in $entry", i > 0)
            assertFalse("newline in $entry", entry.contains('\n'))
            assertFalse("NUL in $entry", entry.contains('\u0000'))
            assertTrue("duplicate key ${entry.substring(0, i)}", keys.add(entry.substring(0, i)))
        }
        assertEquals(base.size + 1, entries.size)
    }

    @Test
    fun `buildEnvironment does not mutate the caller map`() {
        val mutable = base.toMutableMap()
        TerminalSessionConfig.buildEnvironment(mutable)
        assertEquals(base, mutable)
        assertFalse(mutable.containsKey("TERM"))
    }

    @Test
    fun `buildEnvironment on empty base still yields a usable TERM`() {
        val env = envOf(TerminalSessionConfig.buildEnvironment(emptyMap()))
        assertEquals(mapOf("TERM" to "xterm-256color"), env)
    }

    @Test
    fun `exitMessage renders normal and signal exits`() {
        assertEquals("会话已退出 (exit=0)", TerminalSessionConfig.exitMessage(0))
        assertEquals("会话已退出 (exit=127)", TerminalSessionConfig.exitMessage(127))
        assertEquals("会话已退出 (exit=-9)", TerminalSessionConfig.exitMessage(-9))
    }

    @Test
    fun `sessionTitle falls back when OSC title is null or blank`() {
        assertEquals("终端", TerminalSessionConfig.sessionTitle(null, "终端"))
        assertEquals("终端", TerminalSessionConfig.sessionTitle("   ", "终端"))
        assertEquals("vim /etc/hosts", TerminalSessionConfig.sessionTitle("vim /etc/hosts", "终端"))
    }
}
