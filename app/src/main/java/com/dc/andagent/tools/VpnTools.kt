package com.dc.andagent.tools

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.TrafficStats
import android.net.VpnService
import android.os.Environment
import android.os.Process
import com.dc.andagent.bridge.PermissionBroker
import com.dc.andagent.i18n.tr
import com.dc.andagent.vpn.AndagentVpnService
import com.dc.andagent.vpn.DnsEvent
import com.dc.andagent.vpn.HostsRules
import com.dc.andagent.vpn.VpnConsentBridge
import com.dc.andagent.vpn.VpnConsentActivity
import com.dc.andagent.vpn.VpnStore
import com.dc.andagent.vpn.VpnTelemetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** R120: 本地 VPN 工具族（VpnService，纯应用权限）。 */
val VPN_TOOLS = listOf(
    "vpn_start",
    "vpn_stop",
    "vpn_status",
    "vpn_traffic",
    "vpn_split",
    "vpn_hosts",
    "vpn_diag",
)

/**
 * 本地 VPN：全机流量统计、按应用分流、hosts 广告拦截、DNS 抓包诊断。
 *
 * 只用 VpnService（纯应用权限，无需 root）：tun 仅接管 DNS 网段，
 * 因此开启后不会改变正常上网路径，也不会出现"VPN 一开就断网"。
 */
class VpnTool(
    private val context: Context,
    private val permissions: PermissionBroker,
    override val name: String,
) : Tool {

    override val description: String = when (name) {
        "vpn_start" ->
            "开启本地 VPN（无需 root）：建立 tun 并把系统 DNS 指向虚拟 DNS 服务器，做 hosts 级广告拦截与 DNS 抓包。" +
                "只接管 DNS，不影响正常上网。可顺带设置按应用分流（split_mode + packages）。" +
                "首次会弹出一次系统 VPN 授权，之后全自动。"
        "vpn_stop" -> "关闭本地 VPN，恢复系统 DNS。"
        "vpn_status" ->
            "查看本地 VPN 状态：是否运行、运行时长、分流模式与名单、规则数量、拦截/转发计数、" +
                "上游 DNS、私人 DNS 是否开启。未运行时也会返回当前配置。"
        "vpn_traffic" ->
            "全机流量统计（NetworkStatsManager，需「使用情况访问」权限）：按时间窗返回各应用的" +
                "Wi-Fi/移动网络收发字节，排序取前 N 个，同时给出设备总量与本应用实时值。"
        "vpn_split" ->
            "按应用分流配置：mode=include（只有名单内应用走 VPN）/ exclude（名单内应用不走 VPN）/ off；" +
                "action=status 查看，set 设置，clear 清空。纯应用权限，无需 root。"
        "vpn_hosts" ->
            "hosts 级广告拦截规则管理：内置常见广告/追踪域名，可增删自定义域名、导入远程 hosts 列表、" +
                "开关拦截、切换拦截应答（0.0.0.0 或 NXDOMAIN）。规则改动立即生效。"
        "vpn_diag" ->
            "网络诊断：DNS 查询日志（域名/类型/耗时/结果）、被拦截域名排行、导出标准 pcap（可拖进 Wireshark）、" +
                "清空日志。用于定位「哪个 App 在偷偷连广告」或「DNS 为什么慢」。"
        else -> "本地 VPN 工具"
    }

    override val parameters: JsonObject = when (name) {
        "vpn_start" -> schemaOf(
            "split_mode" to strParam("可选：off（默认，全部应用）/ include（仅名单）/ exclude（排除名单）"),
            "packages" to strParam("可选：逗号分隔的包名，配合 split_mode"),
            "block_ads" to boolParam("可选：是否开启 hosts 广告拦截，默认保持当前设置"),
            "upstream" to strParam("可选：上游 DNS，如 1.1.1.1 或 1.1.1.1,223.5.5.5；传 system 表示跟随物理网络"),
        )
        "vpn_stop" -> schemaOf()
        "vpn_status" -> schemaOf()
        "vpn_traffic" -> schemaOf(
            "hours" to intParam("统计时间窗小时数，默认 24，范围 1..720"),
            "type" to strParam("all（默认）/ wifi / mobile"),
            "top" to intParam("按流量排序返回的应用个数，默认 12，范围 1..80"),
        )
        "vpn_split" -> schemaOf(
            "action" to strParam("status（默认）/ set / clear"),
            "mode" to strParam("include / exclude / off"),
            "packages" to strParam("逗号或空格分隔的包名列表"),
        )
        "vpn_hosts" -> schemaOf(
            "action" to strParam("status（默认）/ add / remove / set / clear / enable / disable / import_url"),
            "domains" to strParam("域名或 hosts 文本；add/remove/set 必填"),
            "url" to strParam("action=import_url 时的 hosts 文件地址"),
            "replace" to boolParam("import_url 时是否替换现有自定义规则，默认 false（合并）"),
            "style" to strParam("拦截应答风格：zero（默认，返回 0.0.0.0）/ nxdomain"),
        )
        "vpn_diag" -> schemaOf(
            "action" to strParam("summary（默认）/ dns / blocked / pcap / clear"),
            "limit" to intParam("返回条数，默认 40，范围 1..500"),
        )
        else -> schemaOf()
    }

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        runCatching {
            when (name) {
                "vpn_start" -> start(args)
                "vpn_stop" -> stop()
                "vpn_status" -> statusJson()
                "vpn_traffic" -> traffic(args)
                "vpn_split" -> split(args)
                "vpn_hosts" -> hosts(args)
                "vpn_diag" -> diag(args)
                else -> "ERROR: 未知 VPN 工具 $name"
            }
        }.getOrElse { "ERROR: $name 执行失败：${it.message ?: it.javaClass.simpleName}" }
    }

    /* ------------------------------- start ------------------------------ */

    private suspend fun start(args: JsonObject): String {
        val store = VpnStore.from(context)
        var changed = false

        args.strArg("split_mode")?.let {
            store.splitMode = it
            changed = true
        }
        args.strArg("packages")?.let {
            store.packages = splitPackages(it)
            changed = true
        }
        args.boolArg("block_ads")?.let {
            store.hostsEnabled = it
            changed = true
        }
        args.strArg("upstream")?.let {
            store.upstream = if (it.equals("system", true) || it.equals("auto", true)) "" else it
            changed = true
        }

        if (AndagentVpnService.running) {
            if (changed) {
                // tun 的地址/路由/分流在 establish() 时固化，改配置必须重建
                AndagentVpnService.stop(context)
                waitUntilStopped()
                AndagentVpnService.start(context)
                waitUntilRunning()
                return buildJsonObject {
                    put("ok", true)
                    put("restarted", true)
                    put("message", "配置已更新并重启 VPN")
                    put("status", Json.parseToJsonElement(statusJson()))
                }.toString()
            }
            return buildJsonObject {
                put("ok", true)
                put("alreadyRunning", true)
                put("status", Json.parseToJsonElement(statusJson()))
            }.toString()
        }

        if (!ensureConsent()) {
            return "ERROR: " + tr(
                "VPN 授权被拒绝或超时。请在系统弹窗中点「确定」，并确认没有别的 VPN 正在运行。",
                "VPN authorization was denied or timed out. Accept the system dialog and make sure no other VPN is active.",
            )
        }

        AndagentVpnService.start(context)
        val ok = waitUntilRunning()
        if (!ok) {
            val error = AndagentVpnService.instance?.error()
            return "ERROR: " + (
                error ?: tr("VPN 未能启动，请检查是否有其它 VPN 正在运行", "VPN did not start; check whether another VPN is active")
                )
        }
        return buildJsonObject {
            put("ok", true)
            put("message", "本地 VPN 已启动（仅接管 DNS，不影响正常上网）")
            put("status", Json.parseToJsonElement(statusJson()))
        }.toString()
    }

    private fun stop(): String {
        val wasRunning = AndagentVpnService.running
        AndagentVpnService.stop(context)
        return buildJsonObject {
            put("ok", true)
            put("wasRunning", wasRunning)
            put("message", if (wasRunning) "本地 VPN 已关闭，系统 DNS 已恢复" else "本地 VPN 本来就没有运行")
            put("stats", statsJson())
        }.toString()
    }

    private suspend fun ensureConsent(): Boolean {
        if (VpnService.prepare(context) == null) return true
        val id = VpnConsentBridge.newRequest()
        context.startActivity(VpnConsentActivity.intent(context, id))
        return VpnConsentBridge.await(id, 90_000)
    }

    private suspend fun waitUntilRunning(timeoutMs: Long = 6_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (AndagentVpnService.running) return true
            delay(120)
        }
        return AndagentVpnService.running
    }

    private suspend fun waitUntilStopped(timeoutMs: Long = 3_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!AndagentVpnService.running) return
            delay(100)
        }
    }

    /* ------------------------------ status ------------------------------ */

    private fun statusJson(): String {
        val store = VpnStore.from(context)
        val service = AndagentVpnService.instance
        val running = service?.runningFlag() == true
        return buildJsonObject {
            put("running", running)
            if (running) {
                put("sessionSeconds", service?.sessionSeconds() ?: 0)
            }
            putJsonObject("tun") {
                put("address", "${AndagentVpnService.TUN_ADDRESS}/${AndagentVpnService.TUN_PREFIX}")
                put("dns", AndagentVpnService.DNS_ADDRESS)
                put("route", "${AndagentVpnService.DNS_ADDRESS}/${AndagentVpnService.DNS_PREFIX}")
                put("scope", "dns-only")
            }
            putJsonObject("split") {
                put("mode", store.splitMode)
                put("packages", JsonArray(store.packages.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            }
            putJsonObject("hosts") {
                put("enabled", store.hostsEnabled)
                put("builtInRules", HostsRules.builtIn().size)
                put("customRules", store.customDomains.size)
                put("activeRules", if (running) service?.activeRuleCount() ?: store.activeRules().size else store.activeRules().size)
                put("blockStyle", store.blockStyle)
            }
            putJsonArray("upstream") {
                val list = store.upstreamList()
                if (list.isEmpty()) {
                    add("system")
                } else {
                    list.forEach { add(it) }
                }
            }
            put("privateDns", service?.privateDnsActive() ?: false)
            put("stats", statsJson())
            service?.error()?.let { put("lastError", it) }
            val hints = ArrayList<String>()
            if (running && service?.privateDnsActive() == true) {
                hints.add("系统「私人 DNS」已开启，会绕过本 VPN 的 DNS 拦截；如需拦截请设为关闭")
            }
            if (running && store.splitMode == VpnStore.SPLIT_INCLUDE && store.packages.isEmpty()) {
                hints.add("分流模式为 include 但名单为空，当前等同于不接管任何应用的 DNS")
            }
            if (running && (VpnTelemetry.snapshot()["packetsIn"] ?: 0L) == 0L) {
                hints.add(
                    "运行中但一个 DNS 报文都没收到：确认系统 DNS 指向 ${AndagentVpnService.DNS_ADDRESS}、" +
                        "分流名单没有把发起查询的应用排除在外（packetsIn=0 时问题不在上游 DNS）",
                )
            }
            if (hints.isNotEmpty()) {
                putJsonArray("hints") { hints.forEach { add(it) } }
            }
        }.toString()
    }

    private fun statsJson(): JsonObject = buildJsonObject {
        val snapshot = VpnTelemetry.snapshot()
        put("packetsIn", snapshot["packetsIn"] ?: 0)
        put("bytesIn", snapshot["bytesIn"] ?: 0)
        put("dnsQueries", snapshot["dnsQueries"] ?: 0)
        put("dnsBlocked", snapshot["dnsBlocked"] ?: 0)
        put("dnsForwarded", snapshot["dnsForwarded"] ?: 0)
        put("dnsFailed", snapshot["dnsFailed"] ?: 0)
        put("capturedPackets", snapshot["capturedPackets"] ?: 0)
        put("blockRate", run {
            val total = (snapshot["dnsQueries"] ?: 0)
            if (total <= 0) "0%" else "${(snapshot["dnsBlocked"] ?: 0) * 100 / total}%"
        })
    }

    /* ------------------------------ traffic ----------------------------- */

    private fun traffic(args: JsonObject): String {
        val hours = (args.intArg("hours") ?: 24).coerceIn(1, 720)
        val type = (args.strArg("type") ?: "all").lowercase()
        val top = (args.intArg("top") ?: 12).coerceIn(1, 80)

        if (!hasUsageAccess()) {
            return "ERROR: " + tr(
                "缺少「使用情况访问」权限，无法读取全机分应用流量。请在系统设置 → 特殊应用权限 → 使用情况访问 中允许 AndAgent。",
                "Missing Usage Access permission, so per-app traffic is unavailable. Allow AndAgent under Settings → Special app access → Usage access.",
            )
        }

        val end = System.currentTimeMillis()
        val begin = end - hours * 3_600_000L
        val buckets = LinkedHashMap<Int, LongArray>()
        val warnings = ArrayList<String>()

        val types = when (type) {
            "wifi" -> listOf(ConnectivityManager.TYPE_WIFI)
            "mobile" -> listOf(ConnectivityManager.TYPE_MOBILE)
            else -> listOf(ConnectivityManager.TYPE_WIFI, ConnectivityManager.TYPE_MOBILE)
        }
        for (t in types) {
            runCatching { queryByUid(t, begin, end) }
                .onSuccess { map ->
                    for ((uid, values) in map) {
                        val acc = buckets.getOrPut(uid) { LongArray(2) }
                        acc[0] += values[0]
                        acc[1] += values[1]
                    }
                }
                .onFailure { warnings.add("${if (t == ConnectivityManager.TYPE_WIFI) "wifi" else "mobile"}: ${it.message ?: "查询失败"}") }
        }

        val index = uidIndex()
        val rows = buckets.entries
            .filter { it.value[0] + it.value[1] > 0 }
            .sortedByDescending { it.value[0] + it.value[1] }
            .take(top)

        var totalRx = 0L
        var totalTx = 0L
        for (values in buckets.values) {
            totalRx += values[0]
            totalTx += values[1]
        }

        return buildJsonObject {
            put("windowHours", hours)
            put("type", type)
            putJsonObject("total") {
                put("rxBytes", totalRx)
                put("txBytes", totalTx)
                put("rxMB", mb(totalRx))
                put("txMB", mb(totalTx))
            }
            putJsonObject("sinceBoot") {
                put("deviceRxBytes", TrafficStats.getTotalRxBytes())
                put("deviceTxBytes", TrafficStats.getTotalTxBytes())
                put("mobileRxBytes", TrafficStats.getMobileRxBytes())
                put("appRxBytes", TrafficStats.getUidRxBytes(Process.myUid()))
                put("appTxBytes", TrafficStats.getUidTxBytes(Process.myUid()))
                put("note", "TrafficStats 为开机以来累计值，-1 表示该设备不支持")
            }
            putJsonArray("apps") {
                for ((uid, values) in rows) {
                    val known = index[uid]
                    add(buildJsonObject {
                        put("uid", uid)
                        put("package", known?.first ?: "uid:$uid")
                        put("label", known?.second ?: "未知")
                        put("rxBytes", values[0])
                        put("txBytes", values[1])
                        put("totalMB", mb(values[0] + values[1]))
                    })
                }
            }
            if (warnings.isNotEmpty()) {
                putJsonArray("warnings") { warnings.forEach { add(it) } }
            }
            if (rows.isEmpty()) {
                put("hint", "该时间窗内没有可统计的流量；部分设备对 Wi-Fi 统计需要单独的「使用情况访问」授权")
            }
        }.toString()
    }

    private fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        return appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        ) == AppOpsManager.MODE_ALLOWED
    }

    private fun queryByUid(type: Int, begin: Long, end: Long): Map<Int, LongArray> {
        val manager = context.getSystemService(NetworkStatsManager::class.java) ?: return emptyMap()
        val result = HashMap<Int, LongArray>()
        val stats = manager.querySummary(type, null, begin, end)
        try {
            val bucket = android.app.usage.NetworkStats.Bucket()
            while (stats.hasNextBucket()) {
                if (!stats.getNextBucket(bucket)) break
                val acc = result.getOrPut(bucket.uid) { LongArray(2) }
                acc[0] += bucket.rxBytes
                acc[1] += bucket.txBytes
            }
        } finally {
            runCatching { stats.close() }
        }
        return result
    }

    private fun uidIndex(): Map<Int, Pair<String, String>> {
        val manager = context.packageManager
        val index = HashMap<Int, Pair<String, String>>()
        runCatching {
            for (info in manager.getInstalledPackages(0)) {
                val app = info.applicationInfo ?: continue
                if (index.containsKey(app.uid)) continue
                val label = runCatching { manager.getApplicationLabel(app).toString() }
                    .getOrDefault(info.packageName)
                index[app.uid] = info.packageName to label
            }
        }
        return index
    }

    private fun mb(bytes: Long): String = "%.2f".format(bytes / 1024.0 / 1024.0)

    /* ------------------------------- split ------------------------------ */

    private suspend fun split(args: JsonObject): String {
        val store = VpnStore.from(context)
        val action = (args.strArg("action") ?: "status").lowercase()

        when (action) {
            "set" -> {
                val mode = VpnStore.normalizeSplitMode(args.strArg("mode") ?: VpnStore.SPLIT_OFF)
                val raw = args.strArg("packages")
                val packages = if (raw == null) store.packages else splitPackages(raw)
                val (valid, invalid) = validatePackages(packages)
                store.splitMode = if (mode == VpnStore.SPLIT_OFF) VpnStore.SPLIT_OFF else mode
                store.packages = valid
                val restarted = restartIfRunning()
                return buildJsonObject {
                    put("ok", true)
                    put("mode", store.splitMode)
                    putJsonArray("packages") { valid.forEach { add(it) } }
                    if (invalid.isNotEmpty()) {
                        putJsonArray("unknownPackages") { invalid.forEach { add(it) } }
                    }
                    put("restarted", restarted)
                    put("note", if (restarted) "分流已生效（VPN 已重建）" else "配置已保存，下次开启 VPN 时生效")
                }.toString()
            }
            "clear" -> {
                store.splitMode = VpnStore.SPLIT_OFF
                store.packages = emptyList()
                val restarted = restartIfRunning()
                return buildJsonObject {
                    put("ok", true)
                    put("mode", VpnStore.SPLIT_OFF)
                    put("restarted", restarted)
                    put("note", "已关闭按应用分流，全部应用共用同一策略")
                }.toString()
            }
            else -> {
                val (valid, invalid) = validatePackages(store.packages)
                return buildJsonObject {
                    put("running", AndagentVpnService.running)
                    put("mode", store.splitMode)
                    putJsonArray("packages") { valid.forEach { add(it) } }
                    if (invalid.isNotEmpty()) {
                        putJsonArray("unknownPackages") { invalid.forEach { add(it) } }
                    }
                    put("explain", when (store.splitMode) {
                        VpnStore.SPLIT_INCLUDE -> "只有名单内应用的 DNS 走 VPN，其余应用直连"
                        VpnStore.SPLIT_EXCLUDE -> "名单内应用的 DNS 不走 VPN，其余应用走 VPN"
                        else -> "未分流，全部应用的 DNS 都走 VPN"
                    })
                    putJsonArray("popular") {
                        add("com.android.chrome")
                        add("com.tencent.mm")
                        add("com.taobao.taobao")
                        add("com.zhihu.android")
                    }
                }.toString()
            }
        }
    }

    private suspend fun restartIfRunning(): Boolean {
        if (!AndagentVpnService.running) return false
        AndagentVpnService.stop(context)
        waitUntilStopped()
        AndagentVpnService.start(context)
        return waitUntilRunning()
    }

    private fun splitPackages(raw: String): List<String> =
        raw.split(',', ';', ' ', '\n', '\t')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    private fun validatePackages(packages: List<String>): Pair<List<String>, List<String>> {
        val valid = ArrayList<String>()
        val invalid = ArrayList<String>()
        for (pkg in packages) {
            val exists = runCatching {
                context.packageManager.getPackageInfo(pkg, 0)
            }.isSuccess
            if (exists) valid.add(pkg) else invalid.add(pkg)
        }
        return valid to invalid
    }

    /* ------------------------------- hosts ------------------------------ */

    private suspend fun hosts(args: JsonObject): String {
        val store = VpnStore.from(context)
        val action = (args.strArg("action") ?: "status").lowercase()
        val domainsText = args.strArg("domains")

        when (action) {
            "add" -> {
                if (domainsText == null) return "ERROR: action=add 需要 domains 参数"
                val report = store.addDomains(domainsText)
                applyRules()
                return hostsResult(store, "已添加 ${report.domains.size} 条规则（无效行 ${report.invalid}）")
            }
            "remove" -> {
                if (domainsText == null) return "ERROR: action=remove 需要 domains 参数"
                val removed = store.removeDomains(domainsText)
                applyRules()
                return hostsResult(store, "已移除 $removed 条自定义规则")
            }
            "set" -> {
                if (domainsText == null) return "ERROR: action=set 需要 domains 参数"
                val count = store.replaceDomains(domainsText)
                applyRules()
                return hostsResult(store, "自定义规则已替换为 $count 条")
            }
            "clear" -> {
                store.clearDomains()
                applyRules()
                return hostsResult(store, "自定义规则已清空（内置规则仍生效）")
            }
            "enable" -> {
                store.hostsEnabled = true
                applyRules()
                return hostsResult(store, "广告拦截已开启")
            }
            "disable" -> {
                store.hostsEnabled = false
                applyRules()
                return hostsResult(store, "广告拦截已关闭（规则保留，可随时重新开启）")
            }
            "import_url" -> {
                val url = args.strArg("url") ?: return "ERROR: action=import_url 需要 url 参数"
                val replace = args.boolArg("replace") == true
                val text = fetchHosts(url)
                return if (replace) {
                    val count = store.replaceDomains(text)
                    applyRules()
                    hostsResult(store, "已用 $url 替换规则，共 $count 条")
                } else {
                    val report = store.addDomains(text)
                    applyRules()
                    hostsResult(store, "已从 $url 合并 ${report.domains.size} 条新规则")
                }
            }
            else -> {
                args.strArg("style")?.let { store.blockStyle = it }
                val custom = store.customDomains
                return buildJsonObject {
                    put("enabled", store.hostsEnabled)
                    put("builtInRules", HostsRules.builtIn().size)
                    put("customRules", custom.size)
                    put("activeRules", store.activeRules().size)
                    put("blockStyle", store.blockStyle)
                    putJsonArray("customSample") { custom.sorted().take(50).forEach { add(it) } }
                    putJsonArray("builtInSample") { HostsRules.builtIn().sorted().take(20).forEach { add(it) } }
                    put("hint", "单个 App 内置 DoH/DoT 或硬编码 DNS 时不会经过本 VPN；把系统「私人 DNS」设为关闭可让拦截更完整")
                }.toString()
            }
        }
    }

    private fun hostsResult(store: VpnStore, message: String): String = buildJsonObject {
        put("ok", true)
        put("message", message)
        put("enabled", store.hostsEnabled)
        put("builtInRules", HostsRules.builtIn().size)
        put("customRules", store.customDomains.size)
        put("activeRules", store.activeRules().size)
        put("appliedLive", AndagentVpnService.running)
    }.toString()

    private fun applyRules() {
        AndagentVpnService.instance?.reloadRules()
    }

    private fun fetchHosts(url: String): String {
        require(url.startsWith("http://") || url.startsWith("https://")) { "url 必须以 http:// 或 https:// 开头" }
        val client = OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val request = Request.Builder().url(url).header("User-Agent", "andagent-vpn/1.0").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            val body = response.body ?: throw IllegalStateException("响应为空")
            val declared = body.contentLength()
            if (declared > MAX_HOSTS_BYTES) throw IllegalStateException("hosts 文件过大（>2MB）")
            return readLimited(body.byteStream(), MAX_HOSTS_BYTES)
        }
    }

    private fun readLimited(stream: java.io.InputStream, max: Int): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (total < max) {
            val read = stream.read(buffer)
            if (read <= 0) break
            val take = minOf(read, max - total)
            out.write(buffer, 0, take)
            total += take
        }
        return out.toString("UTF-8")
    }

    /* -------------------------------- diag ------------------------------ */

    private suspend fun diag(args: JsonObject): String {
        val action = (args.strArg("action") ?: "summary").lowercase()
        val limit = (args.intArg("limit") ?: 40).coerceIn(1, 500)
        return when (action) {
            "dns" -> buildJsonObject {
                put("count", VpnTelemetry.recent(limit).size)
                putJsonArray("events") {
                    for (event in VpnTelemetry.recent(limit)) {
                        add(eventJson(event))
                    }
                }
            }.toString()
            "blocked" -> buildJsonObject {
                put("count", VpnTelemetry.topBlocked(limit).size)
                putJsonArray("domains") {
                    for ((domain, hits) in VpnTelemetry.topBlocked(limit)) {
                        add(buildJsonObject {
                            put("domain", domain)
                            put("hits", hits)
                        })
                    }
                }
            }.toString()
            "pcap" -> exportPcap()
            "clear" -> {
                VpnTelemetry.clearLogs()
                buildJsonObject {
                    put("ok", true)
                    put("message", "DNS 日志与抓包缓冲已清空（累计计数保留）")
                }.toString()
            }
            else -> summaryJson(limit)
        }
    }

    private fun eventJson(event: DnsEvent): JsonObject = buildJsonObject {
        put("time", SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(event.ts)))
        put("domain", event.name)
        put("type", event.qtype)
        put("client", event.clientIp)
        put("result", event.result)
        put("elapsedMs", event.elapsedMs)
        event.answer?.let { put("answer", it) }
        event.rule?.let { put("via", it) }
    }

    private fun summaryJson(limit: Int): String {
        val service = AndagentVpnService.instance
        val running = service?.runningFlag() == true
        val snapshot = VpnTelemetry.snapshot()
        val failures = VpnTelemetry.recent(limit * 2).filter { it.result == "failed" }.take(limit)
        return buildJsonObject {
            put("running", running)
            if (running) put("sessionSeconds", service?.sessionSeconds() ?: 0)
            put("privateDns", service?.privateDnsActive() ?: false)
            put("stats", statsJson())
            putJsonArray("topBlocked") {
                for ((domain, hits) in VpnTelemetry.topBlocked(limit)) {
                    add(buildJsonObject {
                        put("domain", domain)
                        put("hits", hits)
                    })
                }
            }
            putJsonArray("recentFailures") { failures.forEach { add(eventJson(it)) } }
            val slow = VpnTelemetry.recent(limit * 2).filter { it.result == "forwarded" }.sortedByDescending { it.elapsedMs }.take(5)
            putJsonArray("slowestQueries") { slow.forEach { add(eventJson(it)) } }
            putJsonArray("advice") {
                if (!running) add("VPN 未运行：先调用 vpn_start 才能抓到 DNS 流量")
                if ((snapshot["dnsFailed"] ?: 0L) > 0L) add("存在上游 DNS 失败，可在 vpn_start 里指定更稳定的 upstream（如 223.5.5.5）")
                if (service?.privateDnsActive() == true) add("系统「私人 DNS」已开启，会绕过本 VPN：请在网络设置里关闭")
                if ((snapshot["capturedPackets"] ?: 0L) > 0L) add("可用 vpn_diag(action=pcap) 导出 pcap，在 Wireshark 里查看")
                if (running && (snapshot["packetsIn"] ?: 0L) == 0L) add("运行中但 tun 里没有任何报文：系统 DNS 应指向 ${AndagentVpnService.DNS_ADDRESS}，并检查分流名单是否漏掉发起查询的应用")
            }
        }.toString()
    }

    private suspend fun exportPcap(): String {
        permissions.ensure(listOf(Manifest.permission.WRITE_EXTERNAL_STORAGE))
        val directory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "andagent/vpn",
        )
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("无法创建目录 ${directory.absolutePath}")
        }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(directory, "vpn-$stamp.pcap")
        val count = VpnTelemetry.exportPcap(file)
        return buildJsonObject {
            put("ok", true)
            put("path", file.absolutePath)
            put("packets", count)
            put("sizeBytes", file.length())
            put("format", "pcap / LINKTYPE_RAW（Wireshark、tcpdump 可直接打开）")
            put("note", if (count == 0) "缓冲为空：VPN 运行期间才会记录 DNS 报文" else "已导出 $count 个报文")
        }.toString()
    }

    private companion object {
        const val MAX_HOSTS_BYTES = 2 * 1024 * 1024
    }
}
