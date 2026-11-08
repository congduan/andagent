package com.dc.andagent.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class DiscoveryCodecTest {

    /* ---------------------------------- SSDP ---------------------------------- */

    @Test fun ssdpSearchCarriesRequiredHeaders() {
        val request = buildSsdpSearch()
        assertTrue(request.startsWith("M-SEARCH * HTTP/1.1\r\n"))
        assertTrue(request.contains("HOST: 239.255.255.250:1900\r\n"))
        assertTrue(request.contains("MAN: \"ssdp:discover\"\r\n"))
        assertTrue(request.contains("ST: ssdp:all\r\n"))
        assertTrue(request.endsWith("\r\n\r\n"))
    }

    @Test fun ssdpSearchClampsMxAndKeepsCustomSt() {
        assertTrue(buildSsdpSearch("urn:schemas-upnp-org:device:MediaRenderer:1", mx = 99).contains("MX: 5"))
        assertTrue(buildSsdpSearch(mx = 0).contains("MX: 1"))
        assertTrue(buildSsdpSearch("  ", mx = 2).contains("ST: ssdp:all"))
    }

    @Test fun parsesSsdpResponse() {
        val raw = "HTTP/1.1 200 OK\r\n" +
            "CACHE-CONTROL: max-age=1800\r\n" +
            "EXT:\r\n" +
            "LOCATION: http://192.168.1.20:9197/dmr\r\n" +
            "SERVER: Linux/4.4 UPnP/1.0 Samsung UPnP SDK/1.0\r\n" +
            "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
            "USN: uuid:abcd-1234::urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
            "\r\n"
        val ad = parseSsdpAdvertisement(raw)!!
        assertEquals("urn:schemas-upnp-org:device:MediaRenderer:1", ad.type)
        assertEquals("http://192.168.1.20:9197/dmr", ad.location)
        assertEquals("uuid:abcd-1234::urn:schemas-upnp-org:device:MediaRenderer:1", ad.usn)
        assertTrue(ad.server!!.contains("Samsung"))
        assertFalse(ad.isNotify)
    }

    @Test fun parsesSsdpNotifyAndRejectsJunk() {
        val notify = parseSsdpAdvertisement(
            "NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nNT: upnp:rootdevice\r\n" +
                "USN: uuid:aa::upnp:rootdevice\r\nNTS: ssdp:alive\r\n\r\n",
        )!!
        assertTrue(notify.isNotify)
        assertEquals("upnp:rootdevice", notify.type)
        assertNull(parseSsdpAdvertisement("GET / HTTP/1.1\r\nHost: x\r\n\r\n"))
        assertNull(parseSsdpAdvertisement(""))
        // 没有 ST/NT 的响应无法归类。
        assertNull(parseSsdpAdvertisement("HTTP/1.1 200 OK\r\nLOCATION: http://x/y\r\n\r\n"))
    }

    /* -------------------------------- UPnP XML -------------------------------- */

    private val descriptionXml = """
        <?xml version="1.0"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0">
          <specVersion><major>1</major><minor>0</minor></specVersion>
          <device>
            <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
            <friendlyName>Living Room TV &amp; Soundbar</friendlyName>
            <manufacturer>Samsung Electronics</manufacturer>
            <manufacturerURL>http://www.samsung.com/</manufacturerURL>
            <modelName>UE55TU8000</modelName>
            <modelNumber>UE55</modelNumber>
            <serialNumber>SN-42</serialNumber>
            <UDN>uuid:abcd-1234</UDN>
            <iconList>
              <icon><mimetype>image/png</mimetype><width>48</width><url>/icon48.png</url></icon>
              <icon><mimetype>image/png</mimetype><url>/icon120.png</url></icon>
            </iconList>
            <serviceList>
              <service>
                <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                <serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>
                <SCPDURL>/AVTransport.xml</SCPDURL>
                <controlURL>/upnp/control/AVTransport1</controlURL>
                <eventSubURL>/upnp/event/AVTransport1</eventSubURL>
              </service>
            </serviceList>
            <deviceList>
              <device>
                <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
                <friendlyName>Embedded</friendlyName>
                <UDN>uuid:embedded</UDN>
              </device>
            </deviceList>
            <presentationURL>/main.html</presentationURL>
          </device>
        </root>
    """.trimIndent()

    @Test fun parsesUpnpDeviceDescription() {
        val device = parseUpnpDeviceDescription(descriptionXml, "http://192.168.1.20:9197/dmr")!!
        assertEquals("Living Room TV & Soundbar", device.friendlyName)
        assertEquals("urn:schemas-upnp-org:device:MediaRenderer:1", device.deviceType)
        assertEquals("Samsung Electronics", device.manufacturer)
        assertEquals("UE55TU8000", device.modelName)
        assertEquals("SN-42", device.serialNumber)
        assertEquals("uuid:abcd-1234", device.udn)
        assertEquals(1, device.embeddedDeviceCount)
        assertEquals(1, device.services.size)
        val service = device.services.single()
        assertEquals("urn:schemas-upnp-org:service:AVTransport:1", service.serviceType)
        assertEquals("http://192.168.1.20:9197/upnp/control/AVTransport1", service.controlUrl)
        assertEquals("http://192.168.1.20:9197/AVTransport.xml", service.scpdUrl)
        assertEquals(listOf("http://192.168.1.20:9197/icon48.png", "http://192.168.1.20:9197/icon120.png"), device.iconUrls)
        assertEquals("http://192.168.1.20:9197/main.html", device.presentationUrl)
    }

    @Test fun rejectsNonDescriptionDocument() {
        assertNull(parseUpnpDeviceDescription("<html><body>hello</body></html>"))
        assertNull(parseUpnpDeviceDescription(""))
    }

    @Test fun parsesNamespacedTagsAndEntities() {
        val xml = "<root><dlna:device><friendlyName>R&amp;D &#65; &#x42;</friendlyName>" +
            "<deviceType>urn:schemas-upnp-org:device:Basic:1</deviceType></dlna:device></root>"
        val device = parseUpnpDeviceDescription(xml)!!
        assertEquals("R&D A B", device.friendlyName)
        assertEquals("urn:schemas-upnp-org:device:Basic:1", device.deviceType)
    }

    @Test fun resolvesRelativeUrlsWithoutBase() {
        assertEquals("http://x/y/d.xml", resolveUrl("http://x/y/d.xml", "http://x/y/d.xml"))
        assertEquals("/a/b", resolveUrl(null, "/a/b"))
        assertNull(resolveUrl("http://x/", "  "))
    }

    /* ---------------------------------- mDNS ---------------------------------- */

    private sealed class RData {
        data class Ptr(val target: String) : RData()
        data class Srv(val port: Int, val target: String) : RData()
        data class Txt(val entries: List<String>) : RData()
        data class A(val ip: String) : RData()
    }

    private class DnsWriter {
        private val out = ByteArrayOutputStream()

        fun u8(value: Int) {
            out.write(value and 0xFF)
        }

        fun u16(value: Int) {
            u8(value shr 8)
            u8(value)
        }

        fun u32(value: Long) {
            u16((value shr 16).toInt())
            u16(value.toInt())
        }

        fun name(value: String) {
            value.trimEnd('.').split('.').forEach { label ->
                val bytes = label.toByteArray(Charsets.UTF_8)
                if (bytes.isNotEmpty()) {
                    u8(bytes.size)
                    out.write(bytes)
                }
            }
            u8(0)
        }

        fun raw(bytes: ByteArray) {
            out.write(bytes)
        }

        fun bytes(): ByteArray = out.toByteArray()
    }

    private fun rdataBytes(rdata: RData): ByteArray {
        val writer = DnsWriter()
        when (rdata) {
            is RData.Ptr -> writer.name(rdata.target)
            is RData.Srv -> {
                writer.u16(0)
                writer.u16(0)
                writer.u16(rdata.port)
                writer.name(rdata.target)
            }
            is RData.Txt -> rdata.entries.forEach { entry ->
                val bytes = entry.toByteArray(Charsets.UTF_8)
                writer.u8(bytes.size)
                writer.raw(bytes)
            }
            is RData.A -> rdata.ip.split('.').forEach { writer.u8(it.toInt()) }
        }
        return writer.bytes()
    }

    private fun dnsResponse(vararg records: Triple<String, Int, RData>): ByteArray {
        val writer = DnsWriter()
        writer.u16(0)
        writer.u16(0x8400) // response + authoritative
        writer.u16(0)
        writer.u16(records.size)
        writer.u16(0)
        writer.u16(0)
        records.forEach { (name, type, rdata) ->
            writer.name(name)
            writer.u16(type)
            writer.u16(1)
            writer.u32(120)
            val data = rdataBytes(rdata)
            writer.u16(data.size)
            writer.raw(data)
        }
        return writer.bytes()
    }

    @Test fun buildsQueryWithAllQuestions() {
        val packet = parseDnsPacket(buildMdnsQuery(listOf(MDNS_SERVICE_ENUM, "_airplay._tcp")))!!
        assertFalse(packet.isResponse)
        assertEquals(
            listOf("_services._dns-sd._udp.local", "_airplay._tcp.local"),
            packet.questions.map { it.name },
        )
        assertTrue(packet.questions.all { it.type == DNS_TYPE_PTR && it.clazz == DNS_CLASS_IN })
    }

    @Test fun queryNormalisesAndDeduplicatesTypes() {
        val packet = parseDnsPacket(buildMdnsQuery(listOf("_ipp._tcp", "_IPP._tcp.local.", " ")))!!
        assertEquals(listOf("_ipp._tcp.local"), packet.questions.map { it.name })
    }

    @Test fun parsesCompressedNames() {
        // 答案 name 用常规标签，rdata 里的 PTR target 用指针回指 name 起始偏移 12。
        val writer = DnsWriter()
        writer.u16(0)
        writer.u16(0x8400)
        writer.u16(0)
        writer.u16(1)
        writer.u16(0)
        writer.u16(0)
        writer.name("_airplay._tcp.local")
        writer.u16(DNS_TYPE_PTR)
        writer.u16(1)
        writer.u32(120)
        val instanceLabelBytes = byteArrayOf(2, 'T'.code.toByte(), 'V'.code.toByte(), 0xC0.toByte(), 0x0C)
        writer.u16(instanceLabelBytes.size)
        writer.raw(instanceLabelBytes)

        val packet = parseDnsPacket(writer.bytes())!!
        assertEquals("_airplay._tcp.local", packet.records.single().name)
        assertEquals("TV._airplay._tcp.local", (packet.records.single().data as DnsRData.Ptr).target)
    }

    @Test fun assemblerResolvesDeviceFromPointersAndRecords() {
        val response = dnsResponse(
            Triple(MDNS_SERVICE_ENUM, DNS_TYPE_PTR, RData.Ptr("_airplay._tcp.local")),
            Triple("_airplay._tcp.local", DNS_TYPE_PTR, RData.Ptr("Living Room TV._airplay._tcp.local")),
            Triple("Living Room TV._airplay._tcp.local", DNS_TYPE_SRV, RData.Srv(7000, "tv-1.local")),
            Triple(
                "Living Room TV._airplay._tcp.local",
                DNS_TYPE_TXT,
                RData.Txt(listOf("fn=Living Room TV", "md=Apple TV 4K", "deviceid=AA:BB")),
            ),
            Triple("tv-1.local", DNS_TYPE_A, RData.A("192.168.1.20")),
        )
        val assembler = MdnsAssembler()
        assembler.add(parseDnsPacket(response)!!)

        val device = assembler.devices().single()
        assertEquals("Living Room TV", device.name)
        assertEquals("tv", device.category)
        assertEquals(7000, device.port)
        assertEquals("tv-1.local", device.host)
        assertEquals(listOf("192.168.1.20"), device.addresses)
        assertEquals(listOf("_airplay._tcp"), device.serviceTypes)
        assertEquals("Apple TV 4K", device.txt["md"])
        assertEquals(listOf("_airplay._tcp"), assembler.serviceTypes())
    }

    @Test fun assemblerIgnoresQueriesAndKeepsUnresolvedDevices() {
        val assembler = MdnsAssembler()
        assembler.add(parseDnsPacket(buildMdnsQuery(listOf("_ipp._tcp")))!!)
        assertTrue(assembler.devices().isEmpty())

        assembler.add(
            parseDnsPacket(
                dnsResponse(
                    Triple("_ipp._tcp.local", DNS_TYPE_PTR, RData.Ptr("Office Printer._ipp._tcp.local")),
                    Triple("Office Printer._ipp._tcp.local", DNS_TYPE_SRV, RData.Srv(631, "printer.local")),
                ),
            )!!,
        )
        val device = assembler.devices().single()
        assertEquals("Office Printer", device.name)
        assertEquals("printer", device.category)
        assertEquals(631, device.port)
        assertTrue(device.addresses.isEmpty())
    }

    @Test fun rejectsMalformedDnsPayloads() {
        assertNull(parseDnsPacket(ByteArray(4)))
        assertNull(parseDnsPacket(ByteArray(0)))
        // 头部声称 3 条答案，实际只有 1 条半 → 解析失败返回 null，不抛异常。
        val truncated = dnsResponse(
            Triple("_airplay._tcp.local", DNS_TYPE_PTR, RData.Ptr("X._airplay._tcp.local")),
        ).copyOf(20)
        assertNull(parseDnsPacket(truncated))
    }

    /* ------------------------------- classification ---------------------------- */

    @Test fun classifiesMdnsServices() {
        assertEquals("tv", mdnsCategory(listOf("_airplay._tcp"), emptyMap()))
        assertEquals("tv", mdnsCategory(listOf("_airplay._tcp"), mapOf("model" to "AppleTV6,2")))
        assertEquals("speaker", mdnsCategory(listOf("_airplay._tcp"), mapOf("model" to "AudioAccessory5,1")))
        assertEquals("speaker", mdnsCategory(listOf("_raop._tcp"), emptyMap()))
        assertEquals("speaker", mdnsCategory(listOf("_spotify-connect._tcp"), emptyMap()))
        assertEquals("printer", mdnsCategory(listOf("_ipp._tcp"), emptyMap()))
        assertEquals("printer", mdnsCategory(listOf("_pdl-datastream._tcp"), emptyMap()))
        assertEquals("home_assistant", mdnsCategory(listOf("_home-assistant._tcp"), emptyMap()))
        assertEquals("tv", mdnsCategory(listOf("_googlecast._tcp"), mapOf("md" to "Chromecast Ultra")))
        assertEquals("speaker", mdnsCategory(listOf("_googlecast._tcp"), mapOf("md" to "Google Home Mini")))
        assertEquals("cast", mdnsCategory(listOf("_googlecast._tcp"), mapOf("md" to "Group")))
        assertEquals("iot", mdnsCategory(listOf("_esphomelib._tcp"), emptyMap()))
        assertEquals("nas", mdnsCategory(listOf("_smb._tcp"), emptyMap()))
        assertEquals("computer", mdnsCategory(listOf("_ssh._tcp"), emptyMap()))
        assertEquals("andagent", mdnsCategory(listOf("_andagent._tcp"), emptyMap()))
        assertEquals("unknown", mdnsCategory(listOf("_weird._tcp"), emptyMap()))
    }

    @Test fun classifiesUpnpDevices() {
        assertEquals(
            "tv",
            ssdpCategory(
                listOf("urn:schemas-upnp-org:device:MediaRenderer:1"),
                "Linux/4.4 UPnP/1.0 Samsung UPnP SDK/1.0",
                "Samsung",
                "UE55",
                "http://192.168.1.20:9197/dmr",
            ),
        )
        assertEquals(
            "speaker",
            ssdpCategory(
                listOf("urn:schemas-upnp-org:device:MediaRenderer:1"),
                "Linux UPnP/1.0 Sonos",
                "Sonos",
                "One",
                "http://192.168.1.30:1400/xml/device_description.xml",
            ),
        )
        assertEquals(
            "printer",
            ssdpCategory(listOf("urn:schemas-upnp-org:device:Printer:1"), null, null, null, null),
        )
        assertEquals(
            "router",
            ssdpCategory(listOf("urn:schemas-upnp-org:device:InternetGatewayDevice:1"), null, null, null, null),
        )
        assertEquals(
            "home_assistant",
            ssdpCategory(listOf("upnp:rootdevice"), null, null, null, "http://192.168.1.5:8123/"),
        )
        assertEquals(
            "nas",
            ssdpCategory(listOf("urn:schemas-upnp-org:device:MediaServer:1"), "Synology", "Synology", "DS220", null),
        )
        assertEquals("unknown", ssdpCategory(listOf("upnp:rootdevice"), null, null, null, null))
    }

    @Test fun categoryRankPrefersSpecificCategories() {
        assertTrue(categoryRank("tv") < categoryRank("cast"))
        assertTrue(categoryRank("home_assistant") < categoryRank("tv"))
        assertTrue(categoryRank("unknown") > categoryRank("server"))
    }

    /* ---------------------------------- helpers -------------------------------- */

    @Test fun normalisesServiceTypes() {
        assertEquals("_ipp._tcp.local", normalizeServiceType(" _IPP._TCP. "))
        assertEquals("_ipp._tcp", shortServiceType("_ipp._tcp.local"))
        assertEquals(emptyList<String>(), splitServiceTypes("  "))
        assertEquals(listOf("_http._tcp", "_ipp._tcp"), splitServiceTypes("_http._tcp, _ipp._tcp"))
    }

    @Test fun descriptionCandidatesTryCommonPaths() {
        assertEquals(
            listOf(
                "http://192.168.1.20:49152/",
                "http://192.168.1.20:49152/description.xml",
                "http://192.168.1.20:49152/rootDesc.xml",
                "http://192.168.1.20:49152/device.xml",
                "http://192.168.1.20:49152/upnp/description.xml",
            ),
            descriptionCandidates("http://192.168.1.20:49152/"),
        )
        assertEquals(
            listOf("http://192.168.1.20:49152/dmr.xml"),
            descriptionCandidates("http://192.168.1.20:49152/dmr.xml"),
        )
    }

    @Test fun extractsInstanceLabelsAndTxtEntries() {
        assertEquals("Living Room TV", instanceLabel("Living Room TV._airplay._tcp.local"))
        assertEquals("weird", instanceLabel("weird"))
        assertEquals(mapOf("fn" to "TV", "md" to "Apple TV"), parseTxtEntries(listOf("fn=TV", "md=Apple TV")))
        assertEquals(mapOf("flag" to ""), parseTxtEntries(listOf("flag")))
        assertTrue(parseTxtEntries(listOf("=novalue")).isEmpty())
    }
}
