package com.dc.andagent.adb

import com.dc.andagent.adb.AdbClient.AdbSyncResult

/**
 * R127：大文件分块拉取的纯逻辑 + 编排（纯 JVM，无 Android 依赖，便于单测）。
 *
 * 真机根因（会话 doc_08e4cc265017，荣耀 ANY-AN00 / Android 13 无线调试 TLS）：
 * adbd 在数 MB+ 的 SYNC DATA 传输上提前关闭 sync 流（471K png 成功 / 14M mp4 失败
 * 「流提前关闭，已收 0 字节」）。MAX_PAYLOAD 256KB 修复不解决——SYNC DATA 的**体量**
 * 是独立维度。解法：设备侧把大文件 split 成 ≤2MB 分块，逐块走 SYNC RECV 再拼接；
 * shell 通道（stat/df/split/ls/rm）在该机型上正常（echo/ls/getprop 实测 OK）。
 *
 * toybox 事实（AOSP platform/external/toybox，Android 13 / android-s 的 generated/help.h）：
 *  - split 仅支持 [-a SUFFIX_LEN] [-b BYTES] [-l LINES] [INPUT [OUTPUT]]，后缀是
 *    **字母** aa/ab/...（定宽），**没有** -d / --numeric-suffixes；故用默认字母后缀
 *    + `-a 3`（aaa..zzz，最多 26^3=17576 块），字典序即正确拼接顺序。
 *  - stat 支持 -c FORMAT（%s = 文件字节数）；df 支持 [-k] [FILE...]（默认 1K 块）。
 *  - wc/ls/rm/sort 均在 toybox 内；命令经 adbd 的 `/system/bin/sh -c` 执行，glob/`&&`/
 *    单引号均生效（既有 doScreencap 的 `&&`/base64 已实证）。
 *
 * 路线选择：**反应式**（先走原始单次 RECV，命中「流提前关闭」失败再 stat 判尺寸、过阈值
 * 才分块）。理由：用户「小文件正常」的可用路径绝不新增 shell 往返，健康设备上的大文件
 * 也能直接拉成功而不必强行分块；且既有 sync: 协议单测帧序列保持不变。阈值依然是分块
 * 的硬闸门（见 [shouldChunk]）。尺寸探测失败一律 fail-open 回退原始单次结果。
 */
object AdbChunkPull {
    /** 超过该尺寸（默认 4MB）才进入分块路径；可调常量。 */
    const val CHUNK_THRESHOLD_BYTES = 4L * 1024 * 1024
    /** 每块字节数（保守 2MB，低于真机失败体量）；split -b。 */
    const val CHUNK_SIZE_BYTES = 2L * 1024 * 1024
    /** split -a 后缀宽度：3 → aaa..zzz，足够覆盖 max_mb=4096（4GB/2MB=2048 块）。 */
    const val SUFFIX_LEN = 3
    const val PART_DIR = "/data/local/tmp"
    const val PART_PREFIX = "andagent_part_"

    /**
     * Codex review 必修 4（二轮收紧）：分块拼接结果为单 ByteArray 常驻内存（concat 用
     * Int 求和，>2GiB 溢出；拼接峰值≈文件×2 份）。256MB 时峰值 ≈512MB，低端机可承受；
     * 该上限同时钳制 stat 尺寸与逐块累计量（stat 后文件可增长，二轮 review 指出）。
     */
    const val MAX_IN_MEMORY_BYTES = 256L * 1024 * 1024
    val PART_GLOB_BASE: String = "$PART_DIR/$PART_PREFIX"
    val PART_GLOB: String = "$PART_GLOB_BASE*"

    /**
     * 编排与协议层之间的端口：便于用 fake 做「分块失败清理」等纯 JVM 断言。
     * [shell] 映射到 AdbClient.openStream；[pullSingle] 映射到原始单次 SYNC RECV
     * （**不能**递归调用公开 syncPull，否则分块自身的失败会再次触发分块）。
     */
    interface Port {
        fun shell(cmd: String): String
        fun pullSingle(remotePath: String, maxBytes: Long): AdbSyncResult
    }

    /* ------------------------------ 纯函数 ------------------------------ */

    fun shouldChunk(sizeBytes: Long, threshold: Long = CHUNK_THRESHOLD_BYTES): Boolean =
        sizeBytes > threshold

    /** 向上取整的分块数（用于 evidence 预估，不参与实际枚举）。 */
    fun chunkCount(sizeBytes: Long, chunk: Long = CHUNK_SIZE_BYTES): Int =
        ((sizeBytes + chunk - 1) / chunk).coerceAtLeast(1L).toInt()

    /** 与 Locale 无关的 X.X MB 文案（真机工具返回文案用）。 */
    fun formatMb(bytes: Long): String {
        val tenths = bytes * 10 / 1_048_576
        return "${tenths / 10}.${tenths % 10}"
    }

    fun concat(parts: List<ByteArray>): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var off = 0
        for (p in parts) {
            p.copyInto(out, off)
            off += p.size
        }
        return out
    }

    /** 单引号包裹任意文本：内部单引号以 `'\''` 结束重启，杜绝 shell 注入。 */
    fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    fun sizeCmd(remote: String): String = "stat -c %s ${shellQuote(remote)}"
    fun dfCmd(): String = shellDone("df -k $PART_DIR")
    fun splitCmd(remote: String, chunk: Long = CHUNK_SIZE_BYTES): String =
        shellDone("split -b $chunk -a $SUFFIX_LEN ${shellQuote(remote)} $PART_GLOB_BASE")
    fun lsCmd(): String = shellDone("ls -1 $PART_GLOB")
    fun rmCmd(): String = shellDone("rm -f $PART_GLOB")

    /** `stat -c %s` 输出（如 "14680064\n"）取首行首个十进制数；错误/空输出返回 null。 */
    fun parseSize(output: String): Long? {
        val t = output.trimStart()
        if (t.startsWith("ERROR") || t.startsWith("(空)")) return null
        val first = output.lineSequence().firstOrNull() ?: return null
        return Regex("""^\s*(\d+)""").find(first)?.groupValues?.get(1)?.toLongOrNull()
    }

    /** `ls -1 <glob>` 输出过滤成本轮分块文件全路径（排除报错行/未展开的字面量 glob）。
     *  只接受**三位字母后缀**（与 splitCmd -a 3 的 toybox 形态一致，Codex review 建议 1
     *  升级采纳：防止同前缀杂项文件被当作分块拼进结果）；数字后缀形态仅供 sortParts 单测。
     *  完成标记行（AA_CHUNK_CMD_DONE）不是分块文件，天然被后缀正则排除。 */
    fun parseParts(output: String, suffixLen: Int = SUFFIX_LEN): List<String> {
        if (output.trimStart().startsWith("ERROR")) return emptyList()
        val re = Regex("""^[A-Za-z]{$suffixLen}$""")
        return output.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith(PART_GLOB_BASE) && !it.contains('*') && !it.contains("No such file") }
            .filter { re.matches(suffixOf(it)) }
            .toList()
    }

    /** 取分块后缀：`/data/local/tmp/andagent_part_aaa` → `aaa`。 */
    fun suffixOf(path: String): String = path.substringAfterLast('/').removePrefix(PART_PREFIX)

    /**
     * 按后缀排序得到正确拼接顺序：
     *  - toybox split 默认**字母**后缀（定宽 aa..zz）：字典序 == 正确顺序（覆盖简报
     *    「20 块内 OK」以及 -a 3 的全部 aaa..zzz）。
     *  - 若换成**数字**后缀（GNU -d/--numeric-suffixes，Android toybox 不支持）：纯字典序
     *    会把 `..._10` 排在 `..._2` 前，故对全数字后缀按数值排序。两种形态都覆盖，纯函数
     *    便于单测「文件名字典序 vs 数字序问题」。
     */
    fun sortParts(paths: List<String>): List<String> {
        if (paths.isEmpty()) return emptyList()
        val numeric = paths.all { p ->
            val s = suffixOf(p)
            s.isNotEmpty() && s.all { it in '0'..'9' }
        }
        return if (numeric) {
            paths.sortedWith(compareBy<String> { suffixOf(it).toLong() }.thenBy { it })
        } else paths.sorted()
    }

    /** `df -k` 输出取 /data 数据行的 Available（1K 块）；解析不出返回 null（fail-open）。 */
    fun parseDfAvailableKb(output: String): Long? {
        for (line in output.lineSequence()) {
            val tokens = line.trim().split(Regex("""\s+"""))
            if (tokens.isEmpty() || !tokens[0].startsWith("/")) continue // 跳过表头
            val useIdx = tokens.indexOfFirst { tk ->
                tk.endsWith("%") && tk.dropLast(1).isNotEmpty() && tk.dropLast(1).all { it in '0'..'9' }
            }
            if (useIdx >= 2) return tokens[useIdx - 1].toLongOrNull()
        }
        return null
    }

    /** 识别「大 SYNC DATA 被 OEM adbd 提前断流」的失败文案（真机实录 + 本客户端默认文案）。 */
    fun isLargeSyncEarlyClose(reason: String): Boolean =
        reason.contains("流提前关闭") || reason.contains("未收到 DONE") ||
            reason.contains("服务被对端关闭") || reason.contains("已收 0 字节")

    /**
     * 识别 openStream 的非零退出标记（AdbClient 会在输出尾部附 `[exit code: n]`）。
     * Codex review 必修 4：split/ls 的「部分写入 + 非零退出」若只查 ERROR 前缀就会把
     * 截断结果当成功，必须把该形态一律判为命令失败。
     */
    fun shellFailed(output: String): Boolean =
        output.trimStart().startsWith("ERROR") ||
            Regex("""\[exit code: (\d+)]\s*$""").containsMatchIn(output.takeLast(120))

    /**
     * Codex review 二轮必修 1：openStream 在「收到 ACK 后遇到 EOF」时可能返回**空串**——
     * rm/split 这类无输出命令若只查 shellFailed 就会把未完成的执行当成成功（evidence 虚报）。
     * 解法：给每条关键 shell 命令追加 `&& echo MARK`（复用 doScreencap `&& echo SC_OK`
     * 在真机已实证的形态），命令真实跑完才会输出标记；[shellFailed] 同时校验标记缺失。
     */
    const val DONE_MARK = "AA_CHUNK_CMD_DONE"

    fun shellDone(cmd: String): String = "($cmd) && echo $DONE_MARK"

    /** 命令必须「无失败形态 + 带完成标记」才算成功；空输出/缺标记一律判失败。 */
    fun shellOk(output: String): Boolean =
        !shellFailed(output) && output.contains(DONE_MARK)


    /* ------------------------------ 编排 ------------------------------ */

    /**
     * 分块拉取：重入清理 → df 空间检查 → split → ls+排序 → 逐块 pullSingle → 拼接 →
     * finally 收尾清理 → 统一组装返回值。`AdbSyncResult.Pulled` 携带**完整**字节；
     * 调用方对分块无感知。任一分块失败：清理后返回明确错误（含已完成块数），evidence
     * 逐块留痕；支持重入（每次进入先 rm 旧 part 再 split，避免上次失败残留混入 ls）。
     *
     * Codex review 必修 1：body 只算候选结果（var result），finally 先执行 rm 并把其状态
     * 追加进 evidence，最后才用最终快照组装返回值——收尾清理警告不会丢、rm 失败不虚报
     * 「已清理」。必修 2：重入 rm 放在 df 之前且失败即返回可操作错误，避免旧块占满 tmp
     * 时 df 先拒绝、永远到不了清理。
     */
    fun chunked(remotePath: String, size: Long, maxBytes: Long, port: Port): AdbSyncResult {
        val ev = StringBuilder()
        val mb = formatMb(size)
        ev.appendLine(
            "大文件分块模式（文件 ${mb} MB，${size} 字节）：超过 " +
                "${CHUNK_THRESHOLD_BYTES / 1_048_576}MB 阈值，按 " +
                "${CHUNK_SIZE_BYTES / 1_048_576}MB/块分块拉取",
        )

        // 上限早拒：避免无谓 split。
        if (size > maxBytes) {
            return AdbSyncResult.Failed(
                "远程文件 ${mb}MB 超过 ${maxBytes / 1_048_576}MB 上限",
                ev.toString(),
            )
        }

        // Codex review 必修 4：分块结果以单个 ByteArray 内存拼接（concat 的 Int 求和约在
        // 2GiB 处溢出），且分块+拼接双份常驻会顶爆低端机堆。adb_pull 的 max_mb 参数上限
        // (4096) 是给健康设备直连路径的；分块路径收紧为 512MB 硬上限（拼接峰值 ≈ 上限×2）。
        if (size > MAX_IN_MEMORY_BYTES) {
            ev.appendLine("超过分块内存上限 ${MAX_IN_MEMORY_BYTES / 1_048_576}MB，不进入分块")
            return AdbSyncResult.Failed(
                "远程文件 ${mb}MB 超过分块拉取内存上限 ${MAX_IN_MEMORY_BYTES / 1_048_576}MB（拼接需双份常驻内存）；请缩小 max_mb 或改用 ssh/scp 等通道",
                ev.toString(),
            )
        }

        // ① 重入清理：先清旧分块并确认成功（旧块占满 /data/local/tmp 时 df 才不会先拒绝，
        // 否则永远到不了清理、无法重试，必修 2a）。rm 失败直接返回可操作错误，不继续
        // split/ls——避免旧块混入本次枚举（必修 2b）。
        val preRm = port.shell(rmCmd())
        if (!shellOk(preRm)) {
            ev.appendLine("重入清理 rm 失败：${preRm.trim()}")
            return AdbSyncResult.Failed(
                "重入清理失败：无法清除 $PART_DIR 下的旧分块（${preRm.trim()}）；为避免旧分块混入本次拉取已中止，请先手动清理 $PART_GLOB 后重试",
                ev.toString(),
            )
        }
        ev.appendLine("重入清理完成")

        // ② df 空间检查（14M 文件 split 后约双倍占用；解析不出则 fail-open 跳过）。
        // 放在重入清理之后，才能反映清理后的真实可用空间。
        val availKb = parseDfAvailableKb(port.shell(dfCmd()))
        when {
            availKb == null -> ev.appendLine("空间检查跳过：df 输出无法解析（fail-open 继续）")
            availKb * 1024 < size -> {
                ev.appendLine("空间检查失败：可用 ${availKb * 1024} 字节 < 分块所需约 $size 字节")
                return AdbSyncResult.Failed(
                    "目标机 $PART_DIR 剩余空间不足（可用 ${availKb / 1024}MB，分块需额外约 ${mb}MB），请先清理后重试",
                    ev.toString(),
                )
            }
            else -> ev.appendLine("空间检查通过：$PART_DIR 可用 ${availKb / 1024}MB")
        }

        // ③ split + ls + 逐块 pullSingle + 拼接。body 只算「候选结果」：失败文案不含清理
        // 结论、evidence 也不在此定型（finally 才执行 rm 并追加其状态）。最后统一组装，
        // 杜绝 finally 的收尾清理警告进不了返回值 / rm 失败却虚报「已清理」（必修 1）。
        var result: AdbSyncResult? = null
        var cleanupOk = false
        try {
            result = run {
                val splitOut = port.shell(splitCmd(remotePath))
                if (!shellOk(splitOut)) {
                    ev.appendLine("split 失败：$splitOut")
                    return@run AdbSyncResult.Failed("split 分块命令失败：$splitOut")
                }
                val lsOut = port.shell(lsCmd())
                if (!shellOk(lsOut)) {
                    ev.appendLine("ls 枚举失败：$lsOut")
                    return@run AdbSyncResult.Failed("分块枚举失败（ls 非零退出或报错）：$lsOut")
                }
                val parts = sortParts(parseParts(lsOut))
                if (parts.isEmpty()) {
                    ev.appendLine("split 未产生任何分块")
                    return@run AdbSyncResult.Failed("split 未产生分块文件（ls 未枚举到 $PART_GLOB）")
                }
                // 分块数与预估严重不符（>1 块偏差）→ split 部分写入的信号，拒绝拼接。
                val expected = chunkCount(size)
                if (parts.size < expected - 1) {
                    ev.appendLine("分块数不符：枚举到 ${parts.size} 块，预估 $expected 块（疑 split 部分写入）")
                    return@run AdbSyncResult.Failed(
                        "分块数不符：枚举到 ${parts.size} 块但按文件大小预估 $expected 块；为避免拼出截断文件已中止，可直接重试",
                    )
                }
                ev.appendLine("枚举到 ${parts.size} 块（预估 $expected 块），按序逐块 SYNC RECV")

                val collected = ArrayList<ByteArray>(parts.size)
                var assembled = 0L
                for ((i, part) in parts.withIndex()) {
                    when (val r = port.pullSingle(part, maxBytes)) {
                        is AdbSyncResult.Pulled -> {
                            assembled += r.bytes.size
                            // 必修 4（二轮）：maxBytes 参数上限之外，实际累计量也必须服从
                            // 内存硬上限——stat 之后文件仍可能在设备侧增长，逐块累计才是
                            // 真实值，防 concat Int 溢出与内存失控。
                            if (assembled > maxBytes || assembled > MAX_IN_MEMORY_BYTES) {
                                ev.appendLine("第 ${i + 1}/${parts.size} 块后累计 $assembled 字节超上限")
                                return@run AdbSyncResult.Failed(
                                    "远程文件超过 ${minOf(maxBytes, MAX_IN_MEMORY_BYTES) / 1_048_576}MB 上限（已收 $assembled 字节）",
                                )
                            }
                            collected.add(r.bytes)
                            ev.appendLine("第 ${i + 1}/${parts.size} 块 ok（${r.bytes.size} 字节）：${part.substringAfterLast('/')}")
                        }
                        is AdbSyncResult.Failed -> {
                            ev.appendLine("第 ${i + 1}/${parts.size} 块失败（已完成 $i 块）：${r.reason}")
                            return@run AdbSyncResult.Failed(
                                "大文件分块拉取在第 ${i + 1}/${parts.size} 块失败（已完成 $i 块）：${r.reason}；可直接重试（支持重入）",
                            )
                        }
                        is AdbSyncResult.Pushed -> {
                            ev.appendLine("第 ${i + 1}/${parts.size} 块返回意外 Pushed")
                            return@run AdbSyncResult.Failed(
                                "第 ${i + 1}/${parts.size} 块返回意外结果（已完成 $i 块）",
                            )
                        }
                    }
                }
                val bytes = concat(collected)
                // 终检：拼接字节数必须与 stat 报的文件大小一致，防截断/混杂。
                if (bytes.size.toLong() != size) {
                    ev.appendLine("终检失败：拼接 ${bytes.size} 字节 != stat 报告的 $size 字节")
                    return@run AdbSyncResult.Failed(
                        "分块拼接校验失败：实收 ${bytes.size} 字节与远程文件大小 $size 字节不符（疑目标机分块不完整），已丢弃结果；可直接重试",
                    )
                }
                ev.appendLine("拼接完成 ${bytes.size} 字节（共 ${parts.size} 块），逐块全部成功")
                AdbSyncResult.Pulled(bytes)
            }
        } finally {
            // 成功/失败统一收尾清理；rm 状态必须先追加进 evidence，再组装最终返回值。
            val rmOut = port.shell(rmCmd())
            cleanupOk = shellOk(rmOut)
            if (cleanupOk) ev.appendLine("收尾清理：临时分块已清理")
            else ev.appendLine("警告：收尾清理 rm 失败：${rmOut.trim()}")
        }

        // 清理结论按实际 rm 结果生成：成功→「临时分块已清理」，失败→明确警告（不虚报）。
        val cleanupNote = if (cleanupOk) {
            "临时分块已清理"
        } else {
            "警告：临时分块清理失败（/data/local/tmp 残留 andagent_part_*，下次分块拉取会自动先清理）"
        }
        val evidence = ev.toString()
        val finalResult = result ?: AdbSyncResult.Failed("分块拉取未产生结果", evidence)
        return when (finalResult) {
            is AdbSyncResult.Pulled -> AdbSyncResult.Pulled(finalResult.bytes, evidence)
            is AdbSyncResult.Failed -> AdbSyncResult.Failed(finalResult.reason + "；" + cleanupNote, evidence)
            is AdbSyncResult.Pushed -> finalResult
        }
    }
}
