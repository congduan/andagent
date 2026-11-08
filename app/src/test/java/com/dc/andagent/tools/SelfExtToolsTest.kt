package com.dc.andagent.tools

import android.content.Context
import com.dc.andagent.script.QuickJsEngine
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.Executors

/**
 * 自我扩展（P1）：SelfExtManager 的注册/持久化/门控行为。
 * JVM 单测没有 QuickJS 原生库，因此 create() 的成功路径用直接落盘 tool.json+main.js 模拟；
 * 引擎相关的校验只断言"报错不崩溃"。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SelfExtToolsTest {

    private lateinit var app: Context
    private lateinit var tools: MutableMap<String, Tool>
    private lateinit var manager: SelfExtManager
    private val executor = Executors.newSingleThreadExecutor()

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        tools = java.util.concurrent.ConcurrentHashMap()
        val engine = QuickJsEngine(tools, executor, app)
        manager = SelfExtManager(app, tools, engine)
    }

    private val echoManifest = """{"name":"my_echo","description":"回显测试","parameters":{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]},"allow":["kv_get"],"version":1,"created":"2026-01-01T00:00:00Z","reason":"测试"}"""
    private val echoSource = "exports.tools=[{name:\"run\",handler:function(args){return {echo:args.text};}}];"

    private fun installTool(name: String = "my_echo", manifest: String = echoManifest, source: String = echoSource) {
        val dir = File(app.filesDir, "selfext/$name").apply { mkdirs() }
        File(dir, "tool.json").writeText(manifest, Charsets.UTF_8)
        File(dir, "main.js").writeText(source, Charsets.UTF_8)
    }

    @Test
    fun persistedToolRoundTripsThroughReload() {
        installTool()
        manager.reload()
        assertTrue(tools.containsKey("my_echo"))
        assertTrue(manager.enabled("my_echo"))
        val info = Json.parseToJsonElement(manager.info("my_echo")).jsonObject
        assertEquals("回显测试", info["description"]!!.jsonPrimitive.content)
        assertEquals(1, info["version"]!!.jsonPrimitive.content.toInt())
        assertTrue(info["allow"].toString().contains("kv_get"))

        // 停用 → 注册表移除；启用 → 恢复
        manager.enable("my_echo", false)
        assertFalse(tools.containsKey("my_echo"))
        assertFalse(manager.enabled("my_echo"))
        manager.enable("my_echo", true)
        assertTrue(tools.containsKey("my_echo"))

        // 执行路径：JVM 无原生库，evalSkill 以 ERROR 字符串失败但不崩溃
        val tool = tools.getValue("my_echo")
        val result = runBlocking { tool.execute(buildJsonObject { put("text", JsonPrimitive("hi")) }) }
        assertTrue(result.startsWith("ERROR"))
    }

    @Test
    fun invalidManifestIsSkippedNotCrashing() {
        installTool(name = "my_bad", manifest = """{"name":"echo","description":"名字不合规"}""")
        installTool(name = "my_reserved", manifest = """{"name":"my_tool_create","description":"保留名"}""")
        installTool(name = "orphan", manifest = """{"name":"my_orphan","description":"缺 main.js"}""")
        File(app.filesDir, "selfext/orphan/main.js").delete()
        manager.reload()
        assertNull(tools["echo"])
        assertNull(tools["my_bad"])
        assertNull(tools["my_tool_create"])
        assertNull(tools["my_orphan"])
        assertTrue(tools.containsKey("my_echo").not())
    }

    @Test
    fun createRejectsBadNamesReservedNamesAndMissingDescription() {
        for (bad in listOf("echo", "MY_X", "my_", "my_大写", "my_tool_list")) {
            assertTrue(runCatching { manager.create(bad, "d", "var x=1", null, null, null) }.isFailure)
        }
        assertTrue(runCatching { manager.create("my_ok", "", "var x=1", null, null, null) }.isFailure)
    }

    @Test
    fun createRejectsForbiddenOrUnknownAllowlist() {
        // 禁止前缀/递归工具/未知工具：在引擎校验之前就应被拒绝
        for (allow in listOf("skill_install", "my_tool_delete", "js_eval", "cron_create", "no_such_tool_xyz")) {
            val result = runCatching { manager.create("my_probe", "d", "var x=1", allow, null, null) }
            assertTrue(allow, result.isFailure)
        }
    }

    @Test
    fun deleteRequiresConfirmAndRemovesPersistedDir() {
        installTool()
        manager.reload()
        assertTrue(runCatching { manager.delete("my_echo", confirm = false) }.isFailure)
        manager.delete("my_echo", confirm = true)
        assertFalse(tools.containsKey("my_echo"))
        assertFalse(File(app.filesDir, "selfext/my_echo").exists())
        manager.reload()
        assertFalse(tools.containsKey("my_echo"))
    }

    @Test
    fun metaToolExecuteReturnsErrorStringsInsteadOfThrowing() = runBlocking {
        val tool = SelfExtTool(manager, "my_tool_create")
        assertTrue(tool.execute(buildJsonObject { put("name", JsonPrimitive("bad name")) }).startsWith("ERROR"))
        assertTrue(SelfExtTool(manager, "my_tool_update").execute(buildJsonObject {
            put("name", JsonPrimitive("my_echo"))
        }).contains("confirm"))
        assertTrue(SelfExtTool(manager, "my_tool_delete").execute(buildJsonObject {
            put("name", JsonPrimitive("my_echo"))
        }).contains("confirm"))
        assertTrue(SelfExtTool(manager, "my_tool_read").execute(buildJsonObject {
            put("name", JsonPrimitive("my_echo"))
        }).startsWith("ERROR"))
    }
}
