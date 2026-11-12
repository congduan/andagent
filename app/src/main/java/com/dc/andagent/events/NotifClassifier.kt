package com.dc.andagent.events

/**
 * R117① 通知智能分类：纯 JVM object，零 Android 依赖，可全量 JVM 单测。
 * 三分类 + message 四态，优先级 verification_code > red_packet > express。
 * 中文关键词精确匹配；英文关键词统一用 contains(ignoreCase = true)。
 * 数字码一律从关键词结束位置起、限定字符窗口内「近邻提取」：窗口内无独立 4-8 位数字组
 * 则按 fall-through 落到后续分类，避免日期/金额/订单等远处数字被误写为验证码。
 */
object NotifClassifier {

    data class NotifClassification(
        val category: String,
        val code: String?,
        val sender: String?,
    )

    private val VERIFICATION_KEYWORDS = listOf(
        "验证码", "校验码", "动态码", "确认码",
        "verification code", "verification", "otp", "one-time code",
    )
    private val RED_PACKET_KEYWORDS = listOf("红包", "hongbao", "red packet", "lucky money")
    private val EXPRESS_KEYWORDS = listOf("快递", "包裹", "物流", "取件", "express", "delivery", "parcel")
    private val PICKUP_KEYWORDS = listOf("取件码", "pickup code")

    /** 验证码关键词结束位置起，向后查找的字符窗口长度。 */
    private const val VERIFICATION_WINDOW = 40
    /** 取件码关键词结束位置起，向后查找的字符窗口长度。 */
    private const val PICKUP_WINDOW = 30

    /** 独立 4-8 位数字组：前后不得紧邻数字，长数字串（如 12 位订单号）不会误提。 */
    private val CODE_REGEX = Regex("(?<!\\d)\\d{4,8}(?!\\d)")

    private fun String.containsKeyword(keywords: List<String>): Boolean =
        keywords.any { contains(it, ignoreCase = true) }

    /**
     * 近邻窗口提取：从 [start] 起向后 [windowLen] 字符内找第一个**完整落在窗口内**的独立 4-8 位数字组。
     * 在整串上从 start 搜索，保证 (?<!\d)/(?!\d) 前后断言基于完整上下文；要求命中区间终点也在窗口内
     * （range.last < windowEnd），防止窗口边界把长数字组截断误提。
     */
    private fun codeInWindow(haystack: String, start: Int, windowLen: Int): String? {
        val windowEnd = (start + windowLen).coerceAtMost(haystack.length)
        if (start >= windowEnd) return null
        val match = CODE_REGEX.find(haystack, start) ?: return null
        return match.value.takeIf { match.range.last < windowEnd }
    }

    /** 关键词在整串中的所有出现结束位置（升序）；无命中返回空表。 */
    private fun allKeywordEnds(haystack: String, keyword: String): List<Int> {
        val ends = mutableListOf<Int>()
        var idx = haystack.indexOf(keyword, ignoreCase = true)
        while (idx >= 0) {
            ends.add(idx + keyword.length)
            idx = haystack.indexOf(keyword, ignoreCase = true, startIndex = idx + keyword.length)
        }
        return ends
    }

    /**
     * @param package_ 通知来源包名，本刀保留作未来「包名白名单过滤」入口（R117②），
     *                 不参与本刀分类；sender 无提取规则，恒为 null，不虚报。
     */
    fun classify(title: String, text: String, package_: String): NotifClassification {
        val haystack = "$title $text"

        // 1) 验证码：关键词门槛 + 关键词结束位置起 40 字符近邻窗口内的独立数字组。
        //    依次检查每个关键词的每次命中（远关键词近码优先于近关键词远码）；
        //    全部窗口内找不到 → 不返回 verification_code，继续按优先级落到 red_packet/express/message。
        for (keyword in VERIFICATION_KEYWORDS) {
            for (end in allKeywordEnds(haystack, keyword)) {
                codeInWindow(haystack, end, VERIFICATION_WINDOW)?.let {
                    return NotifClassification("verification_code", it, null)
                }
            }
        }

        // 2) 红包：只按关键词，不提数字。
        if (haystack.containsKeyword(RED_PACKET_KEYWORDS)) {
            return NotifClassification("red_packet", null, null)
        }

        // 3) 快递：关键词命中；含取件码/pickup code 时仅提其后随 30 字符窗口内的独立数字组。
        if (haystack.containsKeyword(EXPRESS_KEYWORDS)) {
            return NotifClassification("express", pickupCode(haystack), null)
        }

        return NotifClassification("message", null, null)
    }

    /** 「后随」：取件码关键词每次出现的结束处向后 30 字符窗口内第一个完整数字组；窗口内无则 null。 */
    private fun pickupCode(haystack: String): String? {
        for (keyword in PICKUP_KEYWORDS) {
            for (end in allKeywordEnds(haystack, keyword)) {
                codeInWindow(haystack, end, PICKUP_WINDOW)?.let { return it }
            }
        }
        return null
    }
}
