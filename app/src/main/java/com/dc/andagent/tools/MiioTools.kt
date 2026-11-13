package com.dc.andagent.tools

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * R79（Roadmap，R78 MQTT 前置已收口）— miIO LAN 协议 PoC 三工具，DatagramSocket / UDP 54321。
 *
 * - miio_discover : 只读，Wi-Fi /24 逐 IP + 受限广播发 HELLO，线程池并发 ≤64，收 4s，去重上限 64，全程 ≤8s。
 * - miio_send     : destructive=true，token 加密下发命令 JSON（控制前须用户确认）。
 * - miio_info     : 只读，发 miIO.info 并原样透传 result。
 *
 * 所有网络 IO 都在 Dispatchers.IO；token 随调用参数传入、绝不写入 KvStore / 文件 / 日志，
 * 输出只出现 [MiioCodec.maskToken] 脱敏值；三工具 try-catch 全包裹，失败一律结构化 ok=false+reason，
 * never-crash。真机发现率/控制成功率取决于设备固件与 token 正确性，工具描述如实标注为协议 PoC，
 * 不承诺全设备支持。协议纯函数见 [MiioCodec]。
 */
val MIIO_TOOLS = listOf("miio_discover", "miio_send", "miio_info")

class MiioTool(
    private val context: Context? = null,
    override val name: String,
) : Tool {
    override val destructive: Boolean get() = name == "miio_send"

    override val description = when (name) {
        "miio_discover" ->
            """米家（miIO）局域网设备发现（只读）：自动取当前 Wi-Fi 的 IPv4 /24 网段，向每个 IP 与 255.255.255.255 受限广播发送 miIO HELLO 握手包（UDP 54321），线程池并发 ≤64，收集约 4 秒并按 IP 去重（上限 64 台），全程 ≤8 秒；返回每台 ip/port/stamp/deviceId 与 token 未知提示。真机发现率取决于设备是否在同一网段且响应 HELLO，本工具为协议 PoC，不承诺全设备支持：token 需从米家 App / 云端另行获取，发现结果不含 token。"""
        "miio_send" ->
            """米家（miIO）局域网设备控制（destructive，执行前须用户确认）：向指定 ip 的设备以 token 加密发送一条 miIO 命令 JSON（如 {"id":1,"method":"set_power","params":[true]}），先握手取设备 stamp 再发命令。参数 ip（支持 ip 或 ip:port，缺省端口 54321）、token（32 位 hex 或 24 位 base64，16 字节，绝不回显明文或落盘）、command（命令 JSON 字符串）；可选 timeout_ms（默认 2000，范围 1000~5000）、retries（默认 1，范围 0~2）。输出仅含脱敏 token 与结构化结果；失败返回 ok=false+reason，从不 crash。真机控制成功率取决于设备固件与 token 正确性，本工具为协议 PoC，不承诺全设备支持。"""
        else ->
            """米家（miIO）设备信息（只读）：向指定 ip 的设备以 token 加密发送 {"id":1,"method":"miIO.info","params":[]} 并透传设备返回的 result 原样（model/ip 等字段以设备实际返回为准，不做白名单深挖）。参数 ip（支持 ip 或 ip:port，缺省端口 54321）、token（32 位 hex 或 24 位 base64，16 字节，绝不回显明文或落盘）；可选 timeout_ms（默认 2000，范围 1000~5000）、retries（默认 1，范围 0~2）。失败返回 ok=false+reason，从不 crash。真机结果取决于设备固件与 token 正确性，本工具为协议 PoC，不承诺全设备支持。"""
    }

    override val parameters = when (name) {
        "miio_discover" -> schemaOf(required = emptyList())
        "miio_send" -> schemaOf(
            "ip" to strParam("设备 IP 或 ip:port，缺省端口 54321，如 192.168.1.23"),
            "token" to strParam("设备 token：32 位 hex 或 24 位 base64（16 字节），随调用传入，绝不回显明文或落盘"),
            "command" to strParam("""miIO 命令 JSON 字符串，如 {"id":1,"method":"set_power","params":[true]}"""),
            "timeout_ms" to intParam("单次收包超时毫秒，默认 2000，范围 1000~5000"),
            "retries" to intParam("失败重试次数，默认 1，范围 0~2"),
            required = listOf("ip", "token", "command"),
        )
        else -> schemaOf(
            "ip" to strParam("设备 IP 或 ip:port，缺省端口 54321，如 192.168.1.23"),
            "token" to strParam("设备 token：32 位 hex 或 24 位 base64（16 字节），随调用传入，绝不回显明文或落盘"),
            "timeout_ms" to intParam("单次收包超时毫秒，默认 2000，范围 1000~5000"),
            "retries" to intParam("失败重试次数，默认 1，范围 0~2"),
            required = listOf("ip", "token"),
        )
    }

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        try {
            when (name) {
                "miio_discover" -> executeDiscover()
                "miio_send" -> executeCommand(args, isInfo = false)
                else -> executeCommand(args, isInfo = true)
            }
        } catch (t: Throwable) {
            // 最外层兜底：任何未预料异常都不 crash，返回结构化错误。
            buildJsonObject {
                put("ok", false)
                put("error", "internal_error")
                put("reason", redact(t.message?.take(160).orEmpty().ifBlank { t.javaClass.simpleName }, null))
            }.toString()
        }
    }

    /* -------------------------------- miio_send / miio_info -------------------------------- */

    private fun executeCommand(args: JsonObject, isInfo: Boolean): String {
        val ipRaw = args.strArg("ip")
        val tokenRaw = args.strArg("token")
        val commandRaw = if (isInfo) null else args.commandArg("command")
        return try {
            val (host, port) = parseTarget(ipRaw ?: throw IllegalArgumentException("缺少 ip"))
            val tokenStr = tokenRaw ?: throw IllegalArgumentException("缺少 token")
            val token = MiioCodec.normalizeToken(tokenStr)
            val timeoutMs = requireTimeout(args.intArg("timeout_ms"))
            val retries = requireRetries(args.intArg("retries"))
            val commandJson = if (isInfo) {
                """{"id":1,"method":"miIO.info","params":[]}"""
            } else {
                val c = commandRaw ?: throw IllegalArgumentException("缺少 command")
                require(Json.parseToJsonElement(c) is JsonObject) { "command 必须是 JSON 对象字符串" }
                c
            }

            val started = System.nanoTime()
            var lastError = "unknown"
            var lastReason = "未知错误"
            // 总尝试次数 = 1 + retries；每次失败（含异常）都按结构化原因记录。
            for (attempt in 0..retries) {
                val result: ExchangeResult = try {
                    exchange(host, port, token, commandJson, timeoutMs)
                } catch (t: Throwable) {
                    val (e, why) = classify(t)
                    ExchangeResult.Failure(e, why)
                }
                when (result) {
                    is ExchangeResult.Success -> return successJson(
                        ipRaw, tokenRaw, result, retries, attempt, started,
                    )
                    is ExchangeResult.Failure -> {
                        lastError = result.error
                        lastReason = result.reason
                    }
                }
            }
            errorJson(ipRaw, tokenRaw, lastError, lastReason)
        } catch (t: Throwable) {
            val (kind, why) = classify(t)
            errorJson(ipRaw, tokenRaw, kind, why)
        }
    }

    /** HELLO 握手取 stamp/did → 加密命令 → 收包解密 → parseCommandResult。 */
    private fun exchange(
        host: String,
        port: Int,
        token: ByteArray,
        commandJson: String,
        timeoutMs: Int,
    ): ExchangeResult {
        val sock = DatagramSocket()
        try {
            sock.soTimeout = timeoutMs
            val addr = InetAddress.getByName(host)
            sock.connect(addr, port)

            // 1) 无 token 的 HELLO 握手，读回设备 stamp / device_id。
            val hello = MiioCodec.discoverRequest()
            sock.send(DatagramPacket(hello, hello.size))
            val buf = ByteArray(1500)
            val helloReply = DatagramPacket(buf, buf.size)
            sock.receive(helloReply)
            val helloResp = MiioCodec.parseResponse(buf.copyOfRange(0, helloReply.length), null)
                ?: return ExchangeResult.Failure("hello_invalid", "HELLO 响应不是合法 miIO 报文")

            // 2) 用握手得到的 stamp/did 构造加密命令并发送。
            val packet = MiioCodec.buildCommand(helloResp.deviceId, helloResp.stamp, token, commandJson)
            sock.send(DatagramPacket(packet, packet.size))
            val cmdReply = DatagramPacket(buf, buf.size)
            sock.receive(cmdReply)
            val cmdResp = MiioCodec.parseResponse(buf.copyOfRange(0, cmdReply.length), token)
                ?: return ExchangeResult.Failure("command_invalid", "命令响应不是合法 miIO 报文")
            if (!cmdResp.checksumValid) {
                return ExchangeResult.Failure(
                    "checksum_failed",
                    "checksum 校验失败：token 可能错误或报文被篡改",
                )
            }
            val plain = cmdResp.plainText
                ?: return ExchangeResult.Failure("decrypt_failed", "命令响应解密失败")
            return ExchangeResult.Success(
                MiioCodec.parseCommandResult(plain),
                cmdResp.deviceId,
                cmdResp.stamp,
            )
        } finally {
            sock.close()
        }
    }

    private sealed class ExchangeResult {
        data class Success(
            val result: MiioCodec.CommandResult,
            val deviceId: Long,
            val stamp: Long,
        ) : ExchangeResult()
        data class Failure(val error: String, val reason: String) : ExchangeResult()
    }

    private fun successJson(
        ip: String?,
        tokenRaw: String?,
        exchange: ExchangeResult.Success,
        retries: Int,
        attemptsUsed: Int,
        started: Long,
    ): String = buildJsonObject {
        val r = exchange.result
        put("ok", r.ok)
        if (ip != null) put("ip", redact(ip, tokenRaw))
        put("token", MiioCodec.maskToken(tokenRaw))
        put("device_id", exchange.deviceId)
        put("stamp", exchange.stamp)
        if (r.ok) {
            r.id?.let { put("id", it) }
            r.result?.let { put("result", it) }
        } else {
            put("error", "device_error")
            r.errorCode?.let { put("error_code", it) }
            // review 建议①：设备 error message 与失败路径同样过 redact（设备固件可能回显 token）。
            put("reason", redact(r.reason ?: "设备返回错误", tokenRaw))
        }
        put("retries", retries)
        put("attempts", attemptsUsed + 1)
        put("elapsed_ms", (System.nanoTime() - started) / 1_000_000)
    }.toString()

    /* ---------------------------------- miio_discover ---------------------------------- */

    private fun executeDiscover(): String {
        return try {
            val localIp = localWifiIpv4(context)
            if (localIp == null) {
                return buildJsonObject {
                    put("ok", false)
                    put("error", "no_wifi_ipv4")
                    put("reason", "无法获取本机 Wi-Fi IPv4（未连接 Wi-Fi 或无权限）；miio_discover 需在同一局域网内运行")
                    put("count", 0)
                    putJsonArray("devices") {}
                }.toString()
            }
            val prefix = localIp.substringBeforeLast('.') + "."
            val hardDeadline = System.currentTimeMillis() + 8000
            val receiveDeadline = System.currentTimeMillis() + 4000
            val started = System.currentTimeMillis()
            val socket = DatagramSocket()
            socket.broadcast = true
            val hello = MiioCodec.discoverRequest()

            // 发送阶段：线程池并发 ≤64，逐 IP + 一个 255.255.255.255 受限广播。
            val pool = Executors.newFixedThreadPool(64)
            try {
                for (last in 1..254) {
                    val host = prefix + last
                    pool.execute {
                        try {
                            val p = DatagramPacket(hello, hello.size, InetAddress.getByName(host), MiioCodec.DEFAULT_PORT)
                            synchronized(socket) { socket.send(p) }
                        } catch (t: Throwable) { /* 单 IP 发送失败忽略 */ }
                    }
                }
                pool.execute {
                    try {
                        val p = DatagramPacket(hello, hello.size, InetAddress.getByName("255.255.255.255"), MiioCodec.DEFAULT_PORT)
                        synchronized(socket) { socket.send(p) }
                    } catch (t: Throwable) { /* 广播失败忽略 */ }
                }
            } finally {
                pool.shutdown()
                pool.awaitTermination(2, TimeUnit.SECONDS)
            }

            // 接收阶段：按 IP 去重，上限 64 台，4 秒窗口且不越过 8 秒硬上限。
            val found = ConcurrentHashMap<String, Found>()
            val buf = ByteArray(1500)
            try {
                while (System.currentTimeMillis() < receiveDeadline && System.currentTimeMillis() < hardDeadline) {
                    socket.soTimeout = (minOf(receiveDeadline, hardDeadline) - System.currentTimeMillis())
                        .toInt().coerceIn(1, 4000)
                    try {
                        val reply = DatagramPacket(buf, buf.size)
                        socket.receive(reply)
                        val resp = MiioCodec.parseResponse(buf.copyOfRange(0, reply.length), null) ?: continue
                        val ip = reply.address?.hostAddress ?: continue
                        if (found.size < 64) {
                            found.putIfAbsent(ip, Found(ip, reply.port, resp.stamp, resp.deviceId))
                        }
                    } catch (t: Throwable) {
                        break // 超时到点或 socket 异常，结束接收
                    }
                }
            } finally {
                // review 建议②：所有路径（含接收循环外的意外异常）都关闭 socket，防 FD 泄漏。
                socket.close()
            }

            buildJsonObject {
                put("ok", true)
                put("subnet", prefix + "0/24")
                put("count", found.size)
                put("token_hint", "token 未知：需从米家 App / 云端获取；发现结果不含 token")
                putJsonArray("devices") {
                    found.values.sortedBy { it.ip }.forEach { d ->
                        add(buildJsonObject {
                            put("ip", d.ip)
                            put("port", d.port)
                            put("stamp", d.stamp)
                            put("device_id", d.deviceId)
                        })
                    }
                }
                put("elapsed_ms", System.currentTimeMillis() - started)
            }.toString()
        } catch (t: Throwable) {
            buildJsonObject {
                put("ok", false)
                put("error", "discover_failed")
                put("reason", t.message?.take(160).orEmpty().ifBlank { t.javaClass.simpleName })
                put("count", 0)
                putJsonArray("devices") {}
            }.toString()
        }
    }

    private data class Found(val ip: String, val port: Int, val stamp: Long, val deviceId: Long)

    private fun localWifiIpv4(ctx: Context?): String? {
        if (ctx == null) return null
        return try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return null
            val ip = wm.connectionInfo.ipAddress // 小端 int
            if (ip == 0) return null
            val bytes = ByteArray(4) { i -> (ip shr (8 * i)).toByte() }
            InetAddress.getByAddress(bytes).hostAddress
        } catch (t: Throwable) {
            null
        }
    }

    /* ------------------------------------ helpers ------------------------------------ */

    /** ip 或 ip:port（仅当恰有一个冒号且后缀为数字时才拆端口），缺省 54321。 */
    private fun parseTarget(raw: String): Pair<String, Int> {
        val s = raw.trim()
        require(s.isNotEmpty()) { "缺少 ip" }
        val colon = s.indexOf(':')
        val hasPort = colon > 0 && s.indexOf(':', colon + 1) < 0 &&
            s.substring(colon + 1).isNotEmpty() && s.substring(colon + 1).all { it.isDigit() }
        val host = if (hasPort) s.substring(0, colon) else s
        val port = if (hasPort) s.substring(colon + 1).toInt() else MiioCodec.DEFAULT_PORT
        require(host.isNotBlank() && !host.contains('/') && !host.contains(' ')) { "ip 非法" }
        require(port in 1..65535) { "ip 端口范围 1~65535" }
        return host to port
    }

    private fun requireTimeout(raw: Int?): Int {
        val v = raw ?: 2000
        require(v in 1000..5000) { "timeout_ms 范围 1000~5000，实际 $v" }
        return v
    }

    private fun requireRetries(raw: Int?): Int {
        val v = raw ?: 1
        require(v in 0..2) { "retries 范围 0~2，实际 $v" }
        return v
    }

    private fun classify(t: Throwable): Pair<String, String> {
        if (t is IllegalArgumentException) {
            return "invalid_args" to (t.message?.takeIf { it.isNotBlank() } ?: "参数校验失败")
        }
        val msg = t.message.orEmpty().lowercase()
        val simple = t::class.java.simpleName.lowercase()
        return when {
            "portunreachable" in simple || "connection refused" in msg || "econnrefused" in msg ->
                "unreachable" to "设备不可达：目标端口未响应 miIO（UDP ${MiioCodec.DEFAULT_PORT}）"
            "sockettimeout" in simple || "timed out" in msg || "timeout" in msg ->
                "timeout" to "设备在该超时时间内未响应"
            "unknownhost" in simple || "unable to resolve" in msg ->
                "dns" to "无法解析设备主机名"
            else -> "io_error" to "网络/协议失败：${t.message?.take(160).orEmpty().ifBlank { t.javaClass.simpleName }}"
        }
    }

    private fun errorJson(ip: String?, tokenRaw: String?, error: String, reason: String): String =
        buildJsonObject {
            put("ok", false)
            if (ip != null) put("ip", redact(ip, tokenRaw))
            put("token", MiioCodec.maskToken(tokenRaw))
            put("error", error)
            put("reason", redact(reason, tokenRaw))
        }.toString()

    private fun redact(text: String, secret: String?): String =
        if (secret.isNullOrBlank()) text else text.replace(secret, "<masked>")

    /** command 允许传字符串，也允许模型直接塞一个 JSON 对象。 */
    private fun JsonObject.commandArg(key: String): String? {
        val el: JsonElement = this[key] ?: return null
        return when (el) {
            is JsonPrimitive -> el.contentOrNull?.takeIf { it.isNotBlank() }
            else -> el.toString()
        }
    }
}
