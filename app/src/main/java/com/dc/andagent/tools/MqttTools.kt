package com.dc.andagent.tools

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * R144 (Roadmap R78 a 项) — MQTT v3.1.1 客户端三工具，面向局域网 MQTT broker
 * （ESPHome / Home Assistant / Mosquitto / 涂鸦等）。
 *
 * - mqtt_pub    : 同步发布一条消息后断开短连接。
 * - mqtt_sub    : 同步订阅窗口，在 duration_ms 内收集消息后一次性返回（不做后台常驻）。
 * - mqtt_status : 列出当前活动窗口数与最近一次订阅统计（同步窗口式实现，故第三工具取
 *                 mqtt_status 而非 mqtt_stop；没有后台 job 可停）。
 *
 * 所有网络操作都在 Dispatchers.IO 上跑，凭据（username/password）只经工具参数随调用传入，
 * 绝不写入 KvStore / 文件、绝不出现在返回值或日志中（结果里只输出 auth: user/<masked>）。
 */
val MQTT_TOOLS = listOf("mqtt_pub", "mqtt_sub", "mqtt_status")

/** 最近一次订阅窗口的统计（进程内内存态，凭据零入库）。 */
data class MqttSubSnapshot(
    val broker: String,
    val topicFilter: String,
    val count: Int,
    val elapsedMs: Long,
    val ok: Boolean,
    val timestamp: Long,
)

private object MqttSubState {
    val active = AtomicInteger(0)

    @Volatile
    var last: MqttSubSnapshot? = null
}

/**
 * 纯逻辑层：broker 地址解析、topic/topic_filter 校验、MQTT 通配匹配、payload 截断、
 * 参数范围收敛、错误分类与凭据脱敏。不依赖任何 Android API，便于 JVM 单测直接覆盖。
 */
object MqttCodec {
    const val MAX_PAYLOAD_BYTES = 4096
    const val MIN_DURATION_MS = 3000
    const val MAX_DURATION_MS = 60000
    const val MAX_MESSAGES = 50

    private val SUPPORTED_SCHEMES = setOf("tcp", "ssl", "ws", "wss")

    data class Broker(val uri: String, val scheme: String, val host: String, val port: Int)
    data class Payload(val text: String, val bytes: Int, val truncated: Boolean)
    data class Failure(val kind: String, val reason: String)

    /** 解析 `tcp://host:port`（也支持 ssl/ws/wss），端口省略时按 scheme 取默认值。 */
    fun parseBroker(raw: String?): Broker {
        val text = raw?.trim().orEmpty()
        require(text.isNotEmpty()) { "缺少 broker，如 tcp://192.168.1.10:1883" }
        val sep = text.indexOf("://")
        require(sep > 0) { "broker 需带协议前缀，如 tcp://192.168.1.10:1883" }
        val scheme = text.substring(0, sep).lowercase()
        require(scheme in SUPPORTED_SCHEMES) { "broker 仅支持 tcp:// / ssl:// / ws:// / wss://" }
        val hostPort = text.substring(sep + 3).substringBefore('/')
        require(hostPort.isNotEmpty()) { "broker 缺少主机名" }
        // R144 review 必修项②：broker URI 不允许携带 userinfo（tcp://user:pass@host），
        // 否则原串会被回显进结果/快照造成凭据泄露。凭据必须走 username/password 参数。
        require(!hostPort.contains('@')) {
            "broker 不应包含用户名:密码（如 tcp://user:pass@host），凭据请走 username/password 参数"
        }
        val (host, portText) = if (hostPort.startsWith("[")) {
            val close = hostPort.indexOf(']')
            require(close > 1) { "broker IPv6 地址无效" }
            val after = hostPort.substring(close + 1)
            hostPort.substring(1, close) to after.removePrefix(":")
        } else {
            val colon = hostPort.lastIndexOf(':')
            if (colon >= 0) hostPort.substring(0, colon) to hostPort.substring(colon + 1)
            else hostPort to ""
        }
        require(host.isNotBlank()) { "broker 缺少主机名" }
        require('\u0000' !in text) { "broker 含非法字符" }
        val port = if (portText.isEmpty()) defaultPort(scheme) else
            portText.toIntOrNull() ?: throw IllegalArgumentException("broker 端口无效: $portText")
        require(port in 1..65535) { "broker 端口范围 1~65535" }
        return Broker(text, scheme, host, port)
    }

    /**
     * R144 review 必修项②兜底：结果/快照/错误里回显 broker 原串前，把可能混入的
     * userinfo（tcp://user:pass@host）脱敏为 tcp://<masked>@host，密码永不出现在输出中。
     */
    fun sanitizeBroker(raw: String?): String? {
        raw ?: return null
        // 按 "@" 切：userinfo 段（可能含冒号）与 host:port 段。
        val at = raw.lastIndexOf('@')
        if (at < 0) return raw
        val sep = raw.indexOf("://")
        val schemePrefix = if (sep > 0) raw.substring(0, sep + 3) else ""
        val userinfo = if (sep > 0 && at > sep + 3) raw.substring(sep + 3, at) else raw.substring(0, at)
        if (userinfo.isEmpty()) return raw
        return schemePrefix + "<masked>@" + raw.substring(at + 1)
    }

    private fun defaultPort(scheme: String): Int = when (scheme) {
        "ssl" -> 8883
        "wss" -> 443
        "ws" -> 80
        else -> 1883
    }

    /** 发布 topic 不允许通配符。返回 trim 后的 topic。 */
    fun validatePublishTopic(topic: String?): String {
        val t = topic?.trim().orEmpty()
        require(t.isNotEmpty()) { "缺少 topic" }
        require('+' !in t && '#' !in t) { "发布 topic 不能含 + / # 通配符" }
        require('\u0000' !in t) { "topic 含非法字符" }
        return t
    }

    /** 订阅过滤器校验：+ / # 只能独占一个层级，# 必须在末尾且独占一层。 */
    fun validateTopicFilter(filter: String?): String {
        val f = filter?.trim().orEmpty()
        require(f.isNotEmpty()) { "缺少 topic_filter" }
        require('\u0000' !in f) { "topic_filter 含非法字符" }
        val parts = levels(f)
        parts.forEachIndexed { idx, level ->
            require(level != "#" || idx == parts.lastIndex) { "# 只能出现在最后一层" }
            require(!level.contains('#') || level == "#") { "# 必须独占一层" }
            require(!level.contains('+') || level == "+") { "+ 必须独占一层" }
        }
        return f
    }

    /** 按 / 切层，保留空层级（含末尾）——MQTT 里 topic `a/` 的第二层是空串，不可丢。 */
    private fun levels(s: String): List<String> {
        val out = mutableListOf<String>()
        var start = 0
        for (i in s.indices) {
            if (s[i] == '/') {
                out.add(s.substring(start, i))
                start = i + 1
            }
        }
        out.add(s.substring(start))
        return out
    }

    /**
     * MQTT 主题过滤匹配（纯字符串函数）。+ 匹配单个层级，# 匹配零个或多个层级且须在末尾。
     * 这是订阅回调的本地兜底网：broker 侧已按过滤器投递，这里再校一次防止异常 broker。
     * 注：$SYS 前缀不参与本地匹配（是否投递由 broker 决定）。
     */
    fun matches(filter: String, topic: String): Boolean {
        val f = levels(filter)
        val t = levels(topic)
        var i = 0
        var j = 0
        while (i < f.size) {
            when (val level = f[i]) {
                "#" -> return i == f.lastIndex // 匹配剩余全部层级（含 0 个）
                "+" -> {
                    if (j >= t.size) return false
                    i++; j++
                }
                else -> {
                    if (j >= t.size || level != t[j]) return false
                    i++; j++
                }
            }
        }
        return j == t.size
    }

    /** UTF-8 安全地按字节截断 payload，返回原文长度与是否截断。 */
    fun decodePayload(payload: ByteArray, maxBytes: Int = MAX_PAYLOAD_BYTES): Payload {
        val bytes = payload.size
        if (bytes <= maxBytes) return Payload(String(payload, Charsets.UTF_8), bytes, false)
        var end = maxBytes
        // 回退越过续接字节(10xxxxxx)，避免把一个多字节字符切成两半。
        while (end > 0 && (payload[end].toInt() and 0xC0) == 0x80) end--
        return Payload(String(payload, 0, end, Charsets.UTF_8), bytes, true)
    }

    fun requireQos(raw: Int?): Int {
        val q = raw ?: 0
        require(q in 0..2) { "qos 只能是 0/1/2" }
        return q
    }

    fun coerceTimeout(raw: Int?): Int = (raw ?: 5000).coerceIn(1000, 30000)

    fun coerceDuration(raw: Int?): Int =
        (raw ?: 10000).coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)

    fun coerceMaxMessages(raw: Int?): Int = (raw ?: 20).coerceIn(1, MAX_MESSAGES)

    fun randomClientId(): String = "andagent-" + UUID.randomUUID().toString().take(8)

    /** 凭据脱敏后的标注：密码永不出现在结果里。 */
    fun authLabel(username: String?): String = if (username == null) "none" else "user/<masked>"

    /** 兜底：万一异常信息里混入了密码，也一律打码。 */
    fun redact(text: String, secret: String?): String =
        if (secret.isNullOrEmpty()) text else text.replace(secret, "<masked>")

    /** 把异常映射为结构化错误类别，broker 不可达时给出具体原因。 */
    fun classify(t: Throwable): Failure {
        if (t is IllegalArgumentException) {
            return Failure("invalid_args", t.message?.takeIf { it.isNotBlank() } ?: "参数校验失败")
        }
        val msg = t.message.orEmpty()
        val low = msg.lowercase()
        val simple = t::class.java.simpleName.lowercase()
        val rc = (t as? MqttException)?.reasonCode ?: -1
        return when {
            rc == MqttException.REASON_CODE_CLIENT_TIMEOUT.toInt() ||
                low.contains("timed out") || low.contains("timeout") || simple.contains("timeout") ->
                Failure("timeout", "连接超时：broker 在超时时间内未响应")
            rc == MqttException.REASON_CODE_FAILED_AUTHENTICATION.toInt() ||
                rc == MqttException.REASON_CODE_NOT_AUTHORIZED.toInt() ||
                low.contains("bad user name or password") || low.contains("not authorized") ||
                low.contains("authentication") || low.contains("认证") || low.contains("授权") ->
                Failure("auth", "认证失败：用户名或密码被 broker 拒绝")
            low.contains("unknownhost") || low.contains("unable to resolve") ||
                low.contains("name or service not known") || simple.contains("unknownhost") ->
                Failure("dns", "DNS 失败：无法解析 broker 主机名")
            rc == MqttException.REASON_CODE_SERVER_CONNECT_ERROR.toInt() ||
                low.contains("connection refused") || low.contains("econnrefused") ||
                simple.contains("connectexception") ->
                Failure("refused", "连接被拒绝：broker 地址或端口不可达")
            else -> Failure("unreachable", "broker 不可达或执行失败：${msg.take(200).ifBlank { t.javaClass.simpleName }}")
        }
    }
}

class MqttTool(
    @Suppress("UNUSED_PARAMETER") context: Context? = null,
    override val name: String,
) : Tool {
    override val description = when (name) {
        "mqtt_pub" ->
            "MQTT 发布：连接局域网 MQTT broker（ESPHome / Home Assistant / Mosquitto / 涂鸦等）并把一条消息发布到指定 topic。参数 broker（tcp://主机:端口，也支持 ssl/ws/wss）、topic、payload（字符串或 JSON 字符串）；可选 qos(0/1/2，默认 0)、retain(默认 false)、client_id（默认随机）、username/password（凭据随调用传入，绝不回显或落盘）、timeout_ms（默认 5000，1000~30000）。同步发布后断开短连接；broker 不可达/认证失败/DNS 失败时返回 ok=false 与具体原因，不 crash。"
        "mqtt_sub" ->
            "MQTT 订阅窗口：连接 broker 并按 topic_filter（支持 + 单层 / # 多层通配）在指定窗口内收集消息（topic/payload 截断 4KB/qos/timestamp），窗口结束后一次性返回。参数 broker、topic_filter；可选 qos、username/password、client_id、duration_ms（订阅窗口，默认 10000，3000~60000，长任务）、max_messages（默认 20，上限 50）、timeout_ms。初版为同步窗口式订阅，不做后台常驻；mqtt_status 可查当前/最近一次订阅统计。"
        else ->
            "MQTT 订阅状态：列出当前活动订阅窗口数与最近一次订阅的统计（broker、topic_filter、count、elapsed_ms、ok、timestamp）。因 mqtt_sub 是同步窗口式（无后台常驻 job），无活动订阅时 active=false、hasLast 视是否有过订阅而定；本工具只读、无参数。"
    }

    override val parameters = when (name) {
        "mqtt_pub" -> schemaOf(
            "broker" to strParam("broker 地址，如 tcp://192.168.1.10:1883（支持 tcp/ssl/ws/wss，默认端口 1883/8883/80/443）"),
            "topic" to strParam("发布主题，不能含 + / # 通配符"),
            "payload" to strParam("消息正文（字符串或 JSON 字符串）"),
            "qos" to intParam("QoS 0/1/2，默认 0"),
            "retain" to boolParam("是否保留消息，默认 false"),
            "client_id" to strParam("客户端 ID，默认随机生成"),
            "username" to strParam("用户名（凭据随调用传入，不回显不落盘）"),
            "password" to strParam("密码（凭据随调用传入，不回显不落盘）"),
            "timeout_ms" to intParam("连接/操作超时毫秒，默认 5000，范围 1000~30000"),
            required = listOf("broker", "topic", "payload"),
        )
        "mqtt_sub" -> schemaOf(
            "broker" to strParam("broker 地址，如 tcp://192.168.1.10:1883（支持 tcp/ssl/ws/wss，默认端口 1883/8883/80/443）"),
            "topic_filter" to strParam("订阅主题过滤器，支持 + 单层 / # 多层通配，如 home/+/temperature 或 home/#"),
            "qos" to intParam("订阅 QoS 0/1/2，默认 0"),
            "username" to strParam("用户名（凭据随调用传入，不回显不落盘）"),
            "password" to strParam("密码（凭据随调用传入，不回显不落盘）"),
            "client_id" to strParam("客户端 ID，默认随机生成"),
            "duration_ms" to intParam("订阅窗口毫秒，默认 10000，范围 3000~60000（长任务）"),
            "max_messages" to intParam("最多收集消息数，默认 20，上限 50"),
            "timeout_ms" to intParam("连接超时毫秒，默认 5000，范围 1000~30000"),
            required = listOf("broker", "topic_filter"),
        )
        else -> schemaOf(required = emptyList())
    }

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        when (name) {
            "mqtt_pub" -> executePub(args)
            "mqtt_sub" -> executeSub(args)
            else -> statusJson()
        }
    }

    /* --------------------------------- mqtt_pub --------------------------------- */

    private fun executePub(args: JsonObject): String {
        val brokerRaw = args.strArg("broker")
        val topicRaw = args.strArg("topic")
        val username = args.strArg("username")
        val password = args.strArg("password")
        return try {
            val broker = MqttCodec.parseBroker(brokerRaw)
            val topic = MqttCodec.validatePublishTopic(topicRaw)
            val payload = args.rawString("payload") ?: throw IllegalArgumentException("缺少 payload")
            val qos = MqttCodec.requireQos(args.intArg("qos"))
            val retain = args.boolArg("retain") ?: false
            val timeoutMs = MqttCodec.coerceTimeout(args.intArg("timeout_ms"))
            val clientId = args.strArg("client_id") ?: MqttCodec.randomClientId()
            publish(broker, topic, payload, qos, retain, username, password, clientId, timeoutMs)
        } catch (t: Throwable) {
            errorJson(brokerRaw, listOfNotNull(topicRaw?.let { "topic" to it }), username, password, t)
        }
    }

    private fun publish(
        broker: MqttCodec.Broker,
        topic: String,
        payload: String,
        qos: Int,
        retain: Boolean,
        username: String?,
        password: String?,
        clientId: String,
        timeoutMs: Int,
    ): String {
        val started = System.nanoTime()
        val client = MqttClient(broker.uri, clientId, MemoryPersistence())
        try {
            client.connect(connectOptions(username, password, timeoutMs))
            client.publish(topic, MqttMessage(payload.toByteArray(Charsets.UTF_8)).apply {
                setQos(qos)
                setRetained(retain)
            })
            return buildJsonObject {
                put("ok", true)
                // R144 review 必修项②：成功路径回显字段也统一过 redact——topic/payload
                // 理论上不含凭据，但万一模型把密码拼进了 topic/payload，不留明文出口。
                put("broker", broker.uri)
                put("topic", MqttCodec.redact(topic, password))
                put("qos", qos)
                put("retain", retain)
                put("elapsed_ms", (System.nanoTime() - started) / 1_000_000)
                put("auth", MqttCodec.authLabel(username))
            }.toString()
        } finally {
            release(client)
        }
    }

    /* --------------------------------- mqtt_sub --------------------------------- */

    private suspend fun executeSub(args: JsonObject): String {
        val brokerRaw = args.strArg("broker")
        val filterRaw = args.strArg("topic_filter")
        val username = args.strArg("username")
        val password = args.strArg("password")
        return try {
            val broker = MqttCodec.parseBroker(brokerRaw)
            val filter = MqttCodec.validateTopicFilter(filterRaw)
            val qos = MqttCodec.requireQos(args.intArg("qos"))
            val timeoutMs = MqttCodec.coerceTimeout(args.intArg("timeout_ms"))
            val durationMs = MqttCodec.coerceDuration(args.intArg("duration_ms"))
            val maxMessages = MqttCodec.coerceMaxMessages(args.intArg("max_messages"))
            val clientId = args.strArg("client_id") ?: MqttCodec.randomClientId()
            collect(broker, filter, qos, username, password, clientId, timeoutMs, durationMs, maxMessages)
        } catch (t: Throwable) {
            errorJson(brokerRaw, listOfNotNull(filterRaw?.let { "topic_filter" to it }), username, password, t)
        }
    }

    private suspend fun collect(
        broker: MqttCodec.Broker,
        filter: String,
        qos: Int,
        username: String?,
        password: String?,
        clientId: String,
        timeoutMs: Int,
        durationMs: Int,
        maxMessages: Int,
    ): String {
        val started = System.nanoTime()
        val messages = Collections.synchronizedList(mutableListOf<MqttSubMessage>())
        // R144 review 必修项③：连接中断不再被吞掉——记录脱敏原因，窗口结束返回 ok=false。
        val lostReason = java.util.concurrent.atomic.AtomicReference<String?>(null)
        MqttSubState.active.incrementAndGet()
        var completed = false
        try {
            val client = MqttClient(broker.uri, clientId, MemoryPersistence())
            try {
                client.setCallback(object : MqttCallback {
                    override fun connectionLost(cause: Throwable?) {
                        val detail = when {
                            cause == null -> "连接中断"
                            else -> MqttCodec.classify(cause).reason
                        }
                        lostReason.compareAndSet(null, detail)
                    }
                    override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
                    override fun messageArrived(topic: String?, message: MqttMessage?) {
                        if (topic == null || message == null) return
                        if (!MqttCodec.matches(filter, topic)) return
                        val decoded = MqttCodec.decodePayload(message.getPayload())
                        val arrival = MqttSubMessage(
                            topic, decoded.text, message.getQos(),
                            System.currentTimeMillis(), decoded.truncated,
                        )
                        synchronized(messages) {
                            if (messages.size < maxMessages) messages.add(arrival)
                        }
                    }
                })
                client.connect(connectOptions(username, password, timeoutMs))
                client.subscribe(filter, qos)
                delay(durationMs.toLong())
                completed = true
            } finally {
                release(client)
            }
            val snapshot = synchronized(messages) { messages.toList() }
            val lost = lostReason.get()
            // 断线不算成功：ok=false + reason 说明中断，但已收到的消息仍如实返回。
            val ok = completed && lost == null
            return buildJsonObject {
                put("ok", ok)
                if (!ok && lost != null) {
                    put("error", "connection_lost")
                    put("reason", MqttCodec.redact(lost, password))
                }
                put("broker", broker.uri)
                // R144 review 必修项②：订阅结果回显字段统一过 redact——filter 与消息
                // topic/payload 万一含凭据，同样不给明文留出口。
                put("topic_filter", MqttCodec.redact(filter, password))
                put("count", snapshot.size)
                putJsonArray("messages") {
                    snapshot.forEach { m ->
                        add(buildJsonObject {
                            put("topic", MqttCodec.redact(m.topic, password))
                            put("payload", MqttCodec.redact(m.payload, password))
                            put("qos", m.qos)
                            put("timestamp", m.timestamp)
                            put("truncated", m.truncated)
                        })
                    }
                }
                put("elapsed_ms", (System.nanoTime() - started) / 1_000_000)
                put("auth", MqttCodec.authLabel(username))
            }.toString()
        } finally {
            MqttSubState.active.decrementAndGet()
            // R144 review 必修项②：快照在写入时就地除密——mqtt_status 回显时没有
            // password 上下文可用；broker.uri 已被 parseBroker 拒 userinfo，天然干净。
            MqttSubState.last = MqttSubSnapshot(
                broker.uri, MqttCodec.redact(filter, password), messages.size,
                (System.nanoTime() - started) / 1_000_000,
                completed && lostReason.get() == null,
                System.currentTimeMillis(),
            )
        }
    }

    /* -------------------------------- mqtt_status ------------------------------- */

    private fun statusJson(): String = buildJsonObject {
        put("ok", true)
        val active = MqttSubState.active.get()
        put("active", active > 0)
        put("activeCount", active)
        val last = MqttSubState.last
        put("hasLast", last != null)
        if (last != null) {
            put("last", buildJsonObject {
                put("broker", last.broker)
                put("topic_filter", last.topicFilter)
                put("count", last.count)
                put("elapsed_ms", last.elapsedMs)
                put("ok", last.ok)
                put("timestamp", last.timestamp)
            })
        }
    }.toString()

    /* ---------------------------------- helpers -------------------------------- */

    private fun connectOptions(username: String?, password: String?, timeoutMs: Int): MqttConnectOptions =
        MqttConnectOptions().apply {
            setCleanSession(true)
            setAutomaticReconnect(false)
            setConnectionTimeout((timeoutMs / 1000).coerceAtLeast(1))
            if (username != null) setUserName(username)
            if (password != null) setPassword(password.toCharArray())
        }

    private fun release(client: MqttClient) {
        runCatching { client.disconnect() }
        runCatching { client.close() }
    }

    private fun errorJson(
        broker: String?,
        fields: List<Pair<String, String>>,
        username: String?,
        password: String?,
        t: Throwable,
    ): String {
        val failure = MqttCodec.classify(t)
        // R144 review 必修项②：broker 原串可能含 userinfo（校验失败路径未经过 parseBroker
        // 拦截），回显前一律脱敏；密码即使混入 reason/broker 也整段打码。
        val safeBroker = MqttCodec.redact(MqttCodec.sanitizeBroker(broker) ?: "", password)
        // R144 六轮建议项：所有回显字段统一过 redact——topic/topic_filter 理论上
        // 不该含凭据，但万一模型把密码拼进了 topic，也不给明文留出口。
        return buildJsonObject {
            put("ok", false)
            if (broker != null) put("broker", safeBroker)
            fields.forEach { (k, v) -> put(k, MqttCodec.redact(v, password)) }
            put("error", failure.kind)
            put("reason", MqttCodec.redact(failure.reason, password))
            put("auth", MqttCodec.authLabel(username))
        }.toString()
    }

    private data class MqttSubMessage(
        val topic: String,
        val payload: String,
        val qos: Int,
        val timestamp: Long,
        val truncated: Boolean,
    )

    /** 允许空字符串的 body 读取（payload="" 是合法 MQTT 消息，如清除 retained）。 */
    private fun JsonObject.rawString(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull
}
