package com.dc.andagent.tools

import java.io.ByteArrayOutputStream
import java.net.InetAddress

/**
 * R121: 局域网语义化发现用的纯函数协议层。
 *
 * 三个协议片段全部在这里离线可测（不碰 Android API、不碰 socket）：
 * - SSDP：M-SEARCH 请求构造 + 响应头解析
 * - UPnP：设备描述 XML 解析（根 device + serviceList + iconList）
 * - mDNS/DNS-SD：查询报文构造 + DNS 报文解析（含名称压缩指针）+ 记录装配成设备
 *
 * 工具层（[DiscoveryTool]）只负责收发报文与 JSON 输出。
 */

/* ================================= SSDP ================================= */

internal const val SSDP_MULTICAST_ADDRESS = "239.255.255.250"
internal const val SSDP_MULTICAST_PORT = 1900
internal const val SSDP_USER_AGENT = "Android UPnP/1.1 AndAgent/1.0"

/** 一条 SSDP 通告（200 OK 响应或 NOTIFY 通告）。 */
internal data class SsdpAdvertisement(
    /** ST（响应）或 NT（通告）。 */
    val type: String,
    val usn: String?,
    val location: String?,
    val server: String?,
    val cacheControl: String?,
    val isNotify: Boolean,
)

/** SSDP M-SEARCH；MX 是设备随机回应延迟上限（秒），必须 1..5。 */
internal fun buildSsdpSearch(
    st: String = "ssdp:all",
    mx: Int = 2,
    userAgent: String = SSDP_USER_AGENT,
): String = buildString {
    append("M-SEARCH * HTTP/1.1\r\n")
    append("HOST: ").append(SSDP_MULTICAST_ADDRESS).append(':').append(SSDP_MULTICAST_PORT).append("\r\n")
    append("MAN: \"ssdp:discover\"\r\n")
    append("MX: ").append(mx.coerceIn(1, 5)).append("\r\n")
    append("ST: ").append(st.trim().ifBlank { "ssdp:all" }).append("\r\n")
    append("USER-AGENT: ").append(userAgent).append("\r\n")
    append("\r\n")
}

/** 解析 SSDP 响应/NOTIFY；非 SSDP 报文或缺少 ST/NT 时返回 null。 */
internal fun parseSsdpAdvertisement(raw: String): SsdpAdvertisement? {
    val text = raw.replace("\r\n", "\n").trim()
    if (text.isEmpty()) return null
    val lines = text.split('\n')
    val status = lines.first().trim()
    val isResponse = status.startsWith("HTTP/1.1 200", true) || status.startsWith("HTTP/1.0 200", true)
    val isNotify = status.startsWith("NOTIFY", true)
    if (!isResponse && !isNotify) return null

    val headers = linkedMapOf<String, String>()
    lines.drop(1).forEach { line ->
        val colon = line.indexOf(':')
        if (colon <= 0) return@forEach
        val key = line.substring(0, colon).trim().lowercase()
        val value = line.substring(colon + 1).trim()
        if (key.isNotEmpty() && value.isNotEmpty()) headers[key] = value
    }
    val type = headers["st"] ?: headers["nt"] ?: return null
    if (type.isBlank()) return null
    return SsdpAdvertisement(
        type = type,
        usn = headers["usn"],
        location = headers["location"],
        server = headers["server"],
        cacheControl = headers["cache-control"],
        isNotify = isNotify,
    )
}

/* =============================== UPnP XML =============================== */

internal data class UpnpService(
    val serviceType: String,
    val serviceId: String?,
    /** 绝对地址（相对地址已按描述文档 URL 解析）。SOAP 控制入口。 */
    val controlUrl: String?,
    val eventSubUrl: String?,
    val scpdUrl: String?,
)

internal data class UpnpDevice(
    val deviceType: String?,
    val friendlyName: String?,
    val manufacturer: String?,
    val manufacturerUrl: String?,
    val modelName: String?,
    val modelNumber: String?,
    val serialNumber: String?,
    val udn: String?,
    val presentationUrl: String?,
    val iconUrls: List<String>,
    val services: List<UpnpService>,
    val embeddedDeviceCount: Int,
)

private val DEVICE_TAG = Regex("<(/?)(?:[A-Za-z0-9_.-]+:)?device\\b[^>]*>", RegexOption.IGNORE_CASE)
private val SERVICE_BLOCK = Regex(
    "<(?:[A-Za-z0-9_.-]+:)?service\\b[^>]*>([\\s\\S]*?)</(?:[A-Za-z0-9_.-]+:)?service\\s*>",
    RegexOption.IGNORE_CASE,
)
private val ICON_BLOCK = Regex(
    "<(?:[A-Za-z0-9_.-]+:)?icon\\b[^>]*>([\\s\\S]*?)</(?:[A-Za-z0-9_.-]+:)?icon\\s*>",
    RegexOption.IGNORE_CASE,
)

/**
 * 解析 UPnP 设备描述文档，只取根 device（嵌套的 embedded device 只计数）。
 * 文档里没有 `<device>` 时返回 null（调用方据此判断该 URL 不是描述文档）。
 */
internal fun parseUpnpDeviceDescription(xml: String, baseUrl: String? = null): UpnpDevice? {
    val root = rootDeviceBlock(xml) ?: return null
    val services = SERVICE_BLOCK.findAll(root).map { match ->
        val block = match.groupValues[1]
        UpnpService(
            serviceType = tagText(block, "serviceType", 160) ?: "unknown",
            serviceId = tagText(block, "serviceId", 160),
            controlUrl = resolveUrl(baseUrl, tagText(block, "controlURL", 400)),
            eventSubUrl = resolveUrl(baseUrl, tagText(block, "eventSubURL", 400)),
            scpdUrl = resolveUrl(baseUrl, tagText(block, "SCPDURL", 400)),
        )
    }.take(32).toList()

    val icons = ICON_BLOCK.findAll(root)
        .mapNotNull { tagText(it.groupValues[1], "url", 400) }
        .mapNotNull { resolveUrl(baseUrl, it) }
        .distinct()
        .take(8)
        .toList()

    val embedded = DEVICE_TAG.findAll(root).count { it.groupValues[1].isEmpty() } - 1
    return UpnpDevice(
        deviceType = tagText(root, "deviceType", 160),
        friendlyName = tagText(root, "friendlyName", 160),
        manufacturer = tagText(root, "manufacturer", 120),
        manufacturerUrl = resolveUrl(baseUrl, tagText(root, "manufacturerURL", 300)),
        modelName = tagText(root, "modelName", 120),
        modelNumber = tagText(root, "modelNumber", 80),
        serialNumber = tagText(root, "serialNumber", 80),
        udn = tagText(root, "UDN", 120),
        presentationUrl = resolveUrl(baseUrl, tagText(root, "presentationURL", 400)),
        iconUrls = icons,
        services = services,
        embeddedDeviceCount = embedded.coerceAtLeast(0),
    )
}

/** 按 `<device>` 开闭标签配对取最外层 device 块（内嵌 device 不会被截断）。 */
private fun rootDeviceBlock(xml: String): String? {
    var depth = 0
    var start = -1
    for (match in DEVICE_TAG.findAll(xml)) {
        if (match.groupValues[1].isEmpty()) {
            if (depth == 0) start = match.range.first
            depth++
        } else if (depth > 0) {
            depth--
            if (depth == 0 && start >= 0) return xml.substring(start, match.range.last + 1)
        }
    }
    return null
}

internal fun tagText(xml: String, tag: String, maxChars: Int): String? {
    val match = Regex(
        "<(?:[A-Za-z0-9_.-]+:)?$tag\\b[^>]*>([\\s\\S]*?)</(?:[A-Za-z0-9_.-]+:)?$tag\\s*>",
        RegexOption.IGNORE_CASE,
    ).find(xml) ?: return null
    return unescapeXml(match.groupValues[1]).trim().take(maxChars).ifBlank { null }
}

internal fun unescapeXml(raw: String): String {
    if (!raw.contains('&')) return raw
    return raw
        .replace(Regex("&#(\\d{1,7});")) { m ->
            m.groupValues[1].toIntOrNull()?.let { code -> runCatching { String(Character.toChars(code)) }.getOrNull() } ?: m.value
        }
        .replace(Regex("&#[xX]([0-9A-Fa-f]{1,6});")) { m ->
            m.groupValues[1].toIntOrNull(16)?.let { code -> runCatching { String(Character.toChars(code)) }.getOrNull() } ?: m.value
        }
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
}

/** 把描述文档里的相对 URL 解析成绝对 URL（base 缺失或解析失败时原样返回）。 */
internal fun resolveUrl(baseUrl: String?, raw: String?): String? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val base = baseUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return value
    return runCatching { java.net.URI(base).resolve(value).toString() }.getOrElse { value }
}

/**
 * upnp_describe 的候选地址：给的是具体 xml 就只试它；
 * 给的是设备根地址时补常见描述文档路径（不同厂商命名不一）。
 */
internal fun descriptionCandidates(raw: String): List<String> {
    val url = raw.trim()
    val candidates = linkedSetOf(url)
    if (!url.lowercase().substringBefore('?').endsWith(".xml")) {
        val base = if (url.endsWith("/")) url else "$url/"
        listOf("description.xml", "rootDesc.xml", "device.xml", "upnp/description.xml")
            .forEach { candidates += base + it }
    }
    return candidates.take(5)
}

/* ================================= mDNS ================================= */

internal const val DNS_TYPE_A = 1
internal const val DNS_TYPE_PTR = 12
internal const val DNS_TYPE_TXT = 16
internal const val DNS_TYPE_AAAA = 28
internal const val DNS_TYPE_SRV = 33
internal const val DNS_TYPE_OPT = 41
internal const val DNS_CLASS_IN = 1

/** `_services._dns-sd._udp.local` 元查询：枚举网段内所有服务类型。 */
internal const val MDNS_SERVICE_ENUM = "_services._dns-sd._udp.local"

/**
 * 内置常用服务类型（不含 `.local` 后缀）。
 * 覆盖 R121 的目标设备（电视/音箱/打印机/Home Assistant）与后续刀：
 * `_andagent._tcp` 是 R81 互传的发现入口，`_home-assistant._tcp` 是 R78 智能家居入口。
 */
internal val MDNS_SERVICE_PRESETS = listOf(
    "_googlecast._tcp",
    "_airplay._tcp",
    "_raop._tcp",
    "_spotify-connect._tcp",
    "_sonos._tcp",
    "_ipp._tcp",
    "_ipps._tcp",
    "_printer._tcp",
    "_pdl-datastream._tcp",
    "_home-assistant._tcp",
    "_hap._tcp",
    "_esphomelib._tcp",
    "_matter._tcp",
    "_matterc._udp",
    "_androidtvremote2._tcp",
    "_amzn-wplay._tcp",
    "_viziocast._tcp",
    "_rtsp._tcp",
    "_http._tcp",
    "_https._tcp",
    "_ssh._tcp",
    "_smb._tcp",
    "_afpovertcp._tcp",
    "_nfs._tcp",
    "_companion-link._tcp",
    "_andagent._tcp",
)

internal data class DnsQuestion(val name: String, val type: Int, val clazz: Int)

internal sealed class DnsRData {
    data class Ptr(val target: String) : DnsRData()
    data class Srv(val priority: Int, val weight: Int, val port: Int, val target: String) : DnsRData()
    data class Txt(val entries: List<String>) : DnsRData()
    data class Address(val ip: String) : DnsRData()
    data class Other(val type: Int) : DnsRData()
}

internal data class DnsRecord(val name: String, val type: Int, val ttl: Long, val data: DnsRData)

internal data class DnsPacket(
    val id: Int,
    val isResponse: Boolean,
    val questions: List<DnsQuestion>,
    val records: List<DnsRecord>,
)

/** `_airplay._tcp` → `_airplay._tcp.local`（去空白、小写、补 .local）。 */
internal fun normalizeServiceType(raw: String): String {
    var text = raw.trim().lowercase().trimEnd('.')
    if (text.isEmpty()) return ""
    if (!text.endsWith(".local")) text = "$text.local"
    return text
}

/** 展示用：去掉 `.local` 后缀。 */
internal fun shortServiceType(serviceType: String): String =
    normalizeServiceType(serviceType).removeSuffix(".local")

/** 逗号/空格/分号分隔的服务类型列表。 */
internal fun splitServiceTypes(raw: String): List<String> =
    raw.split(',', ' ', ';', '\n').mapNotNull { it.trim().takeIf { value -> value.isNotEmpty() } }

/**
 * 构造 mDNS 查询：单个报文携带多个 PTR 问题，设备会在一次响应里带回
 * PTR/SRV/TXT/A，比逐个服务类型串行查询快一个数量级。
 */
internal fun buildMdnsQuery(
    serviceTypes: List<String>,
    transactionId: Int = 0,
    unicastResponse: Boolean = false,
): ByteArray {
    val questions = serviceTypes.mapNotNull { normalizeServiceType(it).takeIf { name -> name.isNotEmpty() } }
        .distinct()
        .take(64)
    val out = ByteArrayOutputStream(512)
    fun u16(value: Int) {
        out.write((value shr 8) and 0xFF)
        out.write(value and 0xFF)
    }
    u16(transactionId and 0xFFFF)
    u16(0x0000) // 标准查询，无标志位
    u16(questions.size)
    u16(0)
    u16(0)
    u16(0)
    questions.forEach { name ->
        out.write(encodeDnsName(name))
        u16(DNS_TYPE_PTR)
        u16(if (unicastResponse) DNS_CLASS_IN or 0x8000 else DNS_CLASS_IN) // QU 位请求单播应答
    }
    return out.toByteArray()
}

internal fun encodeDnsName(name: String): ByteArray {
    val out = ByteArrayOutputStream(64)
    name.trimEnd('.').split('.').forEach { label ->
        val bytes = label.toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty()) return@forEach
        require(bytes.size <= 63) { "DNS 标签超过 63 字节: $label" }
        out.write(bytes.size)
        out.write(bytes)
    }
    out.write(0)
    require(out.size() <= 255) { "DNS 名称超过 255 字节: $name" }
    return out.toByteArray()
}

private class DnsCursor(private val data: ByteArray, private val limit: Int, var pos: Int) {
    fun u8(): Int {
        require(pos < limit) { "DNS 报文截断" }
        return data[pos++].toInt() and 0xFF
    }

    fun u16(): Int = (u8() shl 8) or u8()

    fun u32(): Long = (u16().toLong() shl 16) or u16().toLong()

    fun name(): String {
        val labels = mutableListOf<String>()
        var cursor = pos
        var advanced = -1
        var jumps = 0
        while (true) {
            require(cursor < limit) { "DNS 名称越界" }
            val length = data[cursor].toInt() and 0xFF
            if (length == 0) {
                cursor++
                break
            }
            if (length and 0xC0 == 0xC0) {
                require(cursor + 1 < limit) { "DNS 压缩指针截断" }
                val target = ((length and 0x3F) shl 8) or (data[cursor + 1].toInt() and 0xFF)
                if (advanced < 0) advanced = cursor + 2
                cursor = target
                require(++jumps <= 32) { "DNS 压缩指针成环" }
                continue
            }
            require(length <= 63 && cursor + 1 + length <= limit) { "DNS 标签越界" }
            labels += String(data, cursor + 1, length, Charsets.UTF_8)
            cursor += 1 + length
        }
        pos = if (advanced >= 0) advanced else cursor
        return labels.joinToString(".")
    }
}

/** 解析 mDNS 响应；报文不合法时返回 null（绝不抛给调用方）。 */
internal fun parseDnsPacket(data: ByteArray, length: Int = data.size): DnsPacket? {
    if (length < 12) return null
    return runCatching {
        val cursor = DnsCursor(data, length, 0)
        val id = cursor.u16()
        val flags = cursor.u16()
        val questionCount = cursor.u16()
        val answerCount = cursor.u16()
        val authorityCount = cursor.u16()
        val additionalCount = cursor.u16()
        val isResponse = (flags and 0x8000) != 0

        val questions = mutableListOf<DnsQuestion>()
        repeat(questionCount) {
            val name = cursor.name()
            val type = cursor.u16()
            val clazz = cursor.u16() and 0x7FFF
            questions += DnsQuestion(name, type, clazz)
        }

        val records = mutableListOf<DnsRecord>()
        val total = answerCount + authorityCount + additionalCount
        repeat(total) {
            val name = cursor.name()
            val type = cursor.u16()
            cursor.u16() // class
            val ttl = cursor.u32()
            val rdLength = cursor.u16()
            require(cursor.pos + rdLength <= length) { "DNS 记录数据越界" }
            val start = cursor.pos
            records += DnsRecord(name, type, ttl, parseRData(data, length, start, rdLength, type))
            cursor.pos = start + rdLength
        }
        DnsPacket(id, isResponse, questions, records)
    }.getOrNull()
}

private fun parseRData(data: ByteArray, limit: Int, start: Int, rdLength: Int, type: Int): DnsRData =
    runCatching {
        when (type) {
            DNS_TYPE_PTR -> DnsRData.Ptr(DnsCursor(data, limit, start).name())
            DNS_TYPE_SRV -> {
                val cursor = DnsCursor(data, limit, start)
                val priority = cursor.u16()
                val weight = cursor.u16()
                val port = cursor.u16()
                DnsRData.Srv(priority, weight, port, cursor.name())
            }
            DNS_TYPE_TXT -> {
                val entries = mutableListOf<String>()
                var pos = start
                val end = start + rdLength
                while (pos < end && entries.size < 16) {
                    val size = data[pos].toInt() and 0xFF
                    pos++
                    if (pos + size > end) break
                    val text = String(data, pos, size, Charsets.UTF_8).take(200)
                    pos += size
                    if (text.isNotEmpty()) entries += text
                }
                DnsRData.Txt(entries)
            }
            DNS_TYPE_A -> {
                require(rdLength == 4) { "A 记录长度异常" }
                DnsRData.Address(
                    "${data[start].toInt() and 0xFF}.${data[start + 1].toInt() and 0xFF}." +
                        "${data[start + 2].toInt() and 0xFF}.${data[start + 3].toInt() and 0xFF}",
                )
            }
            DNS_TYPE_AAAA -> {
                require(rdLength == 16) { "AAAA 记录长度异常" }
                val bytes = data.copyOfRange(start, start + 16)
                DnsRData.Address(InetAddress.getByAddress(bytes).hostAddress ?: "")
            }
            else -> DnsRData.Other(type)
        }
    }.getOrElse { DnsRData.Other(type) }

internal data class MdnsDevice(
    /** 人类可读名称：TXT 的 fn/name 优先，其次实例名。 */
    val name: String,
    val serviceTypes: List<String>,
    /** SRV target（如 `living-room-tv.local`）。 */
    val host: String?,
    val port: Int?,
    val addresses: List<String>,
    val txt: Map<String, String>,
    val category: String,
)

/** 把多个响应报文里的 DNS 记录装配成设备（PTR→实例，SRV→端口/主机，A/AAAA→IP）。 */
internal class MdnsAssembler {
    private val records = mutableListOf<DnsRecord>()

    fun add(packet: DnsPacket) {
        if (!packet.isResponse) return
        if (records.size < 4096) records += packet.records.take(1024)
    }

    /** 元查询枚举出的服务类型（展示用，已去掉 `.local`）。 */
    fun serviceTypes(): List<String> = records
        .asSequence()
        .filter { it.type == DNS_TYPE_PTR && it.name.equals(MDNS_SERVICE_ENUM, true) }
        .mapNotNull { (it.data as? DnsRData.Ptr)?.target }
        .filter { it.startsWith("_") }
        .map { shortServiceType(it) }
        .distinct()
        .take(48)
        .toList()

    fun devices(): List<MdnsDevice> {
        val instances = linkedMapOf<String, MutableSet<String>>()
        val instanceNames = linkedMapOf<String, String>()
        records.forEach { record ->
            if (record.type != DNS_TYPE_PTR) return@forEach
            val target = (record.data as? DnsRData.Ptr)?.target ?: return@forEach
            if (record.name.equals(MDNS_SERVICE_ENUM, true)) return@forEach
            if (!record.name.startsWith("_")) return@forEach
            val key = target.lowercase()
            instances.getOrPut(key) { linkedSetOf() } += shortServiceType(record.name)
            instanceNames[key] = target
        }

        val srv = records.filter { it.type == DNS_TYPE_SRV }.associateBy { it.name.lowercase() }
        val txt = records.filter { it.type == DNS_TYPE_TXT }.associateBy { it.name.lowercase() }
        val addresses = records
            .filter { it.type == DNS_TYPE_A || it.type == DNS_TYPE_AAAA }
            .groupBy { it.name.lowercase() }

        return instances.mapNotNull { (key, services) ->
            val instance = instanceNames[key] ?: return@mapNotNull null
            val srvData = srv[key]?.data as? DnsRData.Srv
            val host = srvData?.target
            val ips = host
                ?.let { addresses[it.lowercase()] }
                ?.mapNotNull { (it.data as? DnsRData.Address)?.ip?.takeIf { ip -> ip.isNotBlank() } }
                ?.distinct()
                ?.take(4)
                ?: emptyList()
            val txtMap = (txt[key]?.data as? DnsRData.Txt)
                ?.let { parseTxtEntries(it.entries) }
                ?: emptyMap()
            val label = instanceLabel(instance)
            val name = (txtMap["fn"] ?: txtMap["name"] ?: txtMap["ty"] ?: label).take(80)
            MdnsDevice(
                name = name,
                serviceTypes = services.toList(),
                host = host,
                port = srvData?.port?.takeIf { it in 1..65535 },
                addresses = ips,
                txt = txtMap,
                category = mdnsCategory(services.toList(), txtMap),
            )
        }
    }
}

/** 实例名 `Living Room TV._airplay._tcp.local` → `Living Room TV`。 */
internal fun instanceLabel(instance: String): String {
    val marker = instance.indexOf("._")
    return if (marker > 0) instance.substring(0, marker) else instance
}

internal fun parseTxtEntries(entries: List<String>): Map<String, String> {
    val out = linkedMapOf<String, String>()
    entries.forEach { entry ->
        val idx = entry.indexOf('=')
        if (idx < 0) {
            val key = entry.trim()
            if (key.isNotEmpty() && out.size < 24) out[key] = ""
            return@forEach
        }
        if (idx == 0 || out.size >= 24) return@forEach
        out[entry.substring(0, idx).trim().lowercase()] = entry.substring(idx + 1).trim().take(200)
    }
    return out
}

/* ============================== 语义分类 ============================== */

/**
 * mDNS 服务类型 → 设备语义分类。
 * 分类是「给人看的一等公民」：tv / speaker / printer / home_assistant / router /
 * nas / camera / cast / computer / phone / iot / andagent / server / unknown。
 */
internal fun mdnsCategory(serviceTypes: List<String>, txt: Map<String, String>): String {
    val types = serviceTypes.map { shortServiceType(it) }.toSet()
    val model = (txt["md"] ?: txt["model"] ?: txt["ty"] ?: "").lowercase()

    fun has(vararg candidates: String) = candidates.any { it in types }

    return when {
        has("_home-assistant._tcp") -> "home_assistant"
        has("_ipp._tcp", "_ipps._tcp", "_printer._tcp", "_pdl-datastream._tcp") -> "printer"
        has("_andagent._tcp") -> "andagent"
        has("_googlecast._tcp") -> when {
            model.contains("tv") || model.contains("chromecast") || model.contains("display") -> "tv"
            model.contains("audio") || model.contains("speaker") || model.contains("home") ||
                model.contains("mini") || model.contains("nest") -> "speaker"
            else -> "cast"
        }
        // AirPlay 同时被电视和 HomePod 类音箱使用：TXT model 里的 AudioAccessory 才是音箱。
        has("_airplay._tcp") -> if (
            model.contains("audioaccessory") || model.contains("homepod") || model.contains("speaker")
        ) "speaker" else "tv"
        has("_androidtvremote2._tcp", "_amzn-wplay._tcp", "_viziocast._tcp") -> "tv"
        has("_raop._tcp", "_spotify-connect._tcp", "_sonos._tcp") -> "speaker"
        has("_esphomelib._tcp", "_hap._tcp", "_matter._tcp", "_matterc._udp") -> "iot"
        has("_smb._tcp", "_afpovertcp._tcp", "_nfs._tcp") -> "nas"
        has("_ssh._tcp", "_workstation._tcp", "_companion-link._tcp") -> "computer"
        has("_rtsp._tcp") -> "camera"
        has("_http._tcp", "_https._tcp") -> "server"
        else -> "unknown"
    }
}

/** SSDP/UPnP 设备类型 + 响应头 → 设备语义分类。 */
internal fun ssdpCategory(
    deviceTypes: List<String>,
    server: String?,
    manufacturer: String?,
    model: String?,
    location: String?,
): String {
    val haystack = listOfNotNull(deviceTypes.joinToString(" "), server, manufacturer, model, location)
        .joinToString(" ")
        .lowercase()
    val renderer = deviceTypes.any { it.contains("mediarenderer", true) }
    val mediaServer = deviceTypes.any { it.contains("mediaserver", true) }

    return when {
        haystack.contains("home assistant") || (location?.contains(":8123") == true) -> "home_assistant"
        haystack.contains("printer") || haystack.contains("ipp:") -> "printer"
        haystack.contains("internetgatewaydevice") || haystack.contains("wandevice") -> "router"
        renderer -> when {
            haystack.contains("speaker") || haystack.contains("soundbar") || haystack.contains("sound") ||
                haystack.contains("audio") || haystack.contains("sonos") || haystack.contains("bose") ||
                haystack.contains("denon") || haystack.contains("heos") || haystack.contains("yamaha") ||
                haystack.contains("music") || haystack.contains("avr") -> "speaker"
            haystack.contains("tv") || haystack.contains("samsung") || haystack.contains("lg ") ||
                haystack.contains("webos") || haystack.contains("tizen") || haystack.contains("sony") ||
                haystack.contains("philips") || haystack.contains("hisense") || haystack.contains("tcl ") ||
                haystack.contains("roku") || haystack.contains("chromecast") || haystack.contains("appletv") ||
                haystack.contains("firetv") || haystack.contains("projector") -> "tv"
            else -> "cast"
        }
        mediaServer -> when {
            haystack.contains("synology") || haystack.contains("qnap") || haystack.contains("nas") ||
                haystack.contains("plex") -> "nas"
            else -> "server"
        }
        haystack.contains("camera") || haystack.contains("onvif") -> "camera"
        else -> "unknown"
    }
}

/** 合并同一设备多协议结果时的优先级：数值越小越具体。 */
internal fun categoryRank(category: String): Int = when (category) {
    "home_assistant" -> 0
    "printer" -> 1
    "tv" -> 2
    "speaker" -> 3
    "nas" -> 4
    "camera" -> 5
    "router" -> 6
    "cast" -> 7
    "andagent" -> 8
    "computer" -> 9
    "server" -> 10
    "phone" -> 11
    "iot" -> 12
    "unknown" -> 99
    else -> 50
}
