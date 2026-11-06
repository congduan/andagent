package com.dc.andagent.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets

class HttpToolsTest {

    /* ------------------------------- cidr parsing ------------------------------ */

    @Test fun cidr24SkipsNetworkAndBroadcast() {
        val range = parseCidr("192.168.1.0/24")
        assertEquals("192.168.1.0/24", range.label)
        assertEquals(254, range.hosts.size)
        assertEquals("192.168.1.1", range.hosts.first())
        assertEquals("192.168.1.254", range.hosts.last())
    }

    @Test fun cidr28CoversUsableHosts() {
        val range = parseCidr("10.0.0.16/28")
        assertEquals("10.0.0.16/28", range.label)
        assertEquals(14, range.hosts.size)
        assertEquals("10.0.0.17", range.hosts.first())
        assertEquals("10.0.0.30", range.hosts.last())
    }

    @Test fun cidr32AndBareIpAreSingleHosts() {
        assertEquals(listOf("192.168.1.10"), parseCidr("192.168.1.10/32").hosts)
        assertEquals(listOf("192.168.1.10"), parseCidr("192.168.1.10").hosts)
        assertEquals("192.168.1.10/32", parseCidr(" 192.168.1.10 ").label)
    }

    @Test fun cidrNormalisesMisalignedAddress() {
        assertEquals("192.168.1.0/24", parseCidr("192.168.1.77/24").label)
        assertEquals("10.0.0.16/28", parseCidr("10.0.0.21/28").label)
    }

    @Test fun cidrRejectsWideRangesAndJunk() {
        for (bad in listOf(
            "10.0.0.0/16", "0.0.0.0/0", "10.0.0.0/8", "10.0.0.0/30", "192.168.1.0/",
            "192.168.1.256/24", "192.168.1/24", "not-a-cidr", "192.168.1.0/-1", "fe80::1/64",
        )) {
            try {
                parseCidr(bad)
                fail("expected rejection for $bad")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.isNotBlank())
            }
        }
    }

    /* ---------------------------- argument validation --------------------------- */

    @Test fun methodWhitelistIsCaseInsensitive() {
        assertEquals("GET", httpMethod(null))
        assertEquals("POST", httpMethod("post"))
        assertEquals("PATCH", httpMethod(" Patch "))
        for (bad in listOf("TRACE", "CONNECT", "OPTIONS", "get post")) {
            try {
                httpMethod(bad)
                fail("expected rejection for $bad")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.contains("method"))
            }
        }
    }

    @Test fun urlAcceptsPlainHttpForLocalDevices() {
        requireHttpUrl("http://192.168.1.10:8123/api/")
        requireHttpUrl("https://example.com/path?q=1")
        for (bad in listOf("ftp://example.com", "file:///etc/passwd", "192.168.1.1", "http://", "javascript:alert(1)")) {
            try {
                requireHttpUrl(bad)
                fail("expected rejection for $bad")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.isNotBlank())
            }
        }
    }

    @Test fun headerJsonParsing() {
        assertTrue(parseHeadersJson(null).isEmpty())
        assertTrue(parseHeadersJson("").isEmpty())
        val headers = parseHeadersJson("""{"Authorization":"Bearer t","X-Lan":7,"nullish":null}""")
        assertEquals("Bearer t", headers["Authorization"])
        assertEquals("7", headers["X-Lan"])
        assertFalse(headers.containsKey("nullish"))
        for (bad in listOf("""["a"]""", """{"a":""", "not json")) {
            try {
                parseHeadersJson(bad)
                fail("expected rejection for $bad")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.contains("headers_json"))
            }
        }
    }

    @Test fun keyHeadersDropsNoiseButKeepsCustom() {
        val filtered = keyHeaders(
            mapOf(
                "Content-Type" to listOf("application/json"),
                "Set-Cookie" to listOf("secret=1"),
                "X-Hass" to listOf("ok"),
                "Content-Length" to listOf("12"),
            ),
        )
        assertEquals(listOf("Content-Length", "Content-Type", "X-Hass"), filtered.map { it.first })
        assertEquals("application/json", filtered.first { it.first == "Content-Type" }.second)
    }

    @Test fun safeTakeDoesNotSplitSurrogatePair() {
        assertEquals("abc" to false, safeTake("abc", 10))
        val emoji = "a\uD83D\uDE00" // 'a' + one astral char (2 UTF-16 units)
        val (cut, truncated) = safeTake(emoji, 2)
        assertEquals("a", cut)
        assertTrue(truncated)
    }

    /* ------------------------------- http_request ------------------------------ */

    @Test fun getReturnsStructuredResult() = TinyHttpServer { req ->
        when (req.path) {
            "/hello" -> RawResponse(200, mapOf("Content-Type" to "application/json", "X-Device" to "esphome"), """{"state":"on"}""")
            else -> RawResponse(404, emptyMap(), "missing")
        }
    }.use { server ->
        val result = obj(call("http_request", """{"url":"${server.base()}/hello"}"""))
        assertEquals(200, result["status"]!!.jsonPrimitive.int)
        assertTrue(result["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("GET", result["method"]!!.jsonPrimitive.content)
        assertEquals("""{"state":"on"}""", result["body"]!!.jsonPrimitive.content)
        assertFalse(result["truncated"]!!.jsonPrimitive.content.toBoolean())
        // OkHttp reports parsed header names in lowercase.
        assertEquals(result.toString(), "esphome", result["headers"]!!.jsonObject["x-device"]?.jsonPrimitive?.content)
        assertTrue(result["elapsedMs"]!!.jsonPrimitive.int >= 0)
        assertTrue(result["url"]!!.jsonPrimitive.content.endsWith("/hello"))
    }

    @Test fun postSendsCustomHeadersAndBody() = TinyHttpServer { req ->
        RawResponse(
            201,
            mapOf("Content-Type" to "application/json"),
            """{"method":"${req.method}","token":"${req.headers["x-token"]}","body":${req.body}}""",
        )
    }.use { server ->
        val result = obj(
            call(
                "http_request",
                """{"url":"${server.base()}/echo","method":"post","headers_json":"{\"X-Token\":\"abc123\"}","body":"{\"on\":true}"}""",
            ),
        )
        assertEquals(201, result["status"]!!.jsonPrimitive.int)
        val body = result["body"]!!.jsonPrimitive.content
        assertTrue(body, body.contains("\"method\":\"POST\""))
        assertTrue(body, body.contains("\"token\":\"abc123\""))
        assertTrue(body, body.contains("\"on\":true"))
    }

    @Test fun non2xxReturnsStatusAndCappedBody() = TinyHttpServer {
        RawResponse(500, emptyMap(), "x".repeat(3_000))
    }.use { server ->
        val result = obj(call("http_request", """{"url":"${server.base()}/boom"}"""))
        assertEquals(500, result["status"]!!.jsonPrimitive.int)
        assertFalse(result["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(2_000, result["body"]!!.jsonPrimitive.content.length)
        assertTrue(result["truncated"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(3_000, result["bodyChars"]!!.jsonPrimitive.int)
    }

    @Test fun maxCharsTruncatesSuccessBody() = TinyHttpServer {
        RawResponse(200, emptyMap(), "y".repeat(5_000))
    }.use { server ->
        val result = obj(call("http_request", """{"url":"${server.base()}/long","max_chars":100}"""))
        assertEquals(100, result["body"]!!.jsonPrimitive.content.length)
        assertEquals(5_000, result["bodyChars"]!!.jsonPrimitive.int)
        assertTrue(result["truncated"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test fun headHasEmptyBody() = TinyHttpServer {
        RawResponse(200, mapOf("X-Probe" to "1"), "ignored")
    }.use { server ->
        val result = obj(call("http_request", """{"url":"${server.base()}/head","method":"HEAD"}"""))
        assertEquals(200, result["status"]!!.jsonPrimitive.int)
        assertEquals("", result["body"]!!.jsonPrimitive.content)
    }

    @Test fun connectionFailureIsErrorString() {
        val closedPort = ServerSocket(0).use { it.localPort }
        val text = call("http_request", """{"url":"http://127.0.0.1:$closedPort/x","timeout_ms":1000}""")
        assertTrue(text, text.startsWith("ERROR:"))
    }

    @Test fun rejectsBodyOnGet() {
        val text = call("http_request", """{"url":"http://127.0.0.1:1/x","body":"nope"}""")
        assertTrue(text, text.startsWith("ERROR:") && text.contains("body"))
    }

    /* --------------------------------- lan_scan -------------------------------- */

    @Test fun portScanFindsLocalListener() = TinyHttpServer {
        RawResponse(200, emptyMap(), "ok")
    }.use { server ->
        val result = obj(
            call(
                "lan_scan",
                """{"mode":"port","cidr":"127.0.0.1","port":${server.port},"timeout_ms":500}""",
            ),
        )
        assertEquals("port", result["mode"]!!.jsonPrimitive.content)
        assertEquals("127.0.0.1/32", result["cidr"]!!.jsonPrimitive.content)
        assertEquals(1, result["openCount"]!!.jsonPrimitive.int)
        val open = result["open"]!!.jsonArray.single().jsonObject
        assertEquals("127.0.0.1", open["ip"]!!.jsonPrimitive.content)
        assertEquals(server.port, open["port"]!!.jsonPrimitive.int)
    }

    @Test fun portScanSkipsClosedPort() = TinyHttpServer {
        RawResponse(200, emptyMap(), "ok")
    }.use {
        val closed = ServerSocket(0).use { it.localPort }
        val result = obj(
            call(
                "lan_scan",
                """{"mode":"port","cidr":"127.0.0.1","port":$closed,"timeout_ms":300}""",
            ),
        )
        assertEquals(0, result["openCount"]!!.jsonPrimitive.int)
        assertTrue(result["open"]!!.jsonArray.isEmpty())
    }

    @Test fun lanScanValidatesArguments() {
        assertTrue(call("lan_scan", """{"mode":"port","cidr":"127.0.0.1"}""").contains("port"))
        assertTrue(call("lan_scan", """{"mode":"nope","cidr":"127.0.0.1"}""").startsWith("ERROR:"))
        assertTrue(call("lan_scan", """{"mode":"port","cidr":"10.0.0.0/8","port":80}""").startsWith("ERROR:"))
        assertTrue(call("lan_scan", """{"mode":"port","cidr":"127.0.0.1","port":70000}""").contains("1~65535"))
    }

    @Test fun lanScanNeedsWifiForDefaultCidr() {
        // No Context in unit tests: the default /24 cannot be derived.
        val text = call("lan_scan", """{"mode":"ping"}""")
        assertTrue(text, text.startsWith("ERROR:") && text.contains("Wi-Fi"))
    }

    /* --------------------------------- helpers -------------------------------- */

    private fun call(name: String, json: String): String =
        runBlocking { HttpTool(name).execute(Json.parseToJsonElement(json) as JsonObject) }

    private fun obj(text: String): JsonObject {
        assertFalse("expected JSON but got: $text", text.startsWith("ERROR"))
        val parsed = Json.parseToJsonElement(text) as? JsonObject
        assertNotNull("expected JSON object but got: $text", parsed)
        return parsed!!
    }

    private data class RawRequest(val method: String, val path: String, val headers: Map<String, String>, val body: String)
    private data class RawResponse(val status: Int, val headers: Map<String, String>, val body: String)

    /**
     * Minimal one-request-per-connection HTTP/1.1 server. JDK's com.sun.net.httpserver is
     * unavailable on this module's compile classpath, and OkHttp needs a real socket.
     */
    private class TinyHttpServer(private val handler: (RawRequest) -> RawResponse) : AutoCloseable {
        private val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = socket.localPort
        private val thread = Thread {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: return@Thread
                runCatching { serve(client) }
            }
        }.apply { isDaemon = true; start() }

        fun base(): String = "http://127.0.0.1:$port"

        override fun close() {
            runCatching { socket.close() }
            thread.interrupt()
        }

        private fun serve(client: Socket) = client.use { s ->
            val input = s.getInputStream()
            val requestLine = readLine(input) ?: return@use
            val parts = requestLine.split(' ')
            if (parts.size < 2) return@use
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            val body = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val n = input.read(body, offset, length - offset)
                if (n < 0) break
                offset += n
            }
            val response = handler(RawRequest(parts[0], parts[1], headers, String(body, 0, offset, StandardCharsets.UTF_8)))
            val payload = response.body.toByteArray(StandardCharsets.UTF_8)
            val head = buildString {
                append("HTTP/1.1 ${response.status} ${statusText(response.status)}\r\n")
                response.headers.forEach { (name, value) -> append("$name: $value\r\n") }
                append("Content-Length: ${payload.size}\r\n")
                append("Connection: close\r\n\r\n")
            }
            val out = s.getOutputStream()
            out.write(head.toByteArray(StandardCharsets.UTF_8))
            if (parts[0] != "HEAD") out.write(payload)
            out.flush()
        }

        private fun readLine(input: InputStream): String? {
            val text = StringBuilder()
            while (true) {
                val byte = input.read()
                if (byte < 0) return if (text.isEmpty()) null else text.toString()
                if (byte == '\n'.code) break
                if (byte != '\r'.code) text.append(byte.toChar())
            }
            return text.toString()
        }

        private fun statusText(status: Int): String = when (status) {
            200 -> "OK"
            201 -> "Created"
            404 -> "Not Found"
            500 -> "Internal Server Error"
            else -> "Status"
        }
    }
}
