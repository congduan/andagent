package com.dc.andagent.tools

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URI
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** R121: 局域网语义化发现（SSDP/UPnP + mDNS/DNS-SD）。 */
val DISCOVERY_TOOLS = listOf("lan_discover", "upnp_describe")

private const val MDNS_MULTICAST_ADDRESS = "224.0.0.251"
private const val MDNS_MULTICAST_PORT = 5353
private const val MDNS_POLL_MS = 250
private const val MAX_MDNS_QUESTIONS = 48
private const val MAX_DESCRIPTION_BYTES = 262_144L

/**
 * 语义化局域网发现。
 *
 * [lan_scan] 只会告诉你「这个 IP 开着 80 端口」；本工具的答案是「这是客厅电视 /
 * 打印机 / Home Assistant」。SSDP 提供 UPnP 设备类型与描述文档（型号厂商、
 * serviceList，后续可 SOAP 控制），mDNS 提供 DNS-SD 服务与 TXT 属性
 * （HA 的 base_url、Google Cast 的 model、AirPlay 的 deviceid）。
 *
 * 这也是 R81 互传（`_andagent._tcp`）与 R78 智能家居（`_home-assistant._tcp`、
 * ESPHome、Matter）的前置发现能力。
 */
class DiscoveryTool(
    override val name: String,
    private val context: Context? = null,
) : Tool {

    override val description: String = when (name) {
        "lan_discover" ->
            "语义化局域网设备发现：同时发 SSDP/UPnP 多播搜索与 mDNS/DNS-SD 查询，按服务类型识别" +
                "电视(tv)/音箱(speaker)/打印机(printer)/Home Assistant/NAS/摄像头/路由器/音箱投屏(cast)/" +
                "电脑(computer)/物联网(iot)/AndAgent 对端(andagent)，返回设备名、分类、IP、端口、厂商型号、" +
                "服务类型与 TXT 属性（如 HA 的 base_url、Google Cast 的 model）。默认两路并行 3~5 秒。" +
                "比 lan_scan 端口扫描更准：lan_scan 只给存活 IP，本工具回答「这是什么设备」。"
        else ->
            "读取 UPnP 设备描述文档（SSDP 返回的 LOCATION）：返回友好名称、厂商、型号、序列号、UDN、" +
                "服务列表（serviceType + 绝对 controlURL/SCPDURL，可用于后续 SOAP 控制）与图标地址；" +
                "只给设备根地址时会自动尝试 description.xml / rootDesc.xml 等常见路径。"
    }

    override val parameters = if (name == "lan_discover") schemaOf(
        "protocol" to strParam("ssdp / mdns / both，默认 both"),
        "timeout_ms" to intParam("等待响应毫秒，默认 4000，范围 500~15000"),
        "limit" to intParam("设备上报上限，默认 32，最多 128"),
        "services" to strParam("可选：自定义 mDNS 服务类型（逗号分隔，如 _http._tcp,_ipp._tcp）；省略用内置常用集合"),
        "details" to boolParam("SSDP 是否抓取设备描述文档补全名称/型号，默认 true"),
        required = listOf(),
    ) else schemaOf(
        "url" to strParam("设备描述地址（SSDP 的 LOCATION，如 http://192.168.1.20:49152/description.xml）或设备根地址 http://192.168.1.20:49152/"),
        "timeout_ms" to intParam("请求超时毫秒，默认 4000"),
        required = listOf("url"),
    )

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        runCatching { if (name == "lan_discover") discover(args) else describe(args) }
            .getOrElse { "ERROR: ${it.message ?: it.javaClass.simpleName}" }
    }

    /* ------------------------------- lan_discover ------------------------------- */

    private fun discover(args: JsonObject): String {
        val protocol = (args.strArg("protocol") ?: "both").lowercase()
        require(protocol in setOf("ssdp", "mdns", "both")) { "protocol 只能是 ssdp / mdns / both" }
        val timeout = (args.intArg("timeout_ms") ?: 4_000).coerceIn(500, 15_000)
        val limit = (args.intArg("limit") ?: 32).coerceIn(1, 128)
        val details = args.boolArg("details") ?: true
        val services = (args.strArg("services")?.let { splitServiceTypes(it) } ?: MDNS_SERVICE_PRESETS)
            .distinct()
            .take(MAX_MDNS_QUESTIONS)
        require(services.isNotEmpty()) { "services 不能为空" }

        val started = System.currentTimeMillis()
        val errors = CopyOnWriteArrayList<String>()
        var ssdpDevices = emptyList<SsdpDevice>()
        var mdnsScan = MdnsScan(emptyList(), emptyList())

        if (protocol == "both") {
            val pool = Executors.newFixedThreadPool(2) { runnable ->
                Thread(runnable, "lan-discover").apply { isDaemon = true }
            }
            try {
                val ssdpJob = pool.submit(Callable { ssdpDiscover(timeout, limit, details, errors) })
                val mdnsJob = pool.submit(Callable { mdnsDiscover(services, timeout, errors) })
                ssdpDevices = runCatching { ssdpJob.get(timeout + 15_000L, TimeUnit.MILLISECONDS) }
                    .getOrElse {
                        errors += "ssdp: ${it.message ?: it.javaClass.simpleName}"
                        emptyList()
                    }
                mdnsScan = runCatching { mdnsJob.get(timeout + 15_000L, TimeUnit.MILLISECONDS) }
                    .getOrElse {
                        errors += "mdns: ${it.message ?: it.javaClass.simpleName}"
                        MdnsScan(emptyList(), emptyList())
                    }
            } finally {
                pool.shutdownNow()
            }
        } else if (protocol == "ssdp") {
            ssdpDevices = ssdpDiscover(timeout, limit, details, errors)
        } else {
            mdnsScan = mdnsDiscover(services, timeout, errors)
        }

        val merged = linkedMapOf<String, MergedDevice>()
        ssdpDevices.forEach { merge(merged, it.toMerged()) }
        mdnsScan.devices.forEach { merge(merged, it.toMerged()) }

        val devices = merged.values.sortedWith(
            compareBy({ categoryRank(it.category) }, { it.name?.lowercase() ?: "" }),
        )
        val shown = devices.take(limit)
        val categories = shown.groupingBy { it.category }.eachCount()
            .toList()
            .sortedByDescending { it.second }

        return buildJsonObject {
            put("protocol", protocol)
            put("elapsedMs", System.currentTimeMillis() - started)
            put("deviceCount", devices.size)
            putJsonObject("categories") { categories.forEach { (category, count) -> put(category, count) } }
            putJsonArray("devices") {
                shown.forEach { device ->
                    addJsonObject {
                        putText("name", device.name)
                        put("category", device.category)
                        putText("address", device.address)
                        device.port?.let { put("port", it) }
                        putText("host", device.host)
                        putJsonArray("protocols") { device.protocols.forEach { add(it) } }
                        putJsonArray("services") { device.services.forEach { add(it) } }
                        putText("manufacturer", device.manufacturer)
                        putText("model", device.model)
                        putText("server", device.server)
                        putText("location", device.location)
                        if (device.txt.isNotEmpty()) {
                            putJsonObject("txt") { device.txt.forEach { (key, value) -> put(key, value) } }
                        }
                    }
                }
            }
            put("omitted", devices.size - shown.size)
            if (mdnsScan.serviceTypes.isNotEmpty()) {
                putJsonArray("mdnsServiceTypes") { mdnsScan.serviceTypes.forEach { add(it) } }
            }
            put("detailsFetched", ssdpDevices.count { it.model != null || it.manufacturer != null })
            if (errors.isNotEmpty()) {
                putJsonArray("errors") { errors.take(6).forEach { add(it) } }
            }
        }.toString()
    }

    /* ---------------------------------- SSDP ---------------------------------- */

    private fun ssdpDiscover(
        timeoutMs: Int,
        limit: Int,
        details: Boolean,
        errors: MutableList<String>,
    ): List<SsdpDevice> {
        val ads = mutableListOf<SsdpAdvertisement>()
        val mx = ((timeoutMs + 999) / 1000).coerceIn(1, 5)
        try {
            DatagramSocket().use { socket ->
                socket.soTimeout = 300
                val target = InetSocketAddress(InetAddress.getByName(SSDP_MULTICAST_ADDRESS), SSDP_MULTICAST_PORT)
                listOf("ssdp:all", "urn:schemas-upnp-org:device:MediaRenderer:1").forEach { st ->
                    val payload = buildSsdpSearch(st, mx).toByteArray(Charsets.UTF_8)
                    socket.send(DatagramPacket(payload, payload.size, target))
                }
                val deadline = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < deadline && ads.size <= 400) {
                    val buffer = ByteArray(8 * 1024)
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    parseSsdpAdvertisement(text)?.let { ads += it }
                }
            }
        } catch (e: Exception) {
            errors += "ssdp: ${describeNetworkError(e)}"
        }
        if (ads.isEmpty()) return emptyList()

        // USN 的 uuid 前缀才是设备身份；同一设备会对每个 ST 各回一条。
        val groups = linkedMapOf<String, MutableList<SsdpAdvertisement>>()
        ads.forEach { ad ->
            val usn = ad.usn
            val key = when {
                usn != null && usn.contains("::") -> usn.substringBefore("::")
                usn != null -> usn
                ad.location != null -> ad.location
                else -> ad.type
            }
            groups.getOrPut(key) { mutableListOf() } += ad
        }

        val described = java.util.concurrent.ConcurrentHashMap<String, UpnpDevice>()
        if (details) {
            // 批量抓描述文档必须并行：串行 20 台设备会把总耗时拉到十几秒。
            val targets = groups.entries.take(24)
                .mapNotNull { (key, list) -> list.firstNotNullOfOrNull { it.location }?.let { key to it } }
            if (targets.isNotEmpty()) {
                val pool = Executors.newFixedThreadPool(minOf(8, targets.size)) { runnable ->
                    Thread(runnable, "lan-upnp-describe").apply { isDaemon = true }
                }
                try {
                    val futures = targets.map { (key, location) ->
                        pool.submit(Callable { key to fetchDeviceDescription(location, 1_800) })
                    }
                    futures.forEach { future ->
                        val parsed = runCatching { future.get(2_600, TimeUnit.MILLISECONDS) }.getOrNull()
                        val device = parsed?.second
                        if (parsed != null && device != null) described[parsed.first] = device
                    }
                } finally {
                    pool.shutdownNow()
                }
            }
        }

        return groups.map { (key, list) ->
            val deviceTypes = list.map { it.type }.filterNot { it.equals("upnp:rootdevice", true) }.distinct()
            val server = list.firstNotNullOfOrNull { it.server }
            val location = list.firstNotNullOfOrNull { it.location }
            val description = described[key]
            val uri = location?.let { runCatching { URI(it) }.getOrNull() }
            SsdpDevice(
                name = description?.friendlyName ?: key.removePrefix("uuid:").take(36),
                category = ssdpCategory(
                    deviceTypes = deviceTypes,
                    server = server,
                    manufacturer = description?.manufacturer,
                    model = description?.modelName,
                    location = location,
                ),
                address = uri?.host,
                port = uri?.port?.takeIf { it in 1..65535 },
                location = location,
                server = server,
                deviceTypes = deviceTypes,
                manufacturer = description?.manufacturer,
                model = description?.modelName,
            )
        }.take(limit * 2)
    }

    /* -------------------------------- mDNS/DNS-SD ------------------------------- */

    private fun mdnsDiscover(
        services: List<String>,
        timeoutMs: Int,
        errors: MutableList<String>,
    ): MdnsScan {
        val assembler = MdnsAssembler()
        val lock = acquireMulticastLock()
        var socket: DatagramSocket? = null
        try {
            val group = InetAddress.getByName(MDNS_MULTICAST_ADDRESS)
            val target = InetSocketAddress(group, MDNS_MULTICAST_PORT)
            val multicast = runCatching {
                MulticastSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(MDNS_MULTICAST_PORT))
                    soTimeout = MDNS_POLL_MS
                    timeToLive = 255
                }
            }.getOrNull()

            var unicastOnly = multicast == null
            if (multicast != null) {
                socket = multicast
                val groupAddress = InetSocketAddress(group, MDNS_MULTICAST_PORT)
                var joined = false
                runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull()?.let { interfaces ->
                    for (nif in Collections.list(interfaces)) {
                        if (!nif.isUp || nif.isLoopback || !nif.supportsMulticast()) continue
                        val hasIpv4 = Collections.list(nif.inetAddresses)
                            .any { it is Inet4Address && !it.isLoopbackAddress }
                        if (!hasIpv4) continue
                        if (runCatching { multicast.joinGroup(groupAddress, nif) }.isSuccess) joined = true
                    }
                }
                if (!joined) {
                    runCatching { multicast.close() }
                    socket = DatagramSocket().apply { soTimeout = MDNS_POLL_MS }
                    unicastOnly = true
                    errors += "mdns: 未能加入组播组（VPN/热点或系统限制），已退回单播询问，结果可能不完整"
                }
            } else {
                socket = DatagramSocket().apply { soTimeout = MDNS_POLL_MS }
                errors += "mdns: 5353 端口被占用，已退回单播询问"
            }

            val daemon = socket ?: return MdnsScan(emptyList(), emptyList())
            val payload = buildMdnsQuery(listOf(MDNS_SERVICE_ENUM) + services, 0, unicastOnly)
            daemon.send(DatagramPacket(payload, payload.size, target))

            val started = System.currentTimeMillis()
            val deadline = started + timeoutMs
            var resent = false
            while (System.currentTimeMillis() < deadline) {
                val buffer = ByteArray(9_000)
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    daemon.receive(packet)
                } catch (_: SocketTimeoutException) {
                    // 轮询超时：继续等到 deadline
                } catch (e: IOException) {
                    errors += "mdns: ${describeNetworkError(e)}"
                    break
                }
                if (packet.length > 12) {
                    // 解析失败（非 mDNS 流量/截断报文）直接跳过，不影响其他响应。
                    parseDnsPacket(packet.data, packet.length)?.let { assembler.add(it) }
                }
                if (!resent && System.currentTimeMillis() - started > 1_200) {
                    resent = true
                    runCatching { daemon.send(DatagramPacket(payload, payload.size, target)) }
                }
            }
        } catch (e: Exception) {
            errors += "mdns: ${describeNetworkError(e)}"
        } finally {
            runCatching { socket?.close() }
            runCatching { lock?.release() }
        }
        return MdnsScan(assembler.devices(), assembler.serviceTypes())
    }

    /** 部分机型/系统策略下没有组播锁收不到 224.0.0.251 的多播响应。 */
    private fun acquireMulticastLock(): WifiManager.MulticastLock? {
        val ctx = context?.applicationContext ?: return null
        val manager = ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        return runCatching {
            manager.createMulticastLock("andagent-discovery").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()
    }

    /* ------------------------------- upnp_describe ------------------------------ */

    private fun describe(args: JsonObject): String {
        val url = args.strArg("url") ?: throw IllegalArgumentException("缺少 url")
        requireHttpUrl(url)
        val timeout = (args.intArg("timeout_ms") ?: 4_000).coerceIn(500, 15_000)
        val errors = mutableListOf<String>()
        for (candidate in descriptionCandidates(url)) {
            val text = fetchText(candidate, timeout)
            if (text == null) {
                errors += "$candidate: 请求失败"
                continue
            }
            val device = parseUpnpDeviceDescription(text, candidate)
            if (device == null) {
                errors += "$candidate: 不是 UPnP 设备描述文档"
                continue
            }
            return buildJsonObject {
                put("url", candidate)
                putText("friendlyName", device.friendlyName)
                putText("deviceType", device.deviceType)
                putText(
                    "category",
                    ssdpCategory(
                        deviceTypes = listOfNotNull(device.deviceType),
                        server = null,
                        manufacturer = device.manufacturer,
                        model = device.modelName,
                        location = candidate,
                    ),
                )
                putText("manufacturer", device.manufacturer)
                putText("manufacturerUrl", device.manufacturerUrl)
                putText("modelName", device.modelName)
                putText("modelNumber", device.modelNumber)
                putText("serialNumber", device.serialNumber)
                putText("udn", device.udn)
                putText("presentationUrl", device.presentationUrl)
                put("embeddedDeviceCount", device.embeddedDeviceCount)
                putJsonArray("icons") { device.iconUrls.forEach { add(it) } }
                putJsonArray("services") {
                    device.services.forEach { service ->
                        addJsonObject {
                            put("serviceType", service.serviceType)
                            putText("serviceId", service.serviceId)
                            putText("controlURL", service.controlUrl)
                            putText("scpdURL", service.scpdUrl)
                            putText("eventSubURL", service.eventSubUrl)
                        }
                    }
                }
                put("descriptionChars", text.length)
            }.toString()
        }
        throw IllegalStateException(
            "未取到 UPnP 设备描述（尝试 ${descriptionCandidates(url).size} 个地址）：${errors.joinToString("; ")}",
        )
    }

    private fun fetchDeviceDescription(url: String, timeoutMs: Int): UpnpDevice? {
        val text = fetchText(url, timeoutMs) ?: return null
        return parseUpnpDeviceDescription(text, url)
    }

    private fun fetchText(url: String, timeoutMs: Int): String? {
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return null
        val client = OkHttpClient.Builder()
            .connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .callTimeout(timeoutMs + 800L, TimeUnit.MILLISECONDS)
            .build()
        return runCatching {
            client.newCall(
                Request.Builder().url(url).header("User-Agent", SSDP_USER_AGENT).get().build(),
            ).execute().use { response ->
                if (!response.isSuccessful) null else readBody(response, MAX_DESCRIPTION_BYTES).text
            }
        }.getOrNull()
    }
}

/* ------------------------------ merged device ------------------------------ */

private class MergedDevice {
    var name: String? = null
    var category: String = "unknown"
    var address: String? = null
    var port: Int? = null
    var host: String? = null
    var manufacturer: String? = null
    var model: String? = null
    var server: String? = null
    var location: String? = null
    val protocols = linkedSetOf<String>()
    val services = linkedSetOf<String>()
    val txt = linkedMapOf<String, String>()
}

private data class SsdpDevice(
    val name: String,
    val category: String,
    val address: String?,
    val port: Int?,
    val location: String?,
    val server: String?,
    val deviceTypes: List<String>,
    val manufacturer: String?,
    val model: String?,
)

private data class MdnsScan(val devices: List<MdnsDevice>, val serviceTypes: List<String>)

private fun SsdpDevice.toMerged(): MergedDevice = MergedDevice().apply {
    name = this@toMerged.name
    category = this@toMerged.category
    address = this@toMerged.address
    port = this@toMerged.port
    location = this@toMerged.location
    server = this@toMerged.server
    manufacturer = this@toMerged.manufacturer
    model = this@toMerged.model
    protocols += "ssdp"
    services += deviceTypes
}

private fun MdnsDevice.toMerged(): MergedDevice = MergedDevice().apply {
    name = this@toMerged.name
    category = this@toMerged.category
    address = addresses.firstOrNull()
    port = this@toMerged.port
    host = this@toMerged.host
    manufacturer = this@toMerged.txt["mf"] ?: this@toMerged.txt["manufacturer"]
    model = this@toMerged.txt["md"] ?: this@toMerged.txt["model"]
    location = this@toMerged.txt["base_url"] ?: this@toMerged.txt["location"]
    protocols += "mdns"
    services += serviceTypes
    this@toMerged.txt.forEach { (key, value) -> if (txt.size < 24) txt[key] = value }
}

private fun merge(into: MutableMap<String, MergedDevice>, incoming: MergedDevice) {
    val key = incoming.address ?: "name:${(incoming.name ?: "").lowercase()}"
    val existing = into[key]
    if (existing == null) {
        into[key] = incoming
        return
    }
    if (categoryRank(incoming.category) < categoryRank(existing.category)) {
        existing.category = incoming.category
    }
    val incomingName = incoming.name?.takeIf { it.isNotBlank() }
    val currentName = existing.name
    if (incomingName != null && (currentName.isNullOrBlank() || incomingName.length > currentName.length)) {
        existing.name = incomingName
    }
    existing.address = existing.address ?: incoming.address
    existing.port = existing.port ?: incoming.port
    existing.host = existing.host ?: incoming.host
    existing.manufacturer = existing.manufacturer ?: incoming.manufacturer
    existing.model = existing.model ?: incoming.model
    existing.server = existing.server ?: incoming.server
    existing.location = existing.location ?: incoming.location
    existing.protocols += incoming.protocols
    incoming.services.forEach { service ->
        if (existing.services.size < 14) existing.services += service
    }
    incoming.txt.forEach { (textKey, value) ->
        if (existing.txt.size < 24 && !existing.txt.containsKey(textKey)) existing.txt[textKey] = value
    }
}

/* -------------------------------- helpers -------------------------------- */

private fun describeNetworkError(error: Throwable): String {
    val message = error.message ?: error.javaClass.simpleName
    val lowered = message.lowercase()
    return when {
        lowered.contains("permission denied") || lowered.contains("eacces") ->
            "组播被系统拒绝（需要 CHANGE_WIFI_MULTICAST_STATE 权限）；$message"
        lowered.contains("network is unreachable") || lowered.contains("no route") ->
            "当前网络不支持组播（移动数据/VPN 常见），请连 Wi-Fi 后重试；$message"
        lowered.contains("address already in use") -> "端口被占用；$message"
        else -> message
    }
}

private fun JsonObjectBuilder.putText(key: String, value: String?) {
    val text = value?.takeIf { it.isNotBlank() } ?: return
    put(key, text)
}
