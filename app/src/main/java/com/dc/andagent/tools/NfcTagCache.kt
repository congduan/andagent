package com.dc.andagent.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * R145c: 碰卡拉起对话 → 对话内 nfc_read 冲突修复的「Tag 数据透传缓存」纯逻辑层。
 *
 * 根因：R145 在系统 tag intent 到达时（卡还在场）只注入了「请用 nfc_read 读取」的提示词，
 * 但用户此时已把卡拿开；AI 随后调 nfc_read 会进 NfcSession.awaitTag() 的 60 秒等待，
 * 要求「再贴一次卡」——体验断裂「拉起了对话却读不到卡」。
 *
 * 两段式设计：碰卡瞬间在注入链路里 [begin] 占位（同步，dispatch 线程），后台线程读卡
 * 完成后 [complete] 回填；对话内 nfc_read 走 [takeAwait] 短等缓存落地即得，无需二次贴卡；
 * 无缓存时仍走原 awaitTag() 60 秒等待，手动贴卡路径零破坏。
 *
 * 本轮 Codex review 处置（必修 1/3 + 建议 2 已修，必修 2 + 建议 1 不修）：
 * - 必修 1：同 uid 未过期 pending 再 begin 返回 -2 复用在途读卡，不新开代际覆盖，避免
 *   「第一次完整读卡结果被丢弃、第二次卡已移开只剩降级结果」。
 * - 必修 3：TTL 一律以碰卡（[Entry.beginAtMs]）起算；[complete] 迟到回填若已超 TTL
 *   拒绝写回并清槽，[takeAwait] 对完整条目也按 beginAtMs 判过期，杜绝旧数据借回填
 *   时刻「再活 5 分钟」。
 * - 必修 2（缓存未绑对话 / 跨对话可消费）：**明确不修**。缓存的产品语义是「最新碰卡
 *   快照」（单槽 latest-wins + TTL 5min + 取后即清），并非与某条对话绑定的会话资源；
 *   跨对话误消费的代价仅是拿到 5 分钟内的读卡数据（纯读、无副作用）。把缓存生命周期
 *   挂进对话创建链路会显著扩大改动面、收益很小，故保留该边界，由调用方按需 [clear]。
 * - 建议 1（确定无缓存时去掉 takeAwait 的 3s 短等）：**明确不修**。这 3s 正是为
 *   「begin 已占位、后台读卡在途」设计的核心等待路径；「确定无缓存」无法区分「读卡
 *   在途」与「无条目」，立即回落会破坏必修 1 依赖的在途等待语义（详见 [takeAwait]）。
 *
 * 本文件保持纯 JVM（不 import android.nfc），begin/complete/takeAwait/过期/覆盖/去重/
 * 代际丢弃可直接单测；读取 Tag 的 Android 桥接放在 NfcTools.kt / NfcPrompt.kt。
 */

/** intent extra 标记：该 tag 已被正在进行的 nfc_read/write 前台读取消费（见 NfcSession.onNewIntent）。 */
const val NFC_CONSUMED_BY_READ_EXTRA = "andagent.nfc.consumed_by_read"

/**
 * 单槽缓存：只保留最新一张卡（latest wins），5 分钟过期（从碰卡时刻起算），takeAwait
 * 取后即清（一次碰卡只服务一次 nfc_read）。条目两态：pending（begin 占位、后台读卡在途，
 * json=null）与 complete（json 已回填）。[Entry.beginAtMs] 是碰卡时刻、所有 TTL 判断的
 * 唯一起算点；[Entry.completedAtMs] 仅记录回填时刻、不参与过期计算。时钟以 [nowMs] /
 * nowMs lambda 注入便于测试，生产默认 System.currentTimeMillis()。
 */
object NfcTagCache {
    const val TTL_MS = 5 * 60 * 1000L

    private data class Entry(
        val uid: String,
        val json: String?,          // null=pending，非 null=complete
        val gen: Long,
        val beginAtMs: Long,        // 碰卡时刻，TTL 起算点
        val completedAtMs: Long?,   // 回填时刻（仅记录，不参与 TTL），pending 时为 null
    )

    private var entry: Entry? = null
    private var genCounter = 0L

    /**
     * 碰卡 dispatch 线程同步占位，返回本次读卡任务的代际号（供 complete 校验）。
     * 同 uid 且未过期（按 [Entry.beginAtMs] 计）时复用现有条目、不新增后台读卡：
     * - 已是 complete → -1（去重：贴着不动连续 dispatch 不重复后台读，语义不变）；
     * - 仍是 pending  → -2（必修 1：读卡在途，复用该在途任务，避免新代际把首次完整
     *   读卡结果丢弃、卡移开后只剩降级结果）。
     * 同 uid pending 已超 TTL，或 uid 不同 → 创建 pending 条目覆盖旧条目，代际计数器 +1
     * 并返回新代际。代际单调递增：旧卡后台线程晚 complete 时因代际不符被丢弃，永远盖不
     * 掉新卡。
     */
    @Synchronized
    fun begin(uid: String, nowMs: Long = System.currentTimeMillis()): Long {
        val cur = entry
        if (cur != null && cur.uid == uid && nowMs - cur.beginAtMs <= TTL_MS) {
            return if (cur.json != null) -1L else -2L
        }
        val gen = ++genCounter
        entry = Entry(uid, null, gen, beginAtMs = nowMs, completedAtMs = null)
        return gen
    }

    /**
     * 后台读卡线程完成回填。仅当 gen 与当前条目一致且条目仍是 pending 才处理；代际不符
     * （期间又碰了新卡）或条目已被 takeAwait 过期清理 / [clear]（force=true 语义）时静默
     * 丢弃，不写回旧数据。
     *
     * 必修 3：TTL 一律以碰卡时刻 [Entry.beginAtMs] 计，绝不用回填时刻重置窗口。若
     * `nowMs - cur.beginAtMs > [TTL_MS]`（迟到回填的旧碰卡数据已活过 5 分钟），拒绝写回并
     * **清掉条目**（选「清槽」而非「留着不可消费」：让等待者与后续 begin 立即看到空槽、
     * 语义最简单），同时 notifyAll 唤醒 [takeAwait] 等待者。正常回填记录 completedAtMs 并
     * notifyAll。
     */
    @Synchronized
    fun complete(gen: Long, json: String, nowMs: Long = System.currentTimeMillis()) {
        val cur = entry ?: return
        if (cur.gen != gen || cur.json != null) return
        if (nowMs - cur.beginAtMs > TTL_MS) {
            entry = null
            @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
            (this as java.lang.Object).notifyAll()
            return
        }
        entry = cur.copy(json = json, completedAtMs = nowMs)
        @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
        (this as java.lang.Object).notifyAll()
    }

    /**
     * nfc_read 消费入口：完整条目 → 以碰卡时刻 [Entry.beginAtMs] 判过期后取后即清返回；
     * pending 条目（后台读卡在途）→ 在监视器上 wait 剩余时间，complete 唤醒或超时为止；
     * 超时不清 pending（读卡晚到仍可供下次调用）；无条目 → null（调用方回落 awaitTag()
     * 贴卡等待）。
     *
     * 建议 1（无缓存时立即回落、去掉 3s 短等）不修：begin 占位后到 complete 回填之间存在
     * 「条目已 pending、但 json 未落地」的在途窗口，这段短等正是必修 1 复用在途读卡能成立
     * 的前提；若「无完整条目」就立即回落 60s 贴卡等待，会把在途读卡晾在一边，体验反而断裂。
     */
    @Synchronized
    fun takeAwait(timeoutMs: Long = 3_000L, nowMs: () -> Long = System::currentTimeMillis): String? {
        val deadline = nowMs() + timeoutMs
        while (true) {
            val cur = entry
            if (cur == null && nowMs() >= deadline) return null
            if (cur != null) {
                if (cur.json != null) {
                    entry = null
                    return if (nowMs() - cur.beginAtMs > TTL_MS) null else cur.json
                }
                // pending：等到 deadline（complete 回填或被新 begin 覆盖/clear 后再判）。
                val remain = deadline - nowMs()
                if (remain <= 0) return null
                @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
                (this as java.lang.Object).wait(remain)
                continue
            }
            // 无条目：等到 deadline（期间可能 begin 出新条目）。
            val remain = deadline - nowMs()
            if (remain <= 0) return null
            @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
            (this as java.lang.Object).wait(remain)
        }
    }

    @Synchronized
    fun clear() {
        entry = null
        @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
        (this as java.lang.Object).notifyAll()
    }
}

/**
 * 纯函数：把一次碰卡的读卡结果组装成供 nfc_read 消费的缓存 JSON。
 * - [base] 非 null：完整 read 结果（uid/idSize/techList/isNdef/maxSize/.../records 全量），
 *   原样保留并补 source / failedToConnect=false；
 * - [base] 为 null：connect 失败（卡已移开）降级，仅缓存静态信息 uid/idSize/techList，
 *   并如实记录 failedToConnect=true + note（提示可 force=true 重新贴卡）。
 * 始终标注 source="cached-from-tap"，让模型/前端知道这是碰卡快照而非实时贴卡读取。
 */
fun buildCachedTagJson(base: JsonObject?, uidHex: String, idSize: Int, techs: List<String>): JsonObject =
    buildJsonObject {
        if (base != null) {
            base.forEach { (k, v) -> put(k, v) }
        } else {
            put("uid", uidHex)
            put("idSize", idSize)
            putJsonArray("techList") { techs.forEach { add(it) } }
            put("note", "碰卡时无法连接卡片读取 NDEF，仅缓存 UID 与技术列表；如需完整数据请带 force=true 重新贴卡")
        }
        put("source", "cached-from-tap")
        put("failedToConnect", base == null)
    }
