package com.dc.andagent.tools

import android.content.Context
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * R96: 真实 KvTool 实例的端到端门禁验证（Robolectric 提供 SharedPreferences）。
 * 刻意只覆盖 secure 读/拒绝与非 secure 路径，不做依赖 AndroidKeyStore 的 secure 成功写入。
 * 证明模型通道 execute 走默认拒绝策略、前端桥通道 executeWithSecureAccess 走注入策略。
 */
@RunWith(RobolectricTestRunner::class)
class KvToolSecureExecuteTest {
    private val app: Context get() = RuntimeEnvironment.getApplication()
    private val allowEngines: (String?) -> Boolean = { key -> key == "engines" }

    private fun s(v: String) = JsonPrimitive(v)
    private fun obj(vararg pairs: Pair<String, JsonElement>) = JsonObject(pairs.toMap())

    private fun tool(name: String) = KvTool(app, name)

    private fun secureEnginesGet() = obj("namespace" to s("secure"), "key" to s("engines"))
    private fun secureEnginesSet() = obj("namespace" to s("secure"), "key" to s("engines"), "value_json" to s("[]"))

    @Test fun modelKvGetSecureEnginesDenied() = runBlocking {
        assertEquals(KV_SECURE_DENIED_ERROR, tool("kv_get").execute(secureEnginesGet()))
    }

    @Test fun modelKvSetSecureEnginesDenied() = runBlocking {
        assertEquals(KV_SECURE_DENIED_ERROR, tool("kv_set").execute(secureEnginesSet()))
    }

    @Test fun modelKvExportSecureDenied() = runBlocking {
        assertEquals(KV_SECURE_DENIED_ERROR, tool("kv_export").execute(obj("namespace" to s("secure"))))
    }

    @Test fun modelNonSecureNamespaceUnaffected() = runBlocking {
        val r = tool("kv_get").execute(obj("namespace" to s("sessions"), "key" to s("s_1")))
        assertFalse(r.startsWith("ERROR:"))
        // 空存储 → found=false；门禁未介入 sessions 命名空间。
        assertNotEquals(KV_SECURE_DENIED_ERROR, r)
    }

    @Test fun bridgeGetSecureEnginesAllowed() = runBlocking {
        // 前端桥走 executeWithSecureAccess + 仅 engines 策略；空存储读取不触发 Keystore 加密。
        val r = tool("kv_get").executeWithSecureAccess(secureEnginesGet(), allowEngines)
        assertNotEquals(KV_SECURE_DENIED_ERROR, r)
        assertFalse(r.startsWith("ERROR:"))
    }

    @Test fun bridgeOtherSecureKeyStillDenied() = runBlocking {
        assertEquals(
            KV_SECURE_DENIED_ERROR,
            tool("kv_get").executeWithSecureAccess(
                obj("namespace" to s("secure"), "key" to s("token")),
                allowEngines,
            ),
        )
    }
}
