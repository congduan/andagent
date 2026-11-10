package com.dc.andagent.tools

import android.content.Context

/**
 * R124 b: 前台保活服务（[SshKeepAliveService]）的引用计数 + 服务生命周期动作，SSH + ADB 共用。
 *
 * 纯 JVM 内核（tags 集合）+ 可注入的服务动作（start/stop lambda），便于单测。
 * 动作是 `() -> Unit`：生产安装时闭包捕获 applicationContext（[installActions]），
 * 测试注入 no-op（[installForTest]）——内核不持有也不传递任何 Context，无可空调用点。
 *
 * ## 线程模型（Codex R124 review 必修 1/2 的修复）
 * 引用计数变更与 start/stop 动作共用 [monitor] 这一把锁：acquire→start、release→stop
 * 都是原子的。这样保证：
 * - 不会「计数全空却没停服」：最后一个 release 在锁内串行地决定并执行 stop；
 * - 不会「非空却停了服」：任何 stop 都发生在 monitor 锁内且此刻引用已空，
 *   与并发的新 acquire 天然互斥，不可能交错。
 * - ADB 侧（AdbSessionHub）在 hub 锁内调 acquire/release，再进入本对象锁；
 *   SSH 侧在 SshSessionPool 锁内调。两处获取顺序固定为「外层池锁 → 本锁」，
 *   无反向路径，不存在锁序反转。
 *
 * 幂等语义：同 tag 重复 acquire / 未知或重复 release 均为无害 no-op，计数不会变负。
 * tag 约定：`ssh:<session_id>` 与 `adb`（ADB 用单 tag 表示「存在 live 连接」）。
 *
 * ## 「有引用无服务」自愈（必修 3）
 * 服务可能因 startForeground 异常自停（onStartCommand catch 后 stopSelf）或被系统
 * 回收，而引用仍在。[running] 脏标记在每次动作成功后置位；引用仍在但标记为
 * 「未运行」时，后续任何 [acquire]（以及 ADB client() 复用既有连接时的 [repair]）
 * 都会重试拉起，避免静默失效。
 *
 * ## 动作未安装（R124 收口）
 * [startAction]/[stopAction] 为 null（Application.onCreate 尚未跑，或 JVM 单测未注入）
 * 时：acquire 返回 false（无法拉起，引用仍登记，后续 acquire/repair 自愈重拉）、
 * release 只复位 running 不调 stop、repair no-op——全部无异常、无崩溃。
 */
object KeepAliveRefs {
    private val tags = HashSet<String>()
    @Volatile private var startAction: (() -> Unit)? = null
    @Volatile private var stopAction: (() -> Unit)? = null
    private val monitor = Object()

    /** 服务动作是否已确认成功拉起。仅在持有 monitor 时读写。 */
    private var running = false

    /** 安装生产服务动作（应用 init 时调用一次，幂等覆盖）；闭包捕获应用上下文。 */
    fun installActions(context: Context) {
        val app = context.applicationContext
        startAction = { SshKeepAliveService.ensureStarted(app) }
        stopAction = { SshKeepAliveService.stop(app) }
    }

    /** 仅测试注入。 */
    internal fun installForTest(onStart: () -> Unit, onStop: () -> Unit) {
        startAction = onStart
        stopAction = onStop
    }

    /** 仅测试注入：清掉动作。 */
    internal fun clearActionsForTest() {
        startAction = null
        stopAction = null
    }

    /**
     * 登记一个保活需求并确保服务在跑。返回 true 表示本次执行了 start 动作
     * （引用 0→1，或引用仍在但服务此前未确认在跑——自愈重拉）。
     * start 动作在 [monitor] 锁内执行（startService 是异步投递，不阻塞调用线程）；
     * 动作未安装时返回 false；动作抛异常时 running 置 false 并向上传播，由调用方决定回滚与否。
     */
    fun acquire(tag: String): Boolean = synchronized(monitor) {
        val wasEmpty = tags.isEmpty()
        tags.add(tag)
        val needStart = (wasEmpty && tags.isNotEmpty()) || !running
        if (needStart) {
            val s = startAction ?: return@synchronized false
            try {
                s()
                running = true
            } catch (e: Exception) {
                running = false
                throw e
            }
        }
        needStart
    }

    /**
     * 释放一个保活需求；返回 true 仅当引用集合从 1→0（此刻已在锁内串行执行 stop）。
     * 未知或重复 release 幂等（返回 false，无副作用）。stop 动作未安装时只复位 running；
     * stop 异常吞掉（尽力而为）：服务多跑一次 onStartCommand / stopService 幂等，
     * 不会造成正确性问题。
     */
    fun release(tag: String): Boolean = synchronized(monitor) {
        val existed = tags.remove(tag)
        val drained = existed && tags.isEmpty()
        if (drained) {
            running = false
            try {
                stopAction?.invoke()
            } catch (e: Exception) {
                // never-crash：停服失败不影响返回值与计数正确性。
            }
        }
        drained
    }

    /** 引用仍在但怀疑服务已死时的补拉入口；生产路径上 [acquire] 已自动覆盖，
     *  ADB client() 复用既有连接（未发生「重连」事件）时调用本方法兜底。 */
    fun repair() = synchronized(monitor) {
        if (tags.isNotEmpty() && !running) {
            val s = startAction ?: return@synchronized
            try {
                s()
                running = true
            } catch (e: Exception) {
                running = false
            }
        }
    }

    /**
     * R124 二轮 review 必修 3：running=true 只代表 startForegroundService 投递成功，
     * 服务可能随后在 onStartCommand 里异常自停（或被系统回收）——此时必须复位 running，
     * 否则后续 acquire/repair 因「自以为在跑」而永不重拉，自愈闭环失效。
     * 由 [SshKeepAliveService] 在死亡路径（onDestroy）调用；线程安全，幂等。
     */
    fun markServiceDead() = synchronized(monitor) { running = false }

    @Synchronized fun size(): Int = synchronized(monitor) { tags.size }

    @Synchronized fun contains(tag: String): Boolean = synchronized(monitor) { tag in tags }

    /** 仅供单测复位全局状态，生产不调用。 */
    @Synchronized fun clear() {
        synchronized(monitor) {
            tags.clear()
            running = false
        }
    }
}
