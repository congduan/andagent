package com.dc.andagent.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R96: KvTool 层 secure 命名空间访问策略的纯 JVM 测试（不依赖 Context/KvStore/Keystore）。
 * kvSecureDenied 是纯策略函数：模型/QuickJS 走默认拒绝策略，前端设置页桥走「仅 engines」
 * 例外策略；两者共用同一个 KvTool 实例，靠策略区分通道。
 */
class KvSecureAccessTest {
    /** 默认策略：拒绝一切 secure（= KvTool.execute 模型/QuickJS 通道）。 */
    private val denyAll: (String?) -> Boolean = { _ -> false }
    /** R96 前端桥例外策略：仅放行 secure/engines。 */
    private val allowEngines: (String?) -> Boolean = { key -> key == "engines" }

    private fun s(v: String) = JsonPrimitive(v)
    private fun b(v: Boolean) = JsonPrimitive(v)
    private fun obj(vararg pairs: Pair<String, JsonElement>) = JsonObject(pairs.toMap())

    private fun denied(tool: String, args: JsonObject, policy: (String?) -> Boolean) =
        kvSecureDenied(tool, args, policy)

    /* ---------------- 默认策略：secure 一律拒绝 ---------------- */

    @Test fun kvGetSecureEnginesRejectedForModel() {
        val r = denied("kv_get", obj("namespace" to s("secure"), "key" to s("engines")), denyAll)
        assertEquals(KV_SECURE_DENIED_ERROR, r)
        assertTrue(r!!.startsWith("ERROR:"))
    }

    @Test fun kvSetSecureEnginesRejectedForModel() {
        val r = denied(
            "kv_set",
            obj("namespace" to s("secure"), "key" to s("engines"), "value_json" to s("[1]")),
            denyAll,
        )
        assertEquals(KV_SECURE_DENIED_ERROR, r)
    }

    @Test fun secureDeleteKeysClearRejected() {
        for (tool in listOf("kv_delete", "kv_keys", "kv_clear")) {
            assertEquals(
                KV_SECURE_DENIED_ERROR,
                denied(tool, obj("namespace" to s("secure"), "key" to s("engines")), denyAll),
            )
        }
    }

    @Test fun kvExportSecureRejected() {
        assertEquals(KV_SECURE_DENIED_ERROR, denied("kv_export", obj("namespace" to s("secure")), denyAll))
        assertEquals(KV_SECURE_DENIED_ERROR, denied("kv_export", obj("include_secure" to b(true)), denyAll))
        // 省略 namespace 的 all 导出不含 include_secure，不触碰 secure（KvStore.export all 本就永不含 secure）。
        assertNull(denied("kv_export", obj(), denyAll))
    }

    @Test fun kvImportAndNamespacesNotGatedByPolicy() {
        // kv_import 由 parseBackup 拒绝 secure；kv_namespaces 单独隐藏 secure，二者不走本门禁。
        assertNull(denied("kv_import", obj("data_json" to s("{}")), denyAll))
        assertNull(denied("kv_namespaces", obj(), denyAll))
    }

    /* ---------------- 前端桥例外：仅 secure/engines 单键读写 ---------------- */

    @Test fun bridgeAllowsSecureEnginesRead() {
        assertNull(denied("kv_get", obj("namespace" to s("secure"), "key" to s("engines")), allowEngines))
    }

    @Test fun bridgeAllowsSecureEnginesWrite() {
        assertNull(
            denied(
                "kv_set",
                obj("namespace" to s("secure"), "key" to s("engines"), "value_json" to s("[]")),
                allowEngines,
            ),
        )
    }

    @Test fun bridgeRejectsOtherSecureKeys() {
        assertEquals(KV_SECURE_DENIED_ERROR, denied("kv_get", obj("namespace" to s("secure"), "key" to s("token")), allowEngines))
        assertEquals(
            KV_SECURE_DENIED_ERROR,
            denied("kv_set", obj("namespace" to s("secure"), "key" to s("token"), "value_json" to s("1")), allowEngines),
        )
    }

    @Test fun bridgeRejectsSecureBatchWriteDeleteClearKeys() {
        // 即使策略放行 engines，批量/删除/列举/清空也不属于「单键读写」→ 一律拒绝。
        assertEquals(KV_SECURE_DENIED_ERROR, denied("kv_set", obj("namespace" to s("secure"), "items_json" to s("{}")), allowEngines))
        assertEquals(KV_SECURE_DENIED_ERROR, denied("kv_delete", obj("namespace" to s("secure"), "key" to s("engines")), allowEngines))
        assertEquals(KV_SECURE_DENIED_ERROR, denied("kv_clear", obj("namespace" to s("secure")), allowEngines))
        assertEquals(KV_SECURE_DENIED_ERROR, denied("kv_keys", obj("namespace" to s("secure")), allowEngines))
    }

    /* ---------------- 非 secure 命名空间不受影响（sessions 等）---------------- */

    @Test fun nonSecureNamespacesNeverGated() {
        assertNull(denied("kv_get", obj("namespace" to s("sessions"), "key" to s("s_1")), denyAll))
        assertNull(denied("kv_set", obj("namespace" to s("sessions"), "key" to s("s_1"), "value_json" to s("{}")), denyAll))
        assertNull(denied("kv_get", obj("key" to s("x")), denyAll)) // 默认 namespace
        assertNull(denied("kv_get", obj("namespace" to s("default"), "key" to s("x")), denyAll))
    }
}
