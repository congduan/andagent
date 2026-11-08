package com.dc.andagent.cron

import android.content.Context
import java.io.File

/**
 * R105 review 必修项 1：进程被杀后 cron 队列表单不丢。
 *
 * 旧实现把围栏只放在 AgentBridge companion 的纯内存 ArrayDeque 里：后台 cron
 * 触发后，Android 可能在用户点通知前回收进程，表单随之永久丢失。本收件箱把
 * 每个 fence 落成 context.filesDir/cron_a2ui 下的独立文件，进程重启后仍可读回。
 *
 * 消费语义：pushA2uiForm 仍同时入 AgentBridge 的内存队列用于即时渲染；
 * consumeA2uiForms 返回「内存队列 + drainAll」，drain 后删除文件——JS 侧
 * appendA2uiForm 会把消息写进会话 KV，故 drain 即安全点。
 *
 * 纯 JVM 可测：所有磁盘逻辑落在 [enqueueInto]/[drainFrom]（只依赖 java.io.File），
 * [enqueue]/[drainAll] 仅是把 Context 解析成目录的薄封装。所有 IO 都包
 * runCatching，绝不抛异常；写盘失败降级到进程内内存队列 [fallback]（等价于旧的
 * 内存队列行为，且 AgentBridge 的内存队列也始终持有该围栏）。
 */
object CronA2uiInbox {
    private const val SUBDIR = "cron_a2ui"
    private val lock = Any()

    /** IO 失败时的进程内兜底，保证围栏在当前进程内不丢。 */
    private val fallback = java.util.ArrayDeque<String>()

    private fun dirOf(context: Context): File = File(context.filesDir, SUBDIR)

    /** 持久化写入一条围栏（供 AgentBridge.pushA2uiForm 调用）。 */
    fun enqueue(context: Context, fence: String) = enqueueInto(dirOf(context), fence)

    /** 读取并消费全部围栏（供 AgentBridge.consumeA2uiForms 调用）。 */
    fun drainAll(context: Context): List<String> = drainFrom(dirOf(context))

    /**
     * Context-free 核心：写入单调递增文件名（System.currentTimeMillis() +
     * 防碰撞后缀）的 .json 文件，内容就是纯文本 fence。同步块内生成文件名，
     * 保证同毫秒/多线程不碰撞；写盘失败降级到 [fallback]。
     */
    internal fun enqueueInto(dir: File, fence: String) {
        val ok = runCatching {
            synchronized(lock) {
                if (dir.exists() || dir.mkdirs()) {
                    var stamp = System.currentTimeMillis()
                    var file = File(dir, "$stamp.json")
                    while (file.exists()) {
                        stamp++
                        file = File(dir, "$stamp.json")
                    }
                    file.writeText(fence)
                    true
                } else {
                    false
                }
            }
        }.getOrDefault(false)
        if (!ok) {
            synchronized(lock) {
                fallback.addLast(fence)
                while (fallback.size > 20) fallback.removeFirst()
            }
        }
    }

    /**
     * Context-free 核心：按文件名排序读回全部，读完即删（消费语义）。
     * 目录不存在返回空表；内存兜底队列排在最前并一并清空。
     */
    internal fun drainFrom(dir: File): List<String> {
        val mem = synchronized(lock) {
            val out = ArrayList(fallback)
            fallback.clear()
            out
        }
        val disk = runCatching<List<String>> {
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
                ?: return@runCatching emptyList()
            val out = ArrayList<String>(files.size)
            for (f in files.sortedBy { it.name }) {
                runCatching { out.add(f.readText()) }
                runCatching { f.delete() }
            }
            out
        }.getOrElse { emptyList() }
        return mem + disk
    }
}
