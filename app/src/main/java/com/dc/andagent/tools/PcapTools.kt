package com.dc.andagent.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * R144：抓包文件分析三工具（只读，destructive=false）。
 *
 * 典型来源：PCAPdroid 无 root 抓包导出到 /sdcard/Download。走 java.io.File 直读
 * （同 adb_pull 的读取授权面），路径校验复用 [AdbToolRules.validateRemotePath] 并叠加
 * /sdcard 白名单。解析核心在 [PcapParser]（纯 JVM 流式），本层只负责参数、JSON 组装与错误文案。
 */
val PCAP_TOOLS = listOf("pcap_summary", "pcap_dns", "pcap_http")

/** 与 adb_pull 读取授权面同源：仅允许 /sdcard（含 Download）及其等价存储根下的绝对路径。 */
private val PCAP_ALLOWED_ROOTS = listOf("/sdcard/", "/storage/emulated/0/", "/mnt/sdcard/")

object PcapPathRules {
    fun validate(path: String): String? {
        AdbToolRules.validateRemotePath(path)?.let { return it }
        if (!PCAP_ALLOWED_ROOTS.any { path.startsWith(it) }) {
            return "pcap 路径必须在 /sdcard（含 Download）白名单内，当前：$path"
        }
        return null
    }
}

class PcapTool(override val name: String) : Tool {

    override val description: String = when (name) {
        "pcap_summary" ->
            "解析 /sdcard（含 Download）下的 pcap/pcapng 抓包文件，返回文件级总览：包数/字节数/抓包时长/首末时间戳、协议分布、按字节数降序的 Top 会话（双向 srcIp:port↔dstIp:port+协议，含 SYN/FIN/RST 计数）、去重域名列表、分片/畸形包/TLS-partial/QUIC 计数。支持 pcap（大小端/纳秒变体）与 pcapng（SHB/IDB/EPB/SPB + 未知块跳过）；链路层支持 " + PcapParser.SUPPORTED_LINKTYPES_TEXT + "，其它返回明确报错。流式读取，默认上限 50 万包 / 500MB，超限标注 truncated。"
        "pcap_dns" ->
            "解析抓包文件里的 DNS 查询/响应记录（UDP/TCP 53，TCP 自动跳 2 字节长度前缀），支持 DNS 名称压缩指针（0xC0）解码；返回每条记录的时间戳、域名、类型(A/AAAA/CNAME 等)、结果 IP、rcode 与是否响应。limit 默认 200。"
        else ->
            "解析抓包文件里的 HTTP 请求/响应（请求行 method/path/version、Host 头、状态行 code，仅头部区）、TLS ClientHello 的 SNI（extension 0x0000，跨 TCP 段时标注 partial、不做流重组）与 QUIC v1 初包 SNI（简化 best-effort）；返回每条记录的时间戳、类型、method、host、path、status、sni。limit 默认 100。"
    }

    override val parameters = when (name) {
        "pcap_summary" -> schemaOf(
            "path" to strParam("抓包文件绝对路径（/sdcard/...，典型 /sdcard/Download/xxx.pcap）"),
            "top_n" to intParam("Top 会话条数，默认 20，范围 1..100"),
            required = listOf("path"),
        )
        "pcap_dns" -> schemaOf(
            "path" to strParam("抓包文件绝对路径（/sdcard/...）"),
            "limit" to intParam("最多返回条数，默认 200，范围 1..5000"),
            required = listOf("path"),
        )
        else -> schemaOf(
            "path" to strParam("抓包文件绝对路径（/sdcard/...）"),
            "limit" to intParam("最多返回条数，默认 100，范围 1..5000"),
            required = listOf("path"),
        )
    }

    override val destructive: Boolean = false

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        runCatching { executeInner(args) }.getOrElse { "ERROR: ${it.message ?: it.javaClass.simpleName}" }
    }

    private fun executeInner(args: JsonObject): String {
        val path = args.strArg("path") ?: return "ERROR: 缺少 path 参数"
        PcapPathRules.validate(path)?.let { return "ERROR: $it" }
        val file = File(path)
        if (!file.isFile) return "ERROR: 文件不存在或非普通文件：$path"
        return FileInputStream(file).use { input ->
            when (name) {
                "pcap_summary" -> summaryJson(input, args)
                "pcap_dns" -> dnsJson(input, args)
                else -> httpJson(input, args)
            }
        }
    }

    private fun clampLimit(args: JsonObject, key: String, def: Int, max: Int): Int =
        (args.intArg(key) ?: def).coerceIn(1, max)

    private fun summaryJson(input: InputStream, args: JsonObject): String {
        val cfg = PcapParseConfig(topN = clampLimit(args, "top_n", 20, 100))
        val r = PcapParser.parse(input, cfg)
        val s = r.summary
        return buildJsonObject {
            put("ok", true)
            put("truncated", r.truncated)
            r.truncatedReason?.let { put("truncatedReason", it) }
            put("packets", s.packets)
            put("bytes", s.bytes)
            put("durationUs", s.durationUs)
            put("durationMs", s.durationUs / 1000.0)
            s.firstTimestampUs?.let { put("firstTimestampUs", it) }
            s.lastTimestampUs?.let { put("lastTimestampUs", it) }
            put("fragmentCount", s.fragmentCount)
            put("malformedPackets", s.malformedPackets)
            put("tlsPartialCount", s.tlsPartialCount)
            put("quicCount", s.quicCount)
            putJsonObject("protocolDistribution") {
                s.protocolDistribution.forEach { (k, v) -> put(k, v) }
            }
            putJsonArray("topSessions") {
                s.topSessions.forEach { ses ->
                    add(buildJsonObject {
                        put("a", ses.a)
                        put("b", ses.b)
                        put("protocol", ses.protocol)
                        put("bytes", ses.bytes)
                        put("packets", ses.packets)
                        put("syn", ses.syn)
                        put("fin", ses.fin)
                        put("rst", ses.rst)
                    })
                }
            }
            putJsonArray("domains") { s.domains.forEach { add(it) } }
        }.toString()
    }

    private fun dnsJson(input: InputStream, args: JsonObject): String {
        val cfg = PcapParseConfig(dnsLimit = clampLimit(args, "limit", 200, 5000))
        val r = PcapParser.parse(input, cfg)
        return buildJsonObject {
            put("ok", true)
            put("truncated", r.truncated)
            r.truncatedReason?.let { put("truncatedReason", it) }
            put("count", r.dns.size)
            putJsonArray("records") {
                r.dns.forEach { d ->
                    add(buildJsonObject {
                        put("timestampUs", d.timestampUs)
                        put("domain", d.domain)
                        put("type", d.type)
                        d.resultIp?.let { put("resultIp", it) }
                        put("rcode", d.rcode)
                        put("response", d.response)
                    })
                }
            }
        }.toString()
    }

    private fun httpJson(input: InputStream, args: JsonObject): String {
        val cfg = PcapParseConfig(httpLimit = clampLimit(args, "limit", 100, 5000))
        val r = PcapParser.parse(input, cfg)
        return buildJsonObject {
            put("ok", true)
            put("truncated", r.truncated)
            r.truncatedReason?.let { put("truncatedReason", it) }
            put("count", r.http.size)
            putJsonArray("records") {
                r.http.forEach { h ->
                    add(buildJsonObject {
                        put("timestampUs", h.timestampUs)
                        put("kind", h.kind)
                        h.method?.let { put("method", it) }
                        h.host?.let { put("host", it) }
                        h.path?.let { put("path", it) }
                        h.status?.let { put("status", it) }
                        h.sni?.let { put("sni", it) }
                        put("partial", h.partial)
                    })
                }
            }
        }.toString()
    }
}
