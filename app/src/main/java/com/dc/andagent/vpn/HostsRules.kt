package com.dc.andagent.vpn

/**
 * Hosts 风格的域名拦截规则（纯 Kotlin，可在 JVM 单测里验证）。
 *
 * 接受三种输入行：
 *  1. hosts 文件格式 `0.0.0.0 ads.example.com`（IP 必须是黑洞/回环地址才视为拦截规则）
 *  2. 裸域名 `ads.example.com`
 *  3. `*.example.com` / `.example.com`（等价写法，匹配本就按后缀逐级进行）
 *
 * 匹配是"精确 + 父域后缀"：规则 `example.com` 命中 `a.b.example.com`，反之不成立。
 */
object HostsRules {

    /** 一行里可以出现多条规则时的分隔符（hosts 用空白，纯文本用逗号/分号）。 */
    private const val SEPARATORS = " \t,;"

    /** 视为"黑洞"的 IP：只有这些 IP 的 hosts 行才当作拦截规则。 */
    private val SINKHOLE = setOf("0.0.0.0", "127.0.0.1", "::", "::1", "255.255.255.255")

    private val LABEL = Regex("^[a-z0-9]([a-z0-9_-]*[a-z0-9])?$")

    data class ParseReport(val domains: Set<String>, val lines: Int, val invalid: Int)

    /**
     * 规范化一个域名；返回 null 表示这行不是可用的域名规则
     * （空行、注释、裸 IP、单标签主机名等）。
     */
    fun normalize(raw: String): String? {
        var d = raw.trim().lowercase()
        if (d.isEmpty()) return null
        if (d.startsWith("#") || d.startsWith("!") || d.startsWith("//")) return null
        d = d.removePrefix("*.").removePrefix(".").removeSuffix(".")
        if (d.length !in 4..253) return null
        if (!d.contains('.')) return null
        val labels = d.split('.')
        if (labels.any { it.isEmpty() || it.length > 63 || !LABEL.matches(it) }) return null
        // 顶级域必须是纯字母：过滤掉裸 IP 与带端口/路径的行
        if (!labels.last().all { it.isLetter() }) return null
        return d
    }

    /** 解析 hosts 文本 / 纯域名列表，返回去重后的规则集。 */
    fun parse(text: String): ParseReport {
        val domains = LinkedHashSet<String>()
        var lines = 0
        var invalid = 0
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!") || line.startsWith("//")) continue
            lines++
            val tokens = line.split(*SEPARATORS.toCharArray()).filter { it.isNotEmpty() }
            if (tokens.isEmpty()) {
                invalid++
                continue
            }
            val candidates = if (tokens.size >= 2 && tokens[0] in SINKHOLE) {
                tokens.drop(1)
            } else if (tokens.size == 1) {
                tokens
            } else if (tokens[0].contains('.') && tokens[0].any { it.isLetter() }) {
                // 形如 `ads.example.com 其他说明` 的宽松写法
                listOf(tokens[0])
            } else {
                emptyList()
            }
            var added = false
            for (candidate in candidates) {
                val d = normalize(candidate) ?: continue
                domains.add(d)
                added = true
            }
            if (!added) invalid++
        }
        return ParseReport(domains, lines, invalid)
    }

    /** 域名是否命中规则集（精确或父域后缀）。 */
    fun matches(rawDomain: String, rules: Set<String>): Boolean {
        if (rules.isEmpty()) return false
        val d = normalize(rawDomain) ?: return false
        if (d in rules) return true
        var i = d.indexOf('.')
        while (i in 1 until d.length - 1) {
            if (d.substring(i + 1) in rules) return true
            i = d.indexOf('.', i + 1)
        }
        return false
    }

    /** 命中规则的域名（用于报告"到底哪条规则生效"）。 */
    fun matchedRule(rawDomain: String, rules: Set<String>): String? {
        if (rules.isEmpty()) return null
        val d = normalize(rawDomain) ?: return null
        if (d in rules) return d
        var i = d.indexOf('.')
        while (i in 1 until d.length - 1) {
            val suffix = d.substring(i + 1)
            if (suffix in rules) return suffix
            i = d.indexOf('.', i + 1)
        }
        return null
    }

    /** 内置基础拦截集：常见广告 / 归因 / 行为分析域名。 */
    fun builtIn(): Set<String> = BUILT_IN

    private val BUILT_IN: Set<String> = setOf(
        // Google 广告与分析
        "doubleclick.net",
        "googleadservices.com",
        "googlesyndication.com",
        "google-analytics.com",
        "googletagmanager.com",
        "googletagservices.com",
        "adservice.google.com",
        "pagead2.googlesyndication.com",
        "adsystem.com",
        "amazon-adsystem.com",
        // 国际程序化广告
        "adnxs.com",
        "rubiconproject.com",
        "pubmatic.com",
        "openx.net",
        "criteo.com",
        "criteo.net",
        "taboola.com",
        "outbrain.com",
        "scorecardresearch.com",
        "moatads.com",
        "adsrvr.org",
        "casalemedia.com",
        "smartadserver.com",
        "3lift.com",
        "sharethrough.com",
        "teads.tv",
        // 移动广告 SDK
        "adcolony.com",
        "applovin.com",
        "unityads.unity3d.com",
        "vungle.com",
        "chartboost.com",
        "ironsrc.com",
        "supersonicads.com",
        "inmobi.com",
        "startapp.com",
        "mopub.com",
        "fyber.com",
        "smaato.net",
        // 归因 / 埋点
        "adjust.com",
        "appsflyer.com",
        "branch.io",
        "kochava.com",
        "umeng.com",
        "umengcloud.com",
        "cnzz.com",
        "talkingdata.com",
        "growingio.com",
        "sensorsdata.cn",
        "bugly.qq.com",
        // 国内广告联盟
        "adview.cn",
        "domob.cn",
        "mobads.baidu.com",
        "cpro.baidu.com",
        "pos.baidu.com",
        "tanx.com",
        "alimama.com",
        "mmstat.com",
        "simba.taobao.com",
        "pingstart.com",
    )
}
