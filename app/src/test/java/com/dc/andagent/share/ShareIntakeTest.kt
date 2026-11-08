package com.dc.andagent.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
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
import java.io.File

/**
 * R111: 系统分享入队前的解析/落盘纯逻辑（不涉及 MainActivity 与 WebView）。
 */
@RunWith(RobolectricTestRunner::class)
class ShareIntakeTest {

    private lateinit var context: Context
    private lateinit var dir: File

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        dir = ShareIntake.directory(context)
        dir.deleteRecursively()
    }

    private fun imageFile(bytes: ByteArray = byteArrayOf(1, 2, 3, 4)): File {
        val file = File(context.cacheDir, "shared.png").apply { writeBytes(bytes) }
        return file
    }

    @Test
    fun textShareBecomesPrompt() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "  帮我把这段话翻译成英文  ")
        }
        val payload = ShareIntake.parse(intent)
        assertNotNull(payload)
        assertEquals("帮我把这段话翻译成英文", payload!!.text)
        assertNull(payload.imageUri)
    }

    @Test
    fun urlShareKeepsUrl() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "https://example.com/a?b=1")
        }
        assertEquals("https://example.com/a?b=1", ShareIntake.parse(intent)!!.text)
    }

    @Test
    fun textFallsBackToSubjectAndStripsControlChars() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "标题\u0000带控制字符")
        }
        assertEquals("标题带控制字符", ShareIntake.parse(intent)!!.text)
    }

    @Test
    fun imageShareKeepsStreamUri() {
        val uri = Uri.fromFile(imageFile())
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
        }
        val payload = ShareIntake.parse(intent)!!
        assertEquals(uri, payload.imageUri)
        assertTrue(payload.text.isEmpty())
    }

    @Test
    fun nonShareIntentIsIgnored() {
        assertNull(ShareIntake.parse(null))
        assertNull(ShareIntake.parse(Intent(Intent.ACTION_MAIN)))
        assertNull(ShareIntake.parse(Intent(Intent.ACTION_SEND).apply { type = "text/plain" }))
    }

    @Test
    fun nonImageStreamIsDropped() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, Uri.fromFile(imageFile()))
            putExtra(Intent.EXTRA_TEXT, "看这个")
        }
        val payload = ShareIntake.parse(intent)!!
        assertEquals("看这个", payload.text)
        assertNull(payload.imageUri)
    }

    @Test
    fun persistImageCopiesIntoAppStorage() {
        val uri = Uri.fromFile(imageFile(byteArrayOf(9, 8, 7)))
        val path = ShareIntake.persistImage(context, uri, "image/png")
        assertNotNull(path)
        val saved = File(path!!)
        assertTrue(saved.exists())
        assertEquals("png", saved.extension)
        assertTrue(saved.readBytes().contentEquals(byteArrayOf(9, 8, 7)))
        assertEquals(dir, saved.parentFile)
    }

    @Test
    fun persistImageRejectsOversizeStream() {
        val uri = Uri.fromFile(imageFile(ByteArray(64)))
        val path = ShareIntake.persistImage(context, uri, "image/png", maxBytes = 8)
        assertNull(path)
        assertTrue(dir.listFiles()?.isEmpty() ?: true)
    }

    @Test
    fun extensionPrefersMimeThenName() {
        // MIME 映射表由平台提供（Robolectric 只内置常用类型）：有 MIME 时优先
        // MIME，取不到才回落到原文件名。
        val fromMime = MimeTypeMap.getSingleton().getExtensionFromMimeType("image/png")
        assertEquals(fromMime?.lowercase() ?: "heic", ShareIntake.extensionFor("image/png", "photo.heic"))
        assertEquals("heic", ShareIntake.extensionFor(null, "photo.HEIC"))
        assertEquals("jpg", ShareIntake.extensionFor(null, null))
        // 非法后缀（含路径分隔/超长）一律回落 jpg，杜绝路径穿越。
        assertEquals("jpg", ShareIntake.extensionFor(null, "a./../../x"))
        assertEquals("jpg", ShareIntake.extensionFor(null, "a.toolongext"))
    }

    @Test
    fun pruneKeepsOnlyNewestFiles() {
        dir.mkdirs()
        (0 until ShareIntake.KEEP_IMAGES + 5).forEach { index ->
            File(dir, "img-$index.png").apply {
                writeBytes(byteArrayOf(1))
                setLastModified(1_000_000L + index)
            }
        }
        ShareIntake.prune(dir, ShareIntake.KEEP_IMAGES)
        val remaining = dir.listFiles()!!.map { it.name }.toSet()
        assertEquals(ShareIntake.KEEP_IMAGES, remaining.size)
        assertFalse(remaining.contains("img-0.png"))
        assertTrue(remaining.contains("img-${ShareIntake.KEEP_IMAGES + 4}.png"))
    }
}
