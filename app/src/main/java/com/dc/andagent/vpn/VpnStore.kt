package com.dc.andagent.vpn

import android.content.Context
import android.content.SharedPreferences

/**
 * 本地 VPN 的持久化配置（SharedPreferences `andagent_vpn`）。
 *
 * 只存非敏感数据：分流开关/包名、hosts 规则、上游 DNS、拦截应答风格。
 * 全部读写都在工具线程或 service 内部完成，不进 WebView。
 */
class VpnStore(private val prefs: SharedPreferences) {

    companion object {
        const val PREFS_NAME = "andagent_vpn"

        const val SPLIT_OFF = "off"
        const val SPLIT_INCLUDE = "include"
        const val SPLIT_EXCLUDE = "exclude"

        private const val KEY_SPLIT_MODE = "split_mode"
        private const val KEY_PACKAGES = "split_packages"
        private const val KEY_HOSTS_ENABLED = "hosts_enabled"
        private const val KEY_HOSTS = "hosts_domains"
        private const val KEY_UPSTREAM = "dns_upstream"
        private const val KEY_BLOCK_STYLE = "block_style"

        private const val MAX_RULES = 50_000

        fun from(context: Context): VpnStore =
            VpnStore(context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))

        fun normalizeSplitMode(raw: String?): String = when (raw?.trim()?.lowercase()) {
            SPLIT_INCLUDE, "allow", "whitelist", "白名单" -> SPLIT_INCLUDE
            SPLIT_EXCLUDE, "deny", "blacklist", "黑名单" -> SPLIT_EXCLUDE
            else -> SPLIT_OFF
        }
    }

    /** off | include（仅这些应用走 VPN） | exclude（这些应用不走 VPN）。 */
    var splitMode: String
        get() = normalizeSplitMode(prefs.getString(KEY_SPLIT_MODE, SPLIT_OFF))
        set(value) {
            prefs.edit().putString(KEY_SPLIT_MODE, normalizeSplitMode(value)).apply()
        }

    var packages: List<String>
        get() = prefs.getString(KEY_PACKAGES, "").orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        set(value) {
            prefs.edit().putString(KEY_PACKAGES, value.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(",")).apply()
        }

    var hostsEnabled: Boolean
        get() = prefs.getBoolean(KEY_HOSTS_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_HOSTS_ENABLED, value).apply()
        }

    /** zero（返回 0.0.0.0） | nxdomain（返回不存在）。 */
    var blockStyle: String
        get() = if (prefs.getString(KEY_BLOCK_STYLE, DnsPacket.STYLE_ZERO) == DnsPacket.STYLE_NXDOMAIN) {
            DnsPacket.STYLE_NXDOMAIN
        } else {
            DnsPacket.STYLE_ZERO
        }
        set(value) {
            prefs.edit().putString(
                KEY_BLOCK_STYLE,
                if (value.equals(DnsPacket.STYLE_NXDOMAIN, ignoreCase = true)) DnsPacket.STYLE_NXDOMAIN else DnsPacket.STYLE_ZERO,
            ).apply()
        }

    /** 自定义上游 DNS，空串 = 跟随物理网络的 DNS。 */
    var upstream: String
        get() = prefs.getString(KEY_UPSTREAM, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_UPSTREAM, value.trim()).apply()
        }

    fun upstreamList(): List<String> =
        upstream.split(',', ' ', ';').map { it.trim() }.filter { it.isNotEmpty() }.take(3)

    var customDomains: Set<String>
        get() = HostsRules.parse(prefs.getString(KEY_HOSTS, "").orEmpty()).domains
        set(value) {
            prefs.edit().putString(KEY_HOSTS, value.sorted().joinToString("\n")).apply()
        }

    /** 当前生效的规则集（内置 + 自定义，受总开关控制）。 */
    fun activeRules(): Set<String> = if (!hostsEnabled) {
        emptySet()
    } else {
        HostsRules.builtIn() + customDomains
    }

    fun addDomains(text: String): HostsRules.ParseReport {
        val parsed = HostsRules.parse(text)
        if (parsed.domains.isEmpty()) return parsed
        val merged = LinkedHashSet(customDomains)
        merged.addAll(parsed.domains)
        customDomains = merged.take(MAX_RULES).toSet()
        return parsed.copy(domains = parsed.domains)
    }

    fun removeDomains(text: String): Int {
        val targets = HostsRules.parse(text).domains
        if (targets.isEmpty()) return 0
        val current = LinkedHashSet(customDomains)
        val removed = current.count { it in targets }
        current.removeAll(targets)
        customDomains = current
        return removed
    }

    /** 用给定文本整体替换自定义规则，返回实际写入条数。 */
    fun replaceDomains(text: String): Int {
        val parsed = HostsRules.parse(text)
        customDomains = parsed.domains.take(MAX_RULES).toSet()
        return minOf(parsed.domains.size, MAX_RULES)
    }

    fun clearDomains() {
        prefs.edit().remove(KEY_HOSTS).apply()
    }
}
