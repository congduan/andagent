package com.dc.andagent.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** R83②：多 tab 状态机单测（add/close/select 边界、上限 5、close-active 邻位选择）。 */
class TerminalTabStateTest {

    @Test
    fun `initialize creates clamped count of tabs with incrementing ids`() {
        val s = TerminalTabState()
        val ids = s.initialize(3)
        assertEquals(listOf(1, 2, 3), ids)
        assertEquals(3, s.size)
        assertEquals(0, s.activeIndex)
        assertEquals(1, s.activeTabId)
    }

    @Test
    fun `initialize clamps zero negative and huge counts to 1 and 5`() {
        assertEquals(1, TerminalTabState().initialize(0).size)
        assertEquals(1, TerminalTabState().initialize(-5).size)
        assertEquals(TerminalTabState.MAX_TABS, TerminalTabState().initialize(99).size)
    }

    @Test
    fun `add appends tab and makes it active`() {
        val s = TerminalTabState()
        s.initialize(1)
        assertEquals(2, s.add()!!)
        assertEquals(2, s.size)
        assertEquals(1, s.activeIndex)
        assertEquals(2, s.activeTabId)
    }

    @Test
    fun `add respects the cap of 5`() {
        val s = TerminalTabState()
        s.initialize(5)
        assertTrue(s.isAtCap)
        assertNull(s.add())
        assertEquals(5, s.size)
    }

    @Test
    fun `add resumes after a close frees a slot`() {
        val s = TerminalTabState()
        s.initialize(5)
        s.close(0)
        assertEquals(4, s.size)
        assertFalse(s.isAtCap)
        assertEquals(6, s.add()!!)   // nextId 递增，不复用已关 id
    }

    @Test
    fun `select rejects invalid indices`() {
        val s = TerminalTabState()
        s.initialize(2)
        assertFalse(s.select(-1))
        assertFalse(s.select(2))
        assertTrue(s.select(1))
        assertEquals(1, s.activeIndex)
        assertEquals(2, s.activeTabId)
    }

    @Test
    fun `closing active tab selects the right neighbour`() {
        val s = TerminalTabState()
        s.initialize(3)             // ids 1,2,3
        s.select(1)                 // 活跃 = id2
        val r = s.close(1) as TerminalTabState.CloseResult.Removed
        assertEquals(2, r.closedId)
        assertEquals(1, r.newActiveIndex)
        assertEquals(3, r.newActiveId)
        assertEquals(2, s.size)
        assertEquals(3, s.activeTabId)
    }

    @Test
    fun `closing the last active tab selects the left neighbour`() {
        val s = TerminalTabState()
        s.initialize(3)
        s.select(2)                 // 活跃 = id3（末位）
        val r = s.close(2) as TerminalTabState.CloseResult.Removed
        assertEquals(3, r.closedId)
        assertEquals(1, r.newActiveIndex)
        assertEquals(2, r.newActiveId)
    }

    @Test
    fun `closing a tab before active shifts active index down`() {
        val s = TerminalTabState()
        s.initialize(3)
        s.select(2)                 // 活跃 = id3
        val r = s.close(0) as TerminalTabState.CloseResult.Removed
        assertEquals(1, r.closedId)
        assertEquals(1, r.newActiveIndex)
        assertEquals(3, r.newActiveId)
    }

    @Test
    fun `closing a tab after active keeps active index`() {
        val s = TerminalTabState()
        s.initialize(3)
        s.select(0)
        s.close(2)
        assertEquals(0, s.activeIndex)
        assertEquals(1, s.activeTabId)
    }

    @Test
    fun `closing the last tab signals finish without mutating state`() {
        val s = TerminalTabState()
        s.initialize(1)
        val r = s.close(0) as TerminalTabState.CloseResult.LastTab
        assertEquals(1, r.closedId)
        assertEquals(1, s.size)      // 不改状态，Activity 直接 finish()
        assertEquals(1, s.activeTabId)
    }

    @Test
    fun `close rejects invalid index`() {
        val s = TerminalTabState()
        s.initialize(2)
        assertTrue(s.close(-1) is TerminalTabState.CloseResult.Invalid)
        assertTrue(s.close(2) is TerminalTabState.CloseResult.Invalid)
        assertEquals(2, s.size)
    }

    @Test
    fun `ids are unique and monotonic across add and close`() {
        val s = TerminalTabState()
        s.initialize(2)             // ids 1,2
        assertEquals(3, s.add()!!)
        s.close(0)                  // 移除 id1
        assertEquals(4, s.add()!!)
        assertEquals(listOf(2, 3, 4), s.tabs.map { it.id })
    }
}
