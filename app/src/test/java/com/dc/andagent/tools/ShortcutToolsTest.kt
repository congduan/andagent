package com.dc.andagent.tools

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShortcutToolsTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun emojiBitmapHasExpectedSizeAndDrawnPixels() {
        val bitmap = ShortcutFactory.emojiBitmap("★")
        assertEquals(192, bitmap.width)
        assertEquals(192, bitmap.height)
        assertEquals(Color.TRANSPARENT, bitmap.getPixel(0, 0))
        assertTrue(bitmap.getPixel(96, 96) != Color.TRANSPARENT)
    }

    @Test fun intentEncodesPromptAndTargetsThisPackage() {
        val prompt = "打 卡 & 记录 中文?"
        val intent = ShortcutFactory.intent(context, prompt)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(context.packageName, intent.`package`)
        assertEquals("andagent://send?prompt=${Uri.encode(prompt)}&auto=1", intent.dataString)
        assertEquals(prompt, intent.data?.getQueryParameter("prompt"))
        assertEquals("1", intent.data?.getQueryParameter("auto"))
    }

    @Test fun idIsStableForSameNameAndPrompt() {
        val id = ShortcutFactory.id("打卡", "记录今天")
        assertEquals(id, ShortcutFactory.id("打卡", "记录今天"))
        assertTrue(id.matches(Regex("task_[0-9a-f]{12}")))
        assertFalse(id == ShortcutFactory.id("打卡", "记录明天"))
    }

    @Test fun storeSaveListDeleteRoundtripAndUpsert() {
        context.getSharedPreferences("andagent_shortcuts", 0).edit().clear().commit()
        val store = ShortcutStore(context)
        val original = TaskShortcut("task_test", "打卡", "记录今天", "✅", 123L)
        store.save(original)
        assertEquals(listOf(original), store.list())
        val updated = original.copy(name = "每日打卡")
        store.save(updated)
        assertEquals(listOf(updated), ShortcutStore(context).list())
        store.delete(original.id)
        assertTrue(store.list().isEmpty())
    }
}
