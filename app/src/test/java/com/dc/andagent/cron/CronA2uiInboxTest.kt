package com.dc.andagent.cron

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * R105 review 必修项 1：纯 JVM 验证持久化收件箱。
 *
 * enqueueInto/drainFrom 是 Context-free 核心，直接以临时目录驱动，模拟进程重启
 * 后新进程解析同一 filesDir/cron_a2ui 读回围栏；公共 enqueue(context)/drainAll(context)
 * 只是把 Context 解析成目录再委托给这两个函数。
 */
class CronA2uiInboxTest {

    /** object 持有进程内兜底队列，每个用例前用一个空目录 drain 清空，隔离共享状态。 */
    @Before fun clearSharedFallback() {
        CronA2uiInbox.drainFrom(Files.createTempDirectory("a2ui-inbox-reset").toFile())
    }

    private fun jsonFiles(dir: File): List<File> =
        (dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: emptyArray()).toList()

    private val fences = listOf(
        "```a2ui\n{\"fields\":[{\"type\":\"TEXT_INPUT\",\"key\":\"a\"}]}\n```",
        "```a2ui\n{\"fields\":[{\"type\":\"SWITCH\",\"key\":\"b\"}]}\n```",
        "```a2ui\n{\"fields\":[{\"type\":\"CHOICE\",\"key\":\"c\",\"options\":[\"x\"]}]}\n```",
    )

    @Test fun enqueuePersistsToDiskAndDrainsInFileNameOrder() {
        val dir = Files.createTempDirectory("a2ui-inbox").toFile()
        fences.forEach { CronA2uiInbox.enqueueInto(dir, it) }

        // 真的落在磁盘上（不是只停留在内存），进程重启后新实例可读回。
        assertEquals(3, jsonFiles(dir).size)

        // 「新实例」drainFrom：内存状态为空，全部来自磁盘文件；按文件名（单调递增）排序。
        val drained = CronA2uiInbox.drainFrom(dir)
        assertEquals(fences, drained)
    }

    @Test fun drainConsumesFilesAndSecondDrainIsEmpty() {
        val dir = Files.createTempDirectory("a2ui-inbox").toFile()
        fences.forEach { CronA2uiInbox.enqueueInto(dir, it) }

        assertEquals(fences, CronA2uiInbox.drainFrom(dir))
        // 消费语义：返回后删除文件，目录清空。
        assertTrue(jsonFiles(dir).isEmpty())
        assertTrue(CronA2uiInbox.drainFrom(dir).isEmpty())
    }

    @Test fun missingDirectoryReturnsEmpty() {
        val absent = File(Files.createTempDirectory("a2ui-inbox").toFile(), "cron_a2ui")
        assertTrue(CronA2uiInbox.drainFrom(absent).isEmpty())
    }

    @Test fun badDirectoryNeverThrowsAndKeepsFenceInMemoryFallback() {
        // 普通文件当父目录 → mkdirs() 必失败，写盘降级到进程内内存队列，绝不抛异常。
        val blocker = File(Files.createTempDirectory("a2ui-inbox").toFile(), "blocker")
        blocker.writeText("not a directory")
        val badDir = File(blocker, "cron_a2ui")

        CronA2uiInbox.enqueueInto(badDir, "```a2ui\n{}\n```") // 不抛异常
        val drained = CronA2uiInbox.drainFrom(badDir) // 目录不存在，也不抛异常
        assertEquals(listOf("```a2ui\n{}\n```"), drained) // 围栏由内存兜底保住
    }
}
