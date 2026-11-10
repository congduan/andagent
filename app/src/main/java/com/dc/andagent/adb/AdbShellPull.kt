package com.dc.andagent.adb

import com.dc.andagent.adb.AdbClient.AdbSyncResult
import java.io.ByteArrayOutputStream

/**
 * R130：sync: 通道不可用时的 shell dd|base64 分块拉取（纯 JVM，不依赖 Android，便于单测）。
 *
 * 背景（R130 三方对照结论）：荣耀真机「重连后第一条 pull 成功、之后全部 sync: 被拒」的
 * 根因是【我们客户端】漏读 adbd 关流时回敬的 A_CLSE + SyncStream 无 localId 过滤（见
 * AdbClient.SyncStream 注释与 /tmp/andagent-r130-run.md），已修。但 5 轮修复失败、真机
 * 无法在本环境验证，故把降级路径做扎实：一旦 sync 仍被拒（OEM adbd 真有问题、或修后
 * 仍存在未知时序），仍能绕过 sync: 把任意大小文件拉下来（慢但可用）。
 *
 * 为什么分块：openStream 输出上限 [AdbClient.MAX_OUTPUT_BYTES] = 256KB，而 base64 会
 * 放大 4/3 —— R129 的单发 `base64 <file>` 对 >~192KB 的文件（真机 471K png / 522K mp4）
 * 必然被截断、解码失败。改用设备侧 dd 按块读、逐块 base64：
 *   `dd if='<path>' bs=128K skip=N count=1 2>/dev/null | base64`
 * 每块 base64 ≈177KB，稳落在 256KB 窗口内；逐块解码后拼接，块间不共享 base64 padding
 * （每块独立 padding，故必须逐块 decode，不能拼 base64 字符串再 decode）。
 *
 * 设备侧命令三方实证（真实拉取 AOSP platform/external/toybox 源码，非记忆）：
 *  - `base64` 无 FILE 参数时读 stdin：toys/other/base64.c:112 `loopfiles(toys.optargs, do_base)`；
 *    lib/lib.c:758-759「If no arguments, read from stdin」→ function(0,"-")。
 *  - toybox 默认 76 列换行（base64.c usage `[-w COLUMNS]`，默认 76）：[decodeChunk]
 *    与 R129 一样先剥空白再校验字符集。
 *  - `dd` 在 Android toybox 启用：Android.bp:98 `toys/posix/dd.c`、:274 `"dd"`；
 *    toys/posix/dd.c:23-25 `bs=N / count=N / skip=N`（skip 按 ibs=bs 块计，:197 `skip*=ibs`）。
 *    dd 退出时经 sigatexit(status) 往 stderr 打「N+N records in/out」摘要
 *    （dd.c:66-79），故命令带 `2>/dev/null` 抑制，避免污染 base64 输出。
 *  - 路径单引号转义复用 [AdbChunkPull.shellQuote]，命令段经 adbd
 *    `/system/bin/sh -c` 执行（services.cpp:86-122 只拆 ':' 前的服务参数，命令原样传递）。
 */
object AdbShellPull {
    /** 每块原始字节数：base64 后 ≈177KB < 256KB 输出窗口，留足余量。 */
    const val CHUNK_BYTES = 128 * 1024
    /** 整文件内存上限（拼接需双份常驻，与 [AdbChunkPull.MAX_IN_MEMORY_BYTES] 一致）。 */
    const val MAX_IN_MEMORY_BYTES = 256L * 1024 * 1024
    private const val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/="

    /** `stat -c %s '<path>'`：复用 R127 已实证的尺寸探测。 */
    fun sizeCmd(remote: String): String = AdbChunkPull.sizeCmd(remote)

    /** 第 [index] 块：`(dd ... | base64) && echo <完成标记>`。 */
    fun chunkCmd(remote: String, index: Int): String =
        AdbChunkPull.shellDone(
            "dd if=${AdbChunkPull.shellQuote(remote)} " +
                "bs=$CHUNK_BYTES skip=$index count=1 2>/dev/null | base64",
        )

    /** 向上取整的块数。 */
    fun chunkCount(size: Long): Int =
        ((size + CHUNK_BYTES - 1) / CHUNK_BYTES).coerceAtLeast(0L).toInt()

    /**
     * 把一次 openStream 输出解成一块原始字节。
     *
     * 容错：剥掉 toybox 76 列换行（去空白）、完成标记行、以及 openStream 可能追加的
     * `[exit code: n]` / `[已截断…]` 尾注（任一出现即判失败，不吞截断数据）；校验
     * base64 字符集；补齐 `=` padding 后用 java.util.Base64 解码（API 26+，纯 JVM 可测）。
     * 空块 / 非法字符 / 缺完成标记 / ERROR 一律返回 null，由 [pull] 转成明确错误。
     */
    fun decodeChunk(output: String): ByteArray? {
        val t = output.trimStart()
        if (t.startsWith("ERROR") || t.startsWith("(空)")) return null
        if (!output.contains(AdbChunkPull.DONE_MARK)) return null
        if (output.contains("[已截断") || output.contains("[exit code")) return null
        val body = output.lineSequence()
            .filter { it.trim() != AdbChunkPull.DONE_MARK }
            .joinToString("") { line -> line.filter { c -> !c.isWhitespace() } }
        if (body.isEmpty() || body.any { it !in B64 }) return null
        val padded = when (body.length % 4) {
            0 -> body
            2 -> "$body=="
            3 -> "$body="
            else -> return null
        }
        return runCatching { java.util.Base64.getDecoder().decode(padded) }.getOrNull()
    }

    /**
     * 分块拉取整文件；[shell] 注入 openStream。返回 [AdbSyncResult] 以便工具层与
     * syncPull 统一处理（Pulled 携带完整字节 + evidence）。
     *
     * 尺寸探测失败 / 超限 / 任一块失败一律返回 Failed（带已收块数与 evidence），
     * 绝不返回截断或半截文件；终检拼接字节数必须等于 stat 报告的大小。
     */
    fun pull(remote: String, maxBytes: Long, shell: (String) -> String): AdbSyncResult {
        val ev = StringBuilder()
        val sizeOut = shell(sizeCmd(remote))
        val size = AdbChunkPull.parseSize(sizeOut)
            ?: return AdbSyncResult.Failed(
                "shell 分块回退无法探测远程文件大小（stat 失败）：${sizeOut.trim().take(160)}",
                ev.toString(),
            )
        if (size < 0) return AdbSyncResult.Failed("远程文件大小异常：$size", ev.toString())
        val cap = minOf(maxBytes, MAX_IN_MEMORY_BYTES)
        if (size > maxBytes) {
            return AdbSyncResult.Failed(
                "远程文件 ${AdbChunkPull.formatMb(size)}MB 超过 ${maxBytes / 1_048_576}MB 上限",
                ev.toString(),
            )
        }
        if (size > MAX_IN_MEMORY_BYTES) {
            return AdbSyncResult.Failed(
                "远程文件 ${AdbChunkPull.formatMb(size)}MB 超过分块内存上限 " +
                    "${MAX_IN_MEMORY_BYTES / 1_048_576}MB（拼接需双份常驻）",
                ev.toString(),
            )
        }
        val count = chunkCount(size)
        ev.appendLine(
            "shell dd|base64 分块降级：文件 ${AdbChunkPull.formatMb(size)}MB（$size 字节），" +
                "共 $count 块（每块 ${CHUNK_BYTES / 1024}KiB）",
        )
        if (size == 0L) return AdbSyncResult.Pulled(ByteArray(0), ev.toString())

        val out = ByteArrayOutputStream(size.toInt())
        for (i in 0 until count) {
            val chunkOut = shell(chunkCmd(remote, i))
            val bytes = decodeChunk(chunkOut)
                ?: return AdbSyncResult.Failed(
                    "shell 分块回退在第 ${i + 1}/$count 块失败（已完成 $i 块）：" +
                        chunkOut.trim().take(160),
                    ev.toString(),
                )
            out.write(bytes)
            if (out.size() > cap) {
                return AdbSyncResult.Failed(
                    "shell 分块回退累计超过 ${cap / 1_048_576}MB 上限（已收 ${out.size()} 字节）",
                    ev.toString(),
                )
            }
        }
        val data = out.toByteArray()
        if (data.size.toLong() != size) {
            return AdbSyncResult.Failed(
                "shell 分块拼接校验失败：实收 ${data.size} 字节 != stat 的 $size 字节（已丢弃，未保存截断文件）",
                ev.toString(),
            )
        }
        ev.appendLine("逐块解码完成，终检 ${data.size} 字节与远程大小一致")
        return AdbSyncResult.Pulled(data, ev.toString())
    }
}
