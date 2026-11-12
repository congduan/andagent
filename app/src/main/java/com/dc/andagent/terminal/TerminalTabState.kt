package com.dc.andagent.terminal

/**
 * R83②：多 tab 会话的纯 JVM 状态机（不依赖 Android / pty）。
 *
 * 每个 tab 只持有递增 id；pty 会话与 TerminalView 由 Activity 按 id 持有。上限 5。
 * close 活跃 tab 时选中邻位；close 最后一个 tab 把「finish activity」语义交回 Activity
 * （不改状态，会话由 Activity.onDestroy 统一回收，保留 pid>0 铁律）。
 */
class TerminalTabState(private val maxTabs: Int = MAX_TABS) {

    data class Tab(val id: Int)

    private val _tabs = mutableListOf<Tab>()
    private var nextId = 1

    /** 当前活跃 tab 的下标。 */
    var activeIndex: Int = 0
        private set

    val tabs: List<Tab> get() = _tabs.toList()
    val size: Int get() = _tabs.size
    val isAtCap: Boolean get() = _tabs.size >= maxTabs
    /** 活跃 tab；tabs 为空时返回 null（防未来调用者在空态越界崩溃）。 */
    val activeTab: Tab? get() = _tabs.getOrNull(activeIndex)
    /** 活跃 tab 的 id；tabs 为空（bash 未就绪、onCreate 提前 return）时返回 null，防越界崩溃。 */
    val activeTabId: Int? get() = _tabs.getOrNull(activeIndex)?.id

    /**
     * 按持久化的 tab 数量恢复（钳制 1..maxTabs）。返回全部 tab id 供 Activity 建会话。
     * 恢复后默认选中第一个 tab。
     */
    fun initialize(count: Int): List<Int> {
        val target = count.coerceIn(1, maxTabs)
        while (_tabs.size < target) _tabs.add(Tab(nextId++))
        activeIndex = 0
        return _tabs.map { it.id }
    }

    /** 新建 tab；到达上限返回 null。新 tab 成为活跃 tab（Activity 据此把新视图提到最前）。 */
    fun add(): Int? {
        if (isAtCap) return null
        val tab = Tab(nextId++)
        _tabs.add(tab)
        activeIndex = _tabs.size - 1
        return tab.id
    }

    /** 选中指定下标；越界返回 false。 */
    fun select(index: Int): Boolean {
        if (index !in _tabs.indices) return false
        activeIndex = index
        return true
    }

    /**
     * 关闭指定下标的 tab。
     * - Invalid：下标非法。
     * - LastTab：这是最后一个 tab；不改状态，Activity 应直接 finish()。
     * - Removed：给出被关 id 与新的活跃 id/下标，Activity 据此回收该会话、移除视图并选中邻位。
     */
    fun close(index: Int): CloseResult {
        if (index !in _tabs.indices) return CloseResult.Invalid
        if (_tabs.size == 1) return CloseResult.LastTab(_tabs[0].id)

        val closed = _tabs.removeAt(index)
        activeIndex = when {
            index < activeIndex -> activeIndex - 1                       // 关的是活跃 tab 之前的 tab
            index == activeIndex -> index.coerceAtMost(_tabs.size - 1)   // 关活跃 tab → 选邻位
            else -> activeIndex                                          // 关的是活跃 tab 之后的 tab
        }
        return CloseResult.Removed(closed.id, _tabs[activeIndex].id, activeIndex)
    }

    sealed class CloseResult {
        object Invalid : CloseResult()
        data class LastTab(val closedId: Int) : CloseResult()
        data class Removed(val closedId: Int, val newActiveId: Int, val newActiveIndex: Int) : CloseResult()
    }

    companion object {
        const val MAX_TABS = 5
    }
}
