package com.dc.andagent.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.eclipse.paho.client.mqttv3.MqttException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.UnknownHostException

/**
 * R144 MQTT 刀 JVM 单测：
 *  1) MqttCodec 纯函数（broker 解析、topic/filter 校验与通配匹配、payload 截断、参数收敛、错误分类、脱敏）
 *  2) 三工具在 broker 不可达（127.0.0.1:1）时的结构化容错返回
 *  3) 参数校验失败分支
 *  4) 注册表 / schema 对齐（工具名与参数键）
 * 不需要 Robolectric：MqttTool 的 Context 可空，Paho 是纯 Java，127.0.0.1:1 立即连接被拒。
 */
class MqttToolsTest {

    /* ------------------------------ broker 解析 ------------------------------ */

    @Test fun parseBrokerTcpWithExplicitPort() {
        val b = MqttCodec.parseBroker("tcp://192.168.1.10:1883")
        assertEquals("tcp://192.168.1.10:1883", b.uri)
        assertEquals("tcp", b.scheme)
        assertEquals("192.168.1.10", b.host)
        assertEquals(1883, b.port)
    }

    @Test fun parseBrokerUsesDefaultPortPerScheme() {
        assertEquals(1883, MqttCodec.parseBroker("tcp://localhost").port)
        assertEquals(8883, MqttCodec.parseBroker("ssl://broker.local").port)
        assertEquals(80, MqttCodec.parseBroker("ws://broker.local").port)
        assertEquals(443, MqttCodec.parseBroker("wss://broker.local").port)
    }

    @Test fun parseBrokerAcceptsUnderscoreHostsAndIPv6() {
        // java.net.URI.getHost() 对下划线返回 null，故这里用手工解析，需可通过。
        assertEquals("my_broker.local", MqttCodec.parseBroker("tcp://my_broker.local:1884").host)
        assertEquals(1884, MqttCodec.parseBroker("tcp://my_broker.local:1884").port)
        assertEquals("::1", MqttCodec.parseBroker("tcp://[::1]:1883").host)
        assertEquals(1883, MqttCodec.parseBroker("tcp://[::1]").port)
    }

    @Test fun parseBrokerRejectsInvalidInput() {
        for (bad in listOf(
            null, "", "  ", "192.168.1.10:1883", "udp://h:1883", "tcp://",
            "tcp://h:abc", "tcp://h:70000", "tcp://h:0", "tcp://h:-1",
        )) {
            try {
                MqttCodec.parseBroker(bad)
                fail("expected rejection for $bad")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.isNotBlank())
            }
        }
    }

    @Test fun parseBrokerRejectsUserinfoCredentials() {
        // R144 review 必修项②：URI 内嵌凭据会被原串回显，必须拒绝。
        for (bad in listOf(
            "tcp://user:pass@192.168.1.10:1883",
            "tcp://user@host",
            "ssl://admin:s3cr3t@broker.local:8883/topic",
        )) {
            try {
                MqttCodec.parseBroker(bad)
                fail("expected rejection for $bad")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.contains("username/password"))
            }
        }
    }

    @Test fun sanitizeBrokerMasksUserinfo() {
        assertEquals("tcp://plain:1883", MqttCodec.sanitizeBroker("tcp://plain:1883"))
        assertEquals("tcp://<masked>@h:1", MqttCodec.sanitizeBroker("tcp://user:pass@h:1"))
        assertEquals("tcp://<masked>@h:1", MqttCodec.sanitizeBroker("tcp://user@h:1"))
        assertEquals("<masked>@h", MqttCodec.sanitizeBroker("user:pass@h"))
        assertNull(MqttCodec.sanitizeBroker(null))
    }

    /* --------------------------- topic / filter 校验 -------------------------- */

    @Test fun validatePublishTopicTrimsAndRejectsWildcards() {
        assertEquals("home/kitchen/temp", MqttCodec.validatePublishTopic(" home/kitchen/temp "))
        for (bad in listOf(null, "", "  ", "home/+/temp", "home/#", "home/\u0000x")) {
            try {
                MqttCodec.validatePublishTopic(bad)
                fail("expected rejection for $bad")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.isNotBlank())
            }
        }
    }

    @Test fun validateTopicFilterAcceptsWellFormedWildcards() {
        assertEquals("home/#", MqttCodec.validateTopicFilter(" home/# "))
        MqttCodec.validateTopicFilter("home/+/temperature")
        MqttCodec.validateTopicFilter("#")
        MqttCodec.validateTopicFilter("+/+/+")
    }

    @Test fun validateTopicFilterRejectsMalformedWildcards() {
        for (bad in listOf(
            null, "", "home/#/ranking", "home/#/", "home/a#", "home/a+b", "home/\u0000x",
        )) {
            try {
                MqttCodec.validateTopicFilter(bad)
                fail("expected rejection for $bad")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.isNotBlank())
            }
        }
    }

    /* ------------------------------ 通配匹配 ------------------------------ */

    @Test fun matchesExactAndSingleLevelPlus() {
        assertTrue(MqttCodec.matches("home/kitchen/temperature", "home/kitchen/temperature"))
        assertTrue(MqttCodec.matches("home/+/temperature", "home/kitchen/temperature"))
        assertTrue(MqttCodec.matches("sport/+/player1", "sport//player1")) // + 也匹配空层级
        assertFalse(MqttCodec.matches("home/+/temperature", "home/kitchen/living/temperature"))
        assertFalse(MqttCodec.matches("home/kitchen/temperature", "home/kitchen/humidity"))
    }

    @Test fun matchesHashMultiLevelAndParent() {
        assertTrue(MqttCodec.matches("home/#", "home/kitchen/temperature"))
        assertTrue(MqttCodec.matches("home/#", "home")) // # 也匹配父层（零层）
        assertTrue(MqttCodec.matches("#", "a/b/c"))
        assertFalse(MqttCodec.matches("home/#", "office/kitchen"))
    }

    @Test fun matchesKeepsTrailingEmptyLevelSignificant() {
        // topic `a/b/` 比 filter `a/b` 多一个空层级，应判不匹配。
        assertFalse(MqttCodec.matches("a/b", "a/b/"))
        assertTrue(MqttCodec.matches("a/+", "a/"))
    }

    /* ------------------------------ payload 截断 ------------------------------ */

    @Test fun decodePayloadShortNotTruncated() {
        val d = MqttCodec.decodePayload("""{"state":"on"}""".toByteArray(Charsets.UTF_8))
        assertFalse(d.truncated)
        assertEquals("""{"state":"on"}""", d.text)
    }

    @Test fun decodePayloadTruncatesAt4k() {
        val bytes = "x".repeat(5000).toByteArray(Charsets.UTF_8)
        val d = MqttCodec.decodePayload(bytes)
        assertTrue(d.truncated)
        assertEquals(5000, d.bytes)
        assertEquals(4096, d.text.length)
    }

    @Test fun decodePayloadDoesNotSplitUtf8Character() {
        // 4095 个 'a' + 一个三字节 '€'：第 4096 字节落在 '€' 的续接字节上，需整体回退。
        val payload = ("a".repeat(4095) + "€").toByteArray(Charsets.UTF_8)
        val d = MqttCodec.decodePayload(payload)
        assertTrue(d.truncated)
        assertEquals(payload.size, d.bytes)
        assertEquals("a".repeat(4095), d.text)
        assertFalse(d.text.contains('\uFFFD'))
    }

    /* ---------------------------- 参数收敛 / 脱敏 ---------------------------- */

    @Test fun qosAndWindowCoercion() {
        assertEquals(0, MqttCodec.requireQos(null))
        assertEquals(2, MqttCodec.requireQos(2))
        for (bad in listOf(-1, 3, 9)) {
            try {
                MqttCodec.requireQos(bad)
                fail("expected rejection for qos=$bad")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.contains("qos"))
            }
        }
        assertEquals(1000, MqttCodec.coerceTimeout(10))
        assertEquals(30000, MqttCodec.coerceTimeout(999999))
        assertEquals(5000, MqttCodec.coerceTimeout(null))
        assertEquals(3000, MqttCodec.coerceDuration(1))
        assertEquals(60000, MqttCodec.coerceDuration(999999))
        assertEquals(10000, MqttCodec.coerceDuration(null))
        assertEquals(1, MqttCodec.coerceMaxMessages(0))
        assertEquals(50, MqttCodec.coerceMaxMessages(999))
        assertEquals(20, MqttCodec.coerceMaxMessages(null))
    }

    @Test fun authLabelAndRedactNeverLeakSecret() {
        assertEquals("none", MqttCodec.authLabel(null))
        assertEquals("user/<masked>", MqttCodec.authLabel("admin"))
        assertFalse(MqttCodec.authLabel("admin").contains("admin"))
        assertEquals("连接失败 <masked>", MqttCodec.redact("连接失败 s3cr3t", "s3cr3t"))
        assertEquals("no secret", MqttCodec.redact("no secret", null))
        assertTrue(MqttCodec.randomClientId().startsWith("andagent-"))
        assertTrue(MqttCodec.randomClientId().length <= 23)
    }

    /* ------------------------------ 错误分类 ------------------------------ */

    @Test fun classifyMapsValidationAndMessageHeuristics() {
        assertEquals("invalid_args", MqttCodec.classify(IllegalArgumentException("qos 只能是 0/1/2")).kind)
        assertEquals("timeout", MqttCodec.classify(RuntimeException("connection timed out")).kind)
        assertEquals("auth", MqttCodec.classify(RuntimeException("Bad user name or password")).kind)
        assertEquals("dns", MqttCodec.classify(UnknownHostException("nosuchhost")).kind)
        assertEquals("refused", MqttCodec.classify(RuntimeException("Connection refused (ECONNREFUSED)")).kind)
        assertEquals("unreachable", MqttCodec.classify(RuntimeException("something else")).kind)
    }

    @Test fun classifyMapsPahoReasonCodes() {
        assertEquals("timeout", MqttCodec.classify(MqttException(MqttException.REASON_CODE_CLIENT_TIMEOUT.toInt())).kind)
        assertEquals("auth", MqttCodec.classify(MqttException(MqttException.REASON_CODE_FAILED_AUTHENTICATION.toInt())).kind)
        assertEquals("auth", MqttCodec.classify(MqttException(MqttException.REASON_CODE_NOT_AUTHORIZED.toInt())).kind)
        assertEquals("refused", MqttCodec.classify(MqttException(MqttException.REASON_CODE_SERVER_CONNECT_ERROR.toInt())).kind)
        assertTrue(MqttCodec.classify(MqttException(MqttException.REASON_CODE_CLIENT_TIMEOUT.toInt())).reason.isNotBlank())
    }

    /* --------------------- 工具：broker 不可达结构化容错 --------------------- */

    @Test fun pubUnreachableBrokerReturnsStructuredError() {
        val result = obj(call("mqtt_pub", """{"broker":"tcp://127.0.0.1:1","topic":"home/test","payload":"on","timeout_ms":1000}"""))
        assertFalse(result["ok"]!!.jsonPrimitive.boolean)
        assertNotNull(result["error"])
        assertNotNull(result["reason"])
        assertTrue(result["reason"]!!.jsonPrimitive.content.isNotBlank())
        assertEquals("tcp://127.0.0.1:1", result["broker"]!!.jsonPrimitive.content)
        assertEquals("none", result["auth"]!!.jsonPrimitive.content)
    }

    @Test fun subUnreachableBrokerReturnsStructuredError() {
        val result = obj(call("mqtt_sub", """{"broker":"tcp://127.0.0.1:1","topic_filter":"home/#","duration_ms":3000,"timeout_ms":1000}"""))
        assertFalse(result["ok"]!!.jsonPrimitive.boolean)
        assertNotNull(result["error"])
        assertTrue(result["reason"]!!.jsonPrimitive.content.isNotBlank())
        assertEquals("home/#", result["topic_filter"]!!.jsonPrimitive.content)
    }

    @Test fun credentialsAreNeverEchoedInResult() {
        val text = call("mqtt_pub", """{"broker":"tcp://127.0.0.1:1","topic":"home/test","payload":"on","username":"admin","password":"s3cr3t","timeout_ms":1000}""")
        assertFalse(text.contains("s3cr3t"))
        assertFalse(text.contains("admin"))
        assertEquals("user/<masked>", obj(text)["auth"]!!.jsonPrimitive.content)
    }

    @Test fun userinfoInBrokerIsRejectedAndNeverEchoed() {
        // R144 review 必修项②：带 userinfo 的 broker 一律拒绝，错误回显也不含凭据。
        val text = call("mqtt_pub", """{"broker":"tcp://admin:s3cr3t@127.0.0.1:1","topic":"home/test","payload":"on"}""")
        assertFalse(text.contains("s3cr3t"))
        assertFalse(text.contains("admin"))
        val result = obj(text)
        assertFalse(result["ok"]!!.jsonPrimitive.boolean)
        assertEquals("invalid_args", result["error"]!!.jsonPrimitive.content)
    }

    @Test fun errorPathMasksPasswordInBrokerField() {
        // qos 校验失败走 errorJson，但 broker 原串里嵌了密码：回显必须打码。
        val text = call("mqtt_pub", """{"broker":"tcp://h:1/pw=s3cr3t","topic":"home/#","payload":"on","password":"s3cr3t"}""")
        assertFalse(text.contains("s3cr3t"))
    }

    @Test fun successResultTopicNeverEchoesPassword() {
        // R144 review 必修项②：成功路径回显的 topic 也不含明文密码。
        // 127.0.0.1:1 不可达 → 走 errorJson，但该路径已验证；这里验证校验失败前
        // topic 已过 redact 的等价路径：用 qos 校验失败+密码进 topic 的组合断言回显。
        val text = call("mqtt_pub", """{"broker":"tcp://127.0.0.1:1","topic":"pw=s3cr3t","payload":"on","qos":7,"password":"s3cr3t"}""")
        assertFalse(text.contains("s3cr3t"))
        // 校验通过后的成功回显路径（topic redact）由连接可达性限制无法在 JVM 直测，
        // 与订阅结果 redact 同一实现（MqttCodec.redact），由下方纯函数断言补足。
        assertEquals("pw=<masked>", MqttCodec.redact("pw=s3cr3t", "s3cr3t"))
    }

    @Test fun statusToolIsAlwaysOkAndHasNoParams() {
        val result = obj(call("mqtt_status", "{}"))
        assertTrue(result["ok"]!!.jsonPrimitive.boolean)
        assertNotNull(result["active"])
        assertNotNull(result["hasLast"])
        assertTrue(MqttTool(null, "mqtt_status").parameters.toString().contains("\"properties\""))
    }

    /* -------------------------- 工具：参数校验失败 -------------------------- */

    @Test fun pubValidationFailuresAreStructured() {
        val missingBroker = obj(call("mqtt_pub", """{"topic":"x","payload":"on"}"""))
        assertFalse(missingBroker["ok"]!!.jsonPrimitive.boolean)
        assertEquals("invalid_args", missingBroker["error"]!!.jsonPrimitive.content)

        val badQos = obj(call("mqtt_pub", """{"broker":"tcp://127.0.0.1:1","topic":"x","payload":"on","qos":7}"""))
        assertEquals("invalid_args", badQos["error"]!!.jsonPrimitive.content)

        val wildcardTopic = obj(call("mqtt_pub", """{"broker":"tcp://127.0.0.1:1","topic":"home/#","payload":"on"}"""))
        assertEquals("invalid_args", wildcardTopic["error"]!!.jsonPrimitive.content)

        val noPayload = obj(call("mqtt_pub", """{"broker":"tcp://127.0.0.1:1","topic":"x"}"""))
        assertEquals("invalid_args", noPayload["error"]!!.jsonPrimitive.content)
    }

    @Test fun subValidationFailuresAreStructured() {
        val missingFilter = obj(call("mqtt_sub", """{"broker":"tcp://127.0.0.1:1"}"""))
        assertFalse(missingFilter["ok"]!!.jsonPrimitive.boolean)
        assertEquals("invalid_args", missingFilter["error"]!!.jsonPrimitive.content)

        val badFilter = obj(call("mqtt_sub", """{"broker":"tcp://127.0.0.1:1","topic_filter":"home/#/x"}"""))
        assertEquals("invalid_args", badFilter["error"]!!.jsonPrimitive.content)

        val badScheme = obj(call("mqtt_sub", """{"broker":"udp://h:1883","topic_filter":"#"}"""))
        assertEquals("invalid_args", badScheme["error"]!!.jsonPrimitive.content)
    }

    /* ------------------------- 注册表 / schema 对齐 ------------------------- */

    @Test fun mqttToolsRegistryHasThreeAlignedNames() {
        assertEquals(listOf("mqtt_pub", "mqtt_sub", "mqtt_status"), MQTT_TOOLS)
    }

    @Test fun toolSchemasExposeExpectedParameterKeys() {
        val pub = MqttTool(name = "mqtt_pub").parameters as JsonObject
        val pubProps = pub["properties"]!!.jsonObject
        assertEquals(
            setOf("broker", "topic", "payload", "qos", "retain", "client_id", "username", "password", "timeout_ms"),
            pubProps.keys,
        )
        assertEquals(
            listOf("broker", "topic", "payload"),
            pub["required"]!!.jsonArray.map { it.jsonPrimitive.content },
        )

        val sub = MqttTool(name = "mqtt_sub").parameters as JsonObject
        val subProps = sub["properties"]!!.jsonObject
        assertEquals(
            setOf("broker", "topic_filter", "qos", "username", "password", "client_id", "duration_ms", "max_messages", "timeout_ms"),
            subProps.keys,
        )
        assertEquals(
            listOf("broker", "topic_filter"),
            sub["required"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        val status = MqttTool(name = "mqtt_status").parameters as JsonObject
        assertTrue(status["properties"]!!.jsonObject.isEmpty())
        assertFalse(status.containsKey("required"))
    }

    /* -------------------------------- helpers -------------------------------- */

    private fun call(name: String, json: String): String =
        runBlocking { MqttTool(name = name).execute(Json.parseToJsonElement(json) as JsonObject) }

    private fun obj(text: String): JsonObject {
        assertFalse("expected JSON but got: $text", text.startsWith("ERROR"))
        val parsed = Json.parseToJsonElement(text) as? JsonObject
        assertNotNull("expected JSON object but got: $text", parsed)
        return parsed!!
    }
}
