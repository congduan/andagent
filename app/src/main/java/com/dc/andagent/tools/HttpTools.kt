package com.dc.andagent.tools

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

val HTTP_TOOLS = listOf("http_request", "lan_scan")

private const val HTTP_UA = "AndAgent/1.0 (Android)"
private const val MAX_BODY_BYTES = 5_000_000L
private const val SCAN_DEADLINE_MS = 30_000L
private const val MAX_SCAN_TIMEOUT_MS = 3_000

private val HTTP_METHODS = setOf("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD")

/** Headers worth surfacing to the model; everything else (cookies, fingerprints) is noise. */
private val KEY_HEADERS = setOf(
    "content-type", "content-length", "content-encoding", "content-language", "content-disposition",
    "server", "date", "location", "cache-control", "expires", "etag", "last-modified",
    "allow", "accept-ranges", "retry-after", "www-authenticate", "connection",
)

/**
 * Generic HTTP client plus LAN reachability probe.
 *
 * Unlike [WebSearchTool]'s web_read, http_request deliberately accepts plain HTTP:
 * Home Assistant / ESPHome on the local network speak http:// only.
 */
class HttpTool(
    override val name: String,
    private val context: Context? = null,
) : Tool {
    override val description = if (name == "http_request")
        "通用 HTTP 客户端：支持 GET/POST/PUT/DELETE/PATCH/HEAD，可自定义请求头与请求体，返回状态码、响应头、正文与耗时。" +
            "允许 http://（局域网设备如 Home Assistant/ESPHome）与 https://；非 2xx 也返回结构化结果。"
    else "局域网探测：mode=ping 扫描网段内存活主机（ICMP + 80/443 补充），mode=port 扫描指定端口开放情况。" +
        "默认使用当前 Wi-Fi 的 /24 网段，也可显式传 cidr（仅 /24、/28、/32）。"

    override val parameters = if (name == "http_request") schemaOf(
        "url" to strParam("完整地址，支持 http:// 与 https://，如 http://192.168.1.10:8123/api/"),
        "method" to strParam("GET/POST/PUT/DELETE/PATCH/HEAD，默认 GET"),
        "headers_json" to strParam("请求头 JSON 对象字符串，如 {\"Authorization\":\"Bearer xxx\"}"),
        "body" to strParam("请求体字符串（GET/HEAD 不接受）"),
        "timeout_ms" to intParam("超时毫秒，默认 15000，范围 1000~60000"),
        "max_chars" to intParam("响应正文最大字符数，默认 8000，最多 50000"),
        required = listOf("url"),
    ) else schemaOf(
        "mode" to strParam("ping：找存活主机；port：找开放端口的主机"),
        "cidr" to strParam("网段，默认当前 Wi-Fi 网段的 /24；仅支持 /24、/28 或 /32 单主机"),
        "port" to intParam("mode=port 时必填，1~65535"),
        "timeout_ms" to intParam("单主机超时毫秒，默认 800，范围 100~3000"),
        "limit" to intParam("存活/开放主机的上报上限，默认 64"),
        required = listOf("mode"),
    )

    private val baseHttp = OkHttpClient.Builder().build()

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        runCatching { if (name == "http_request") request(args) else scan(args) }
            .getOrElse { "ERROR: ${it.message ?: it.javaClass.simpleName}" }
    }

    /* ------------------------------ http_request ------------------------------ */

    private fun request(args: JsonObject): String {
        val rawUrl = args.strArg("url") ?: throw IllegalArgumentException("缺少 url")
        requireHttpUrl(rawUrl)
        val method = httpMethod(args.strArg("method"))
        val headers = parseHeadersJson(args.strArg("headers_json"))
        val bodyText = (args["body"] as? JsonPrimitive)?.contentOrNull
        val timeout = (args.intArg("timeout_ms") ?: 15_000).coerceIn(1_000, 60_000)
        val maxChars = (args.intArg("max_chars") ?: 8_000).coerceIn(1, 50_000)

        require(bodyText == null || method !in setOf("GET", "HEAD")) { "$method 请求不能带 body" }

        val builder = Request.Builder().url(rawUrl).header("User-Agent", HTTP_UA)
        headers.forEach { (key, value) -> builder.header(key, value) }
        val defaultType = headers.entries.firstOrNull { it.key.equals("content-type", true) }?.value
            ?.toMediaTypeOrNull()
        val mediaType = defaultType ?: (if (bodyText?.trimStart()?.startsWith("{") == true ||
            bodyText?.trimStart()?.startsWith("[") == true
        ) "application/json; charset=utf-8" else "text/plain; charset=utf-8").toMediaTypeOrNull()
        if (bodyText != null) {
            builder.method(method, bodyText.toRequestBody(mediaType))
        } else if (method in setOf("POST", "PUT", "PATCH")) {
            // OkHttp rejects body-less POST/PUT/PATCH; send an explicit empty body.
            builder.method(method, ByteArray(0).toRequestBody(mediaType))
        } else {
            builder.method(method, null)
        }

        val http = baseHttp.newBuilder()
            .connectTimeout(timeout.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(timeout.toLong(), TimeUnit.MILLISECONDS)
            .writeTimeout(timeout.toLong(), TimeUnit.MILLISECONDS)
            .callTimeout(timeout.toLong(), TimeUnit.MILLISECONDS)
            .build()

        val started = System.nanoTime()
        http.newCall(builder.build()).execute().use { response ->
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            val cap = if (response.isSuccessful) maxChars else min(maxChars, 2_000)
            val read = readBody(response, MAX_BODY_BYTES)
            val (text, cutByChars) = safeTake(read.text, cap)
            val declared = response.body?.contentLength() ?: -1L
            return buildJsonObject {
                put("status", response.code)
                put("ok", response.isSuccessful)
                put("method", method)
                put("url", response.request.url.toString())
                if (response.request.url.toString() != rawUrl) put("requestUrl", rawUrl)
                put("contentType", response.header("Content-Type") ?: "")
                put("headers", buildJsonObject { keyHeaders(response.headers.toMultimap()).forEach { (k, v) -> put(k, v) } })
                put("body", text)
                put("truncated", read.truncated || cutByChars)
                put("bodyChars", read.text.length)
                put("bodyBytes", max(read.bytes, declared))
                put("elapsedMs", elapsedMs)
            }.toString()
        }
    }

    /* -------------------------------- lan_scan -------------------------------- */

    private fun scan(args: JsonObject): String {
        val mode = (args.strArg("mode") ?: throw IllegalArgumentException("缺少 mode")).lowercase()
        require(mode == "ping" || mode == "port") { "mode 只能是 ping 或 port" }
        val timeout = (args.intArg("timeout_ms") ?: 800).coerceIn(100, MAX_SCAN_TIMEOUT_MS)
        val limit = (args.intArg("limit") ?: 64).coerceIn(1, 256)
        val explicit = args.strArg("cidr")
        val cidr = if (explicit != null) explicit else
            localWifiCidr() ?: throw IllegalStateException("未连接 Wi-Fi，无法确定局域网网段；请显式传 cidr，如 192.168.1.0/24")
        val range = parseCidr(cidr)

        return if (mode == "ping") pingScan(range, timeout, limit) else {
            val port = args.intArg("port") ?: throw IllegalArgumentException("mode=port 时必须提供 port")
            require(port in 1..65535) { "port 范围 1~65535" }
            portScan(range, port, timeout, limit)
        }
    }

    private fun pingScan(range: CidrRange, timeout: Int, limit: Int): String {
        val started = System.currentTimeMillis()
        val deadline = started + SCAN_DEADLINE_MS
        val alive = ConcurrentLinkedQueue<String>()
        val workers = min(64, range.hosts.size.coerceAtLeast(1))
        val pool = Executors.newFixedThreadPool(workers) { r -> Thread(r, "lan-ping").apply { isDaemon = true } }
        val finished = try {
            range.hosts.forEach { ip ->
                pool.execute {
                    if (System.currentTimeMillis() > deadline) return@execute
                    if (probeAlive(ip, timeout)) alive += ip
                }
            }
            pool.shutdown()
            pool.awaitTermination(SCAN_DEADLINE_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } finally {
            pool.shutdownNow()
        }

        val sorted = alive.distinct().sortedWith(ipComparator)
        val shown = sorted.take(limit)
        val names = reverseLookup(shown)
        return buildJsonObject {
            put("mode", "ping")
            put("cidr", range.label)
            put("scanned", range.hosts.size)
            put("aliveCount", sorted.size)
            put("alive", buildJsonArray {
                shown.forEach { ip ->
                    add(buildJsonObject {
                        put("ip", ip)
                        names[ip]?.let { put("hostname", it) }
                    })
                }
            })
            put("omitted", sorted.size - shown.size)
            put("timedOut", !finished)
            put("elapsedMs", System.currentTimeMillis() - started)
        }.toString()
    }

    private fun portScan(range: CidrRange, port: Int, timeout: Int, limit: Int): String {
        val started = System.currentTimeMillis()
        val deadline = started + SCAN_DEADLINE_MS
        val open = ConcurrentLinkedQueue<String>()
        val workers = min(64, range.hosts.size.coerceAtLeast(1))
        val pool = Executors.newFixedThreadPool(workers) { r -> Thread(r, "lan-port").apply { isDaemon = true } }
        val finished = try {
            range.hosts.forEach { ip ->
                pool.execute {
                    if (System.currentTimeMillis() > deadline) return@execute
                    val address = runCatching { InetAddress.getByName(ip) }.getOrNull() ?: return@execute
                    if (tcpOpen(address, port, timeout)) open += ip
                }
            }
            pool.shutdown()
            pool.awaitTermination(SCAN_DEADLINE_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } finally {
            pool.shutdownNow()
        }

        val sorted = open.distinct().sortedWith(ipComparator)
        val shown = sorted.take(limit)
        val names = reverseLookup(shown)
        return buildJsonObject {
            put("mode", "port")
            put("cidr", range.label)
            put("port", port)
            put("scanned", range.hosts.size)
            put("openCount", sorted.size)
            put("open", buildJsonArray {
                shown.forEach { ip ->
                    add(buildJsonObject {
                        put("ip", ip)
                        put("port", port)
                        names[ip]?.let { put("hostname", it) }
                    })
                }
            })
            put("omitted", sorted.size - shown.size)
            put("timedOut", !finished)
            put("elapsedMs", System.currentTimeMillis() - started)
        }.toString()
    }

    private fun probeAlive(ip: String, timeout: Int): Boolean {
        val address = runCatching { InetAddress.getByName(ip) }.getOrNull() ?: return false
        // isReachable falls back to a TCP echo probe; either way it is a cheap first pass.
        if (runCatching { address.isReachable(timeout) }.getOrDefault(false)) return true
        val tcpTimeout = min(timeout, 400)
        return tcpOpen(address, 80, tcpTimeout) || tcpOpen(address, 443, tcpTimeout)
    }

    private fun tcpOpen(address: InetAddress, port: Int, timeout: Int): Boolean =
        runCatching { Socket().use { it.connect(InetSocketAddress(address, port), timeout); true } }.getOrDefault(false)

    /** Reverse DNS has no timeout in Java, so bound each lookup with a future. */
    private fun reverseLookup(hosts: List<String>): Map<String, String> {
        if (hosts.isEmpty()) return emptyMap()
        val pool = Executors.newFixedThreadPool(min(16, hosts.size)) { r ->
            Thread(r, "lan-dns").apply { isDaemon = true }
        }
        return try {
            val futures = hosts.associateWith { ip ->
                pool.submit(Callable {
                    val address = InetAddress.getByName(ip)
                    address.canonicalHostName.takeIf { it != address.hostAddress }
                })
            }
            futures.mapNotNull { (ip, future) ->
                runCatching { future.get(500, TimeUnit.MILLISECONDS) }.getOrNull()?.let { ip to it }
            }.toMap()
        } finally {
            pool.shutdownNow()
        }
    }

    private fun localWifiCidr(): String? {
        val ctx = context ?: return null
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(network) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
        val ip = cm.getLinkProperties(network)?.linkAddresses
            ?.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress }
            ?.address?.hostAddress ?: return null
        val octets = ip.split('.')
        if (octets.size != 4) return null
        return "${octets[0]}.${octets[1]}.${octets[2]}.0/24"
    }
}

/* ------------------------------- pure helpers ------------------------------- */

internal data class CidrRange(val label: String, val prefix: Int, val hosts: List<String>)

/**
 * Only /24, /28 and single hosts (/32 or a bare address) are accepted — this tool
 * must never turn into a wide-range scanner by accident.
 */
internal fun parseCidr(raw: String): CidrRange {
    val text = raw.trim()
    val slash = text.indexOf('/')
    val ipPart = if (slash >= 0) text.substring(0, slash) else text
    val prefix = if (slash >= 0) text.substring(slash + 1).toIntOrNull() else 32
    require(prefix != null && prefix in setOf(24, 28, 32)) { "cidr 仅支持 /24、/28 或 /32 单主机，拒绝大范围扫描" }

    val octets = ipPart.split('.').map { it.trim().toIntOrNull() }
    require(octets.size == 4 && octets.all { it != null && it in 0..255 }) {
        "cidr 必须是 IPv4 地址，如 192.168.1.0/24"
    }
    val value = (octets[0]!!.toLong() shl 24) or (octets[1]!!.toLong() shl 16) or
        (octets[2]!!.toLong() shl 8) or octets[3]!!.toLong()
    val hostBits = 32 - prefix
    val networkValue = if (hostBits == 0) value else value and (((1L shl prefix) - 1) shl hostBits)

    val count = 1 shl hostBits
    val hosts = if (prefix == 32) listOf(formatIp(networkValue)) else
        (1 until count - 1).map { formatIp(networkValue + it) }
    return CidrRange("${formatIp(networkValue)}/$prefix", prefix, hosts)
}

private fun formatIp(value: Long): String =
    "${(value shr 24) and 0xFF}.${(value shr 16) and 0xFF}.${(value shr 8) and 0xFF}.${value and 0xFF}"

private val ipComparator = Comparator<String> { a, b ->
    fun key(ip: String) = ip.split('.').fold(0L) { acc, part -> (acc shl 8) or (part.toLongOrNull() ?: 0L) }
    key(a).compareTo(key(b))
}

internal fun httpMethod(raw: String?): String {
    val method = (raw ?: "GET").trim().uppercase()
    require(method in HTTP_METHODS) { "不支持的 method: $method（仅 GET/POST/PUT/DELETE/PATCH/HEAD）" }
    return method
}

/** Accepts http:// on purpose: LAN appliances usually have no TLS. */
internal fun requireHttpUrl(raw: String) {
    val uri = runCatching { URI(raw.trim()) }.getOrElse { throw IllegalArgumentException("无效 URL: $raw") }
    val scheme = uri.scheme?.lowercase()
    require(scheme == "http" || scheme == "https") { "仅支持 http:// 或 https:// 地址" }
    require(!uri.host.isNullOrBlank()) { "URL 缺少主机名" }
}

internal fun parseHeadersJson(raw: String?): Map<String, String> {
    if (raw.isNullOrBlank()) return emptyMap()
    val obj = runCatching { Json.parseToJsonElement(raw) }.getOrNull() as? JsonObject
        ?: throw IllegalArgumentException("headers_json 必须是 JSON 对象，如 {\"Authorization\":\"Bearer xxx\"}")
    return obj.entries.mapNotNull { (key, value) ->
        val text = (value as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        key.trim().takeIf { it.isNotEmpty() }?.let { it to text }
    }.toMap()
}

internal fun keyHeaders(all: Map<String, List<String>>): List<Pair<String, String>> =
    all.entries
        .filter { (name, _) -> name.lowercase() in KEY_HEADERS || name.startsWith("x-", true) }
        .sortedBy { it.key.lowercase() }
        .take(24)
        .map { (name, values) -> name to values.joinToString(", ").take(512) }

internal fun safeTake(text: String, maxChars: Int): Pair<String, Boolean> {
    if (text.length <= maxChars) return text to false
    var end = maxChars
    if (end > 0 && Character.isHighSurrogate(text[end - 1])) end--
    return text.substring(0, end) to true
}

internal data class BodyRead(val text: String, val bytes: Long, val truncated: Boolean)

/** Bounded read: never buffers more than [limitBytes] of a hostile response. */
internal fun readBody(response: Response, limitBytes: Long): BodyRead {
    val body = response.body ?: return BodyRead("", 0, false)
    val out = ByteArrayOutputStream()
    var truncated = false
    body.byteStream().use { stream ->
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            val remaining = limitBytes - out.size()
            if (read > remaining) {
                if (remaining > 0) out.write(buffer, 0, remaining.toInt())
                truncated = true
                break
            }
            out.write(buffer, 0, read)
        }
    }
    val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
    return BodyRead(String(out.toByteArray(), charset), out.size().toLong(), truncated)
}
