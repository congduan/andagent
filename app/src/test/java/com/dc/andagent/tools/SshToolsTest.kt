package com.dc.andagent.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** R91: 破坏性命令分级与会话池元数据（纯 JVM，不建立真实 SSH 连接）。 */
class SshToolsTest {

    /* ---------------------------- command guard ---------------------------- */

    @Test fun destructiveCommandsAreFlagged() {
        val cases = listOf(
            "rm -rf /var/log/nginx",
            "rm -r -f /tmp/work",
            "sudo rm -f /etc/hosts",
            "rm --recursive /data",
            "mkfs.ext4 /dev/sdb1",
            "dd if=/dev/zero of=/dev/sda bs=1M",
            "echo x > /dev/sda",
            "systemctl restart nginx",
            "systemctl stop postgresql",
            "systemctl disable sshd",
            "reboot",
            "shutdown -h now",
            "chmod -R 777 /var/www",
            "psql -c \"DROP DATABASE prod\"",
            "truncate -s 0 /var/log/app.log",
            "docker rm -f web",
            "docker system prune -af",
            "iptables -F",
            ":(){ :|:& };:",
        )
        cases.forEach { cmd ->
            assertNotNull("应判定为破坏性: $cmd", SshCommandGuard.dangerOf(cmd))
        }
    }

    @Test fun readOnlyCommandsPass() {
        val cases = listOf(
            "ls -la /var/log/nginx",
            "df -h",
            "free -m",
            "systemctl status nginx",
            "cat /etc/nginx/nginx.conf",
            "tail -n 100 /var/log/nginx/access.log",
            "grep -r \"error\" /var/log/nginx",
            "docker ps",
            "ps aux | grep nginx",
            "rm app.log",
            "rm -i note.txt",
            "nginx -t",
            "uptime",
        )
        cases.forEach { cmd ->
            assertNull("不应判定为破坏性: $cmd", SshCommandGuard.dangerOf(cmd))
        }
    }

    @Test fun pipelineWithDestructiveTailIsFlagged() {
        assertNotNull(SshCommandGuard.dangerOf("cd /tmp && rm -rf build"))
        assertNotNull(SshCommandGuard.dangerOf("cat x | sudo tee /dev/sda"))
    }

    @Test fun clipKeepsUtf8Intact() {
        val text = "日志正常\n".repeat(2000)
        val clipped = SshCommandGuard.clipUtf8(text.toByteArray(Charsets.UTF_8), 64)
        assertTrue(clipped.startsWith("日志正常"))
        assertTrue(clipped.contains("已截断"))
        assertFalse(clipped.contains("\uFFFD"))
    }

    @Test fun clipShortOutputUnchanged() {
        assertEquals("ok", SshCommandGuard.clipUtf8("ok".toByteArray(), 4096))
        assertEquals("(空)", SshCommandGuard.clipUtf8(ByteArray(0), 4096))
    }

    /* --------------------------- session registry -------------------------- */

    @Test fun registryAddResolveRemove() {
        val registry = SshSessionRegistry { 1_700_000_000_000L }
        val info = registry.add("10.0.0.5", 22, "root", "vps")
        assertEquals(1, registry.size())
        assertTrue(info.id.startsWith("ssh-"))
        assertEquals(8, info.id.removePrefix("ssh-").length)
        assertEquals(info, registry.get(info.id))
        assertEquals(info, registry.resolve(info.id))
        assertEquals(info, registry.resolve("vps"))
        assertEquals(listOf(info), registry.list())

        assertEquals(info, registry.remove(info.id))
        assertEquals(0, registry.size())
        assertNull(registry.get(info.id))
        assertNull(registry.resolve("vps"))
    }

    @Test fun registryIdsAreUnique() {
        val registry = SshSessionRegistry()
        val ids = (1..64).map { registry.add("host$it", 22, "root", null).id }
        assertEquals(64, ids.toSet().size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateNicknameRejected() {
        val registry = SshSessionRegistry()
        registry.add("a", 22, "root", "prod")
        registry.add("b", 22, "root", "prod")
    }

    @Test fun idLookupWorksWithoutNickname() {
        val registry = SshSessionRegistry()
        val a = registry.add("a", 2200, "ubuntu", null)
        assertNull(registry.resolve("prod"))
        assertEquals(a, registry.resolve(a.id))
    }
}
