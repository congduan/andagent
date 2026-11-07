package com.dc.andagent.tools

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * R77: JVM tests for the QS tile config store/validation and the dynamic
 * shortcut id stability (no TileService runtime — JVM can't run it).
 */
@RunWith(RobolectricTestRunner::class)
class TileShortcutTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("andagent_tiles", Context.MODE_PRIVATE).edit().clear().commit()
    }

    // ---- TileConfig validation ----

    @Test
    fun labelValidation() {
        assertTrue(TileConfig.validLabel("打卡"))
        assertTrue(TileConfig.validLabel("a".repeat(32)))
        assertFalse(TileConfig.validLabel(""))
        assertFalse(TileConfig.validLabel("a".repeat(33)))
        assertFalse(TileConfig.validLabel("bad\nnewline"))
        assertFalse(TileConfig.validLabel("bad\u0000nul"))
    }

    @Test
    fun promptValidation() {
        assertTrue(TileConfig.validPrompt("总结剪贴板内容"))
        assertTrue(TileConfig.validPrompt("a".repeat(2000)))
        assertFalse(TileConfig.validPrompt(""))
        assertFalse(TileConfig.validPrompt("   "))
        assertFalse(TileConfig.validPrompt("a".repeat(2001)))
        assertFalse(TileConfig.validPrompt("bad\r\nprompt"))
    }

    // ---- TileStore roundtrip ----

    @Test
    fun storeRoundtrip() {
        val store = TileStore(context)
        assertNull(store.read())
        store.write("晨间打卡", "打开打卡页面并发送「打卡」")
        val config = store.read()!!
        assertEquals("晨间打卡", config.label)
        assertEquals("打开打卡页面并发送「打卡」", config.prompt)
        assertTrue(config.updatedAt > 0)
    }

    @Test
    fun storeOverwriteAndClear() {
        val store = TileStore(context)
        store.write("a", "pa")
        store.write("b", "pb")
        val config = store.read()!!
        assertEquals("b", config.label)
        assertEquals("pb", config.prompt)
        store.clear()
        assertNull(store.read())
    }

    // ---- TileTool string results ----

    @Test
    fun tileSetRejectsBadArgs() = runBlocking {
        val tool = TileTool(context, "tile_set")
        assertTrue(tool.execute(TileToolJson.args("label" to "", "prompt" to "p")).startsWith("ERROR"))
        assertTrue(tool.execute(TileToolJson.args("label" to "ok", "prompt" to "")).startsWith("ERROR"))
        assertTrue(
            tool.execute(TileToolJson.args("label" to "a".repeat(33), "prompt" to "p")).startsWith("ERROR"),
        )
    }

    @Test
    fun tileSetGetClearCycle() = runBlocking {
        val setter = TileTool(context, "tile_set")
        val getter = TileTool(context, "tile_get")
        val clearer = TileTool(context, "tile_clear")
        assertTrue(getter.execute(TileToolJson.args()).contains("\"configured\":false"))
        val setResult = setter.execute(TileToolJson.args("label" to "t1", "prompt" to "p1"))
        assertTrue(setResult.contains("t1"))
        assertTrue(getter.execute(TileToolJson.args()).contains("\"configured\":true"))
        assertTrue(getter.execute(TileToolJson.args()).contains("p1"))
        assertTrue(clearer.execute(TileToolJson.args()).isNotEmpty())
        assertTrue(getter.execute(TileToolJson.args()).contains("\"configured\":false"))
    }

    // ---- ShortcutFactory stability + shortcut_create mode arg handling ----

    @Test
    fun shortcutIdStable() {
        assertEquals(
            ShortcutFactory.id("任务", "提示词"),
            ShortcutFactory.id("任务", "提示词"),
        )
        assertTrue(ShortcutFactory.id("任务", "提示词").startsWith("task_"))
        assertEquals(ShortcutFactory.id("任务", "提示词").length, "task_".length + 12)
    }

    @Test
    fun shortcutCreateRejectsBadMode() = runBlocking {
        val tool = ShortcutTool(context, "shortcut_create")
        val result = tool.execute(TileToolJson.args("name" to "n", "prompt" to "p", "mode" to "bogus"))
        assertTrue(result.startsWith("ERROR"))
        assertTrue(result.contains("mode"))
    }

    @Test
    fun shortcutIntentEncodesPrompt() {
        val intent = ShortcutFactory.intent(context, "发送 你好&再见")
        assertEquals("andagent", intent.data?.scheme)
        assertEquals("send", intent.data?.host)
        assertEquals("发送 你好&再见", intent.data?.getQueryParameter("prompt"))
        assertEquals("1", intent.data?.getQueryParameter("auto"))
    }
}

/** Tiny JSON args helper (keeps the test file free of heavy serialization setup). */
private object TileToolJson {
    fun args(vararg pairs: Pair<String, String>): kotlinx.serialization.json.JsonObject =
        kotlinx.serialization.json.buildJsonObject {
            pairs.forEach { (k, v) -> put(k, kotlinx.serialization.json.JsonPrimitive(v)) }
        }
}
