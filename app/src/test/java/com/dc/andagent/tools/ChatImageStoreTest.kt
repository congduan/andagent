package com.dc.andagent.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import com.dc.andagent.share.ShareIntake
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * R88: 对话图片附件的落盘/读回/越界防护（不涉及 WebView 与模型请求）。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatImageStoreTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        ChatImageStore.dir(context).deleteRecursively()
        ShareIntake.directory(context).deleteRecursively()
    }

    private fun png(width: Int = 40, height: Int = 30): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(20, 120, 200))
        }
        val file = File(ShareIntake.directory(context).apply { mkdirs() }, "src.png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file
    }

    @Test
    fun importCompressesIntoChatImagesDir() {
        val imported = ChatImageStore.import(context, png(2000, 1500).absolutePath)
        assertTrue(imported.path.startsWith("chat-images/"))
        assertEquals("/local/${imported.path}", imported.url)
        assertTrue(maxOf(imported.width, imported.height) <= ChatImageStore.MAX_EDGE)
        val saved = File(ChatImageStore.dir(context), File(imported.path).name)
        assertTrue(saved.isFile)
        assertEquals("jpg", saved.extension)
        assertTrue(imported.bytes > 0)
    }

    @Test
    fun importRejectsPathsOutsideAppStorage() {
        val outside = File(context.cacheDir, "secret.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val failure = runCatching { ChatImageStore.import(context, outside.absolutePath) }
        assertTrue(failure.isFailure)
        val traversal = runCatching { ChatImageStore.import(context, "${ChatImageStore.dir(context)}/../x.png") }
        assertTrue(traversal.isFailure)
    }

    @Test
    fun contentUriAndOwnDirsAreAllowed() {
        assertTrue(ChatImageStore.allowedSource(context, "content://media/external/images/media/1"))
        assertTrue(ChatImageStore.allowedSource(context, File(ChatImageStore.dir(context), "a.jpg").absolutePath))
        assertTrue(ChatImageStore.allowedSource(context, File(ShareIntake.directory(context), "a.jpg").absolutePath))
        assertFalse(ChatImageStore.allowedSource(context, "/sdcard/DCIM/a.jpg"))
        assertFalse(ChatImageStore.allowedSource(context, File(context.filesDir, "chat-images/a.jpg").absolutePath))
    }

    @Test
    fun readBase64RoundTripAndTraversalGuard() {
        val imported = ChatImageStore.import(context, png().absolutePath)
        val base64 = ChatImageStore.readBase64(context, imported.path)
        assertNotNull(base64)
        assertTrue(Base64.decode(base64!!, Base64.DEFAULT).isNotEmpty())
        // 相对路径里的穿越/子目录一律拒绝。
        assertNull(ChatImageStore.readBase64(context, "chat-images/../../files/x.jpg"))
        assertNull(ChatImageStore.readBase64(context, "chat-images/sub/x.jpg"))
        assertNull(ChatImageStore.readBase64(context, "chat-images/missing.jpg"))
    }

    @Test
    fun pruneKeepsNewestFiles() {
        val dir = ChatImageStore.dir(context).apply { mkdirs() }
        repeat(25) { index ->
            File(dir, "img-$index.jpg").apply {
                writeBytes(byteArrayOf(1))
                setLastModified(1_000_000L + index)
            }
        }
        ChatImageStore.prune(dir, 5)
        val remaining = dir.listFiles()!!.map { it.name }.toSet()
        assertEquals(5, remaining.size)
        assertTrue(remaining.contains("img-24.jpg"))
        assertFalse(remaining.contains("img-0.jpg"))
    }
}
