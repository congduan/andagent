package com.dc.andagent.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * R145c: [NfcTagCache] 纯逻辑测试（无 Android 框架调用）。
 * 覆盖两段式语义：begin 占位 → complete 回填 → takeAwait 消费，代际防乱序覆盖、
 * force 清后写回丢弃、5 分钟过期（一律按碰卡时刻起算）、同卡去重/在途复用。
 * 本轮 review 必修 1/3 单测：同 uid 未过期 pending 再 begin 返回 -2、pending 过期可覆盖；
 * complete 迟到回填按 beginAtMs 拒绝、完整条目过期按碰卡时刻判定、正常回填不受影响。
 * 时钟以 nowMs 参数 / lambda 注入，takeAwait 用 timeoutMs=0 控制不真实等待。
 */
class NfcTagCacheTest {

    @Before
    fun reset() {
        NfcTagCache.clear()
    }

    /* ------------------------ 基本消费链 ------------------------ */

    @Test
    fun beginCompleteThenTakeAwaitReturnsJsonAndClears() {
        val gen = NfcTagCache.begin("AA", nowMs = 0L)
        NfcTagCache.complete(gen, "{\"uid\":\"AA\"}", nowMs = 0L)
        assertEquals("{\"uid\":\"AA\"}", NfcTagCache.takeAwait(timeoutMs = 0, nowMs = { 0L }))
        assertNull(NfcTagCache.takeAwait(timeoutMs = 0, nowMs = { 0L }))  // 取后即清
    }

    @Test
    fun emptyCacheTakeAwaitIsNull() {
        assertNull(NfcTagCache.takeAwait(timeoutMs = 0, nowMs = { 0L }))
    }

    /* --------------------- pending（必修 1 核心） --------------------- */

    @Test
    fun pendingEntryTakeAwaitTimesOutWithoutClearingThenCompletesLater() {
        val gen = NfcTagCache.begin("AA", nowMs = 0L)
        // 后台读卡未完成：短等超时返回 null，但 pending 保留（读卡晚到仍可供下次调用）。
        assertNull(NfcTagCache.takeAwait(timeoutMs = 0, nowMs = { 0L }))
        NfcTagCache.complete(gen, "{\"uid\":\"AA\"}", nowMs = 100L)
        assertEquals("{\"uid\":\"AA\"}", NfcTagCache.takeAwait(timeoutMs = 0, nowMs = { 100L }))
    }

    /* ------------------------ 同卡去重 / 在途复用 ------------------------ */

    @Test
    fun sameUidFreshCompleteEntryBeginReturnsMinusOne() {
        val gen = NfcTagCache.begin("AA", nowMs = 0L)
        NfcTagCache.complete(gen, "j1", nowMs = 0L)
        assertEquals(-1L, NfcTagCache.begin("AA", nowMs = 1_000L))  // 未过期完整条目 → 去重
    }

    @Test
    fun sameUidInFlightPendingBeginReturnsMinusTwoAndKeepsFirstRead() {
        // 必修 1：读卡在途时同卡再次 dispatch → -2 复用，不新开代际覆盖首次读卡结果。
        val gen1 = NfcTagCache.begin("AA", nowMs = 0L)
        assertEquals(-2L, NfcTagCache.begin("AA", nowMs = 100L))
        NfcTagCache.complete(gen1, "j-first-read", nowMs = 100L)
        // 首次完整读卡结果未被丢弃（若误开新代际，gen1 会被静默 drop）。
        assertEquals("j-first-read", NfcTagCache.takeAwait(timeoutMs = 0, nowMs = { 100L }))
    }

    @Test
    fun sameUidPendingExpiredBeginOverwrites() {
        val gen1 = NfcTagCache.begin("AA", nowMs = 0L)
        // pending 已超 TTL（按 beginAtMs 计）→ 允许新 begin 覆盖，返回新代际。
        val gen2 = NfcTagCache.begin("AA", nowMs = NfcTagCache.TTL_MS + 1)
        assertTrue("过期 pending 应正常开新代际", gen2 > 0)
        NfcTagCache.complete(gen1, "j-stale", nowMs = NfcTagCache.TTL_MS + 1)  // 旧代际丢弃
        NfcTagCache.complete(gen2, "j-new", nowMs = NfcTagCache.TTL_MS + 1)    // 新代际回填
        assertEquals("j-new", NfcTagCache.takeAwait(timeoutMs = 0, nowMs = { NfcTagCache.TTL_MS + 1 }))
    }

    /* --------------------- 代际防乱序 --------------------- */

    @Test
    fun staleGenerationCompleteIsDroppedSoNewCardWins() {
        val gen1 = NfcTagCache.begin("AA", nowMs = 0L)
        NfcTagCache.begin("BB", nowMs = 100L)   // 新卡覆盖 → gen1 失效
        NfcTagCache.complete(gen1, "j-old-AA", nowMs = 200L)  // 旧卡线程晚到
        assertNull(NfcTagCache.takeAwait(timeoutMs = 0, nowMs = { 200L }))  // BB 仍 pending，AA 数据被丢弃
    }

    @Test
    fun currentGenerationCompleteWinsOverStale() {
        val gen1 = NfcTagCache.begin("AA", nowMs = 0L)
        val gen2 = NfcTagCache.begin("BB", nowMs = 100L)
        NfcTagCache.complete(gen1, "j-old-AA", nowMs = 150L)  // 旧代际先到 → 丢弃
        NfcTagCache.complete(gen2, "j-bb", nowMs = 200L)      // 当前代际后到 → 回填
        assertEquals("j-bb", NfcTagCache.takeAwait(timeoutMs = 0, nowMs = { 200L }))
    }

    @Test
    fun completeAfterClearIsDropped() {
        // force=true 语义：clear 后旧在途线程不得写回。
        val gen = NfcTagCache.begin("AA", nowMs = 0L)
        NfcTagCache.clear()
        NfcTagCache.complete(gen, "j1", nowMs = 100L)
        assertNull(NfcTagCache.takeAwait(timeoutMs = 0, nowMs = { 100L }))
    }

    @Test
    fun doubleCompleteIsIdempotentSecondIsDropped() {
        val gen = NfcTagCache.begin("AA", nowMs = 0L)
        NfcTagCache.complete(gen, "j1", nowMs = 0L)
        NfcTagCache.complete(gen, "j2", nowMs = 100L)  // 已回填（非 pending）→ 第二次丢弃
        assertEquals("j1", NfcTagCache.takeAwait(timeoutMs = 0, nowMs = { 100L }))
    }

    /* --------------------- 过期（按碰卡时刻起算） --------------------- */

    @Test
    fun completeEntryExpiresAfterFiveMinutes() {
        val gen = NfcTagCache.begin("AA", nowMs = 0L)
        NfcTagCache.complete(gen, "j1", nowMs = 0L)
        assertNull(NfcTagCache.takeAwait(timeoutMs = 0, nowMs = { NfcTagCache.TTL_MS + 1 }))
        // 过期取后即清：重新 begin 不再去重。
        assertEquals(1, NfcTagCache.begin("AA", nowMs = NfcTagCache.TTL_MS + 2).let { if (it == -1L) 0 else 1 })
    }

    @Test
    fun completeEntryFreshAtExactTtlBoundary() {
        val gen = NfcTagCache.begin("AA", nowMs = 0L)
        NfcTagCache.complete(gen, "j1", nowMs = 0L)
        assertEquals("j1", NfcTagCache.takeAwait(timeoutMs = 0, nowMs = { NfcTagCache.TTL_MS }))
    }

    @Test
    fun pendingEntryExpiresToo() {
        // begin 时钟 0、takeAwait 时钟 TTL+1：pending 条目同样过期返回 null 并清空。
        NfcTagCache.begin("AA", nowMs = 0L)
        assertNull(NfcTagCache.takeAwait(timeoutMs = 0, nowMs = { NfcTagCache.TTL_MS + 1 }))
    }

    /* ------------- 必修 3：complete / takeAwait 一律按碰卡时刻判 TTL ------------- */

    @Test
    fun lateCompleteBeyondTapTtlIsRejectedAndTakeAwaitIsNull() {
        // ① pending 超 TTL 后迟到 complete 被拒：碰卡 t=0，回填 t=TTL+1 已过 5 分钟窗口。
        val gen = NfcTagCache.begin("AA", nowMs = 0L)
        NfcTagCache.complete(gen, "j-late", nowMs = NfcTagCache.TTL_MS + 1)
        assertNull(NfcTagCache.takeAwait(timeoutMs = 0, nowMs = { NfcTagCache.TTL_MS + 1 }))
        // 选「清槽」策略：拒绝后条目被清，后续 begin 正常开新代际。
        assertTrue(NfcTagCache.begin("AA", nowMs = NfcTagCache.TTL_MS + 2) > 0)
    }

    @Test
    fun completeEntryExpiryUsesTapTimeNotBackfillTime() {
        // ② 碰卡 t=0、读卡慢到 t=TTL-10 才回填；若按「回填时刻」计还能再活近 5 分钟，
        //    按碰卡时刻计 t=TTL+1 已过期 → 杜绝旧数据借回填时刻续命。
        val gen = NfcTagCache.begin("AA", nowMs = 0L)
        NfcTagCache.complete(gen, "j1", nowMs = NfcTagCache.TTL_MS - 10)
        assertNull(NfcTagCache.takeAwait(timeoutMs = 0, nowMs = { NfcTagCache.TTL_MS + 1 }))
    }

    @Test
    fun lateBackfillStillConsumableWithinTapTtl() {
        // ② 配套：窗口仍从碰卡时刻起算，t=TTL-1（碰卡后）依旧可取。
        val gen = NfcTagCache.begin("AA", nowMs = 0L)
        NfcTagCache.complete(gen, "j1", nowMs = NfcTagCache.TTL_MS - 10)
        assertEquals("j1", NfcTagCache.takeAwait(timeoutMs = 0, nowMs = { NfcTagCache.TTL_MS - 1 }))
    }

    @Test
    fun normalBackfillWithinTtlIsUnaffected() {
        // ③ 正常回填（碰卡后 1s 完成）不受 TTL 校验影响。
        val gen = NfcTagCache.begin("AA", nowMs = 0L)
        NfcTagCache.complete(gen, "j1", nowMs = 1_000L)
        assertEquals("j1", NfcTagCache.takeAwait(timeoutMs = 0, nowMs = { 1_000L }))
    }
}
