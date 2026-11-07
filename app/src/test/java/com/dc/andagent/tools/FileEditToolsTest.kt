package com.dc.andagent.tools

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.dc.andagent.bridge.PermissionBroker
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * R-level file tooling: nested paths, idempotent overwrite, paging reads and
 * the `file_edit` literal-replacement contract.
 *
 * Only public behaviour (execute() over the JSON schema) is asserted. All cases
 * use `dir=app_private` so the suite never depends on MediaStore state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FileEditToolsTest {

    private lateinit var app: Context
    private lateinit var writeTool: FileWriteTool
    private lateinit var readTool: FileReadTool
    private lateinit var editTool: FileEditTool

    private val privateRoot: File get() = File(app.filesDir, "andagent")

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        privateRoot.deleteRecursively()

        val controller = Robolectric.buildActivity(ComponentActivity::class.java)
        val activity = controller.get()
        // Registration must happen before the lifecycle reaches STARTED.
        val launcher = activity.registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { }
        controller.setup()
        writeTool = FileWriteTool(app, PermissionBroker(activity, launcher))
        readTool = FileReadTool(app)
        editTool = FileEditTool(app)
    }

    /* ----------------------------- helpers ----------------------------- */

    private fun s(value: String): JsonPrimitive = JsonPrimitive(value)
    private fun i(value: Int): JsonPrimitive = JsonPrimitive(value)
    private fun b(value: Boolean): JsonPrimitive = JsonPrimitive(value)
    private fun obj(vararg pairs: Pair<String, JsonElement>) = JsonObject(pairs.toMap())

    private fun write(name: String, content: String): String = runBlocking {
        writeTool.execute(obj("dir" to s("app_private"), "name" to s(name), "content" to s(content)))
    }

    private fun read(vararg pairs: Pair<String, JsonElement>): JsonObject =
        Json.parseToJsonElement(runBlocking { readTool.execute(JsonObject(pairs.toMap())) }).jsonObject

    private fun edit(old: String, new: String, vararg extra: Pair<String, JsonElement>): String =
        runBlocking {
            editTool.execute(
                JsonObject(
                    mapOf(
                        "dir" to s("app_private"),
                        "name" to s("doc.md"),
                        "old_string" to s(old),
                        "new_string" to JsonPrimitive(new),
                    ) + extra.toMap(),
                ),
            )
        }

    private fun doc(): File = File(privateRoot, "doc.md")

    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.int(key: String) = getValue(key).jsonPrimitive.int
    private fun JsonObject.bool(key: String) = getValue(key).jsonPrimitive.boolean

    /* ------------------------------ write ------------------------------ */

    @Test fun writeCreatesNestedPath() {
        val result = write("notes/2026/a.md", "hello")
        assertTrue(result, result.startsWith("{"))
        val target = File(privateRoot, "notes/2026/a.md")
        assertTrue("nested file should exist", target.isFile)
        assertEquals("hello", target.readText())
        assertFalse(Json.parseToJsonElement(result).jsonObject.bool("overwritten"))
    }

    @Test fun writeOverwritesInsteadOfDuplicating() {
        write("a.md", "one")
        val second = Json.parseToJsonElement(write("a.md", "two")).jsonObject
        assertTrue(second.bool("overwritten"))

        val files = privateRoot.walkTopDown().filter { it.isFile }.toList()
        assertEquals("rewriting must not create a copy: $files", 1, files.size)
        assertEquals("two", files.first().readText())
    }

    @Test fun writeRejectsTraversal() {
        val result = write("../escape.txt", "x")
        assertTrue(result, result.startsWith("ERROR:"))
        assertFalse(File(app.filesDir, "escape.txt").exists())
    }

    /* ------------------------------- read ------------------------------ */

    @Test fun readReturnsLineWindow() {
        write("doc.md", (1..10).joinToString("\n") { "line$it" })

        val page = read(
            "dir" to s("app_private"),
            "name" to s("doc.md"),
            "offset" to i(3),
            "limit" to i(3),
        )
        assertEquals(3, page.int("returnedLines"))
        assertTrue(page.bool("hasMore"))
        assertEquals(6, page.int("nextOffset"))

        val content = page.str("content")
        assertTrue("expected line-number prefix: $content", content.startsWith("     3\tline3"))
        assertTrue(content.endsWith("line5"))
    }

    @Test fun readLastPageReportsNoMore() {
        write("doc.md", (1..5).joinToString("\n") { "line$it" })
        val page = read("dir" to s("app_private"), "name" to s("doc.md"), "offset" to i(4))
        assertEquals(2, page.int("returnedLines"))
        assertFalse(page.bool("hasMore"))
        assertFalse(page.containsKey("nextOffset"))
    }

    @Test fun readOffsetPastEndFails() {
        write("doc.md", "only")
        val result = runBlocking {
            readTool.execute(
                obj("dir" to s("app_private"), "name" to s("doc.md"), "offset" to i(9)),
            )
        }
        assertTrue(result, result.startsWith("ERROR:") && result.contains("超出文件行数"))
    }

    @Test fun readRejectsBinary() {
        privateRoot.mkdirs()
        File(privateRoot, "bin.dat").writeBytes(byteArrayOf(0, 1, 2, 3, 4))
        val result = runBlocking {
            readTool.execute(obj("dir" to s("app_private"), "name" to s("bin.dat")))
        }
        assertTrue(result, result.contains("二进制"))
    }

    /* ------------------------------- edit ------------------------------ */

    @Test fun editReplacesUniqueOccurrence() {
        write("doc.md", "alpha\nbeta\ngamma")
        val result = edit("beta", "BETA")
        val json = Json.parseToJsonElement(result).jsonObject

        assertEquals(1, json.int("replaced"))
        assertEquals(2, json.int("firstChangedLine"))
        assertEquals("alpha\nBETA\ngamma", doc().readText())
    }

    @Test fun editRefusesAmbiguousMatch() {
        write("doc.md", "x\nx\nx")
        val result = edit("x", "y")

        assertTrue(result, result.startsWith("ERROR:"))
        assertTrue(result, result.contains("3 次") && result.contains("2, 3"))
        assertEquals("x\nx\nx", doc().readText())
    }

    @Test fun editReplaceAllOptIn() {
        write("doc.md", "x\nx\nx")
        val result = edit("x", "y", "replace_all" to b(true))
        val json = Json.parseToJsonElement(result).jsonObject

        assertEquals(3, json.int("replaced"))
        assertEquals("y\ny\ny", doc().readText())
    }

    @Test fun editMissingStringFails() {
        write("doc.md", "alpha")
        val result = edit("nope", "x")
        assertTrue(result, result.startsWith("ERROR:") && result.contains("不存在 old_string"))
        assertEquals("alpha", doc().readText())
    }

    @Test fun editAcceptsLineNumberedPaste() {
        write("doc.md", "alpha\nbeta\n")
        val result = edit("     2\tbeta", "BETA")
        assertTrue(result, result.startsWith("{"))
        assertEquals("alpha\nBETA\n", doc().readText())
    }

    @Test fun editDeletesWhenNewStringEmpty() {
        write("doc.md", "a\nb\nc")
        val result = edit("b\n", "")
        assertTrue(result, result.startsWith("{"))
        assertEquals("a\nc", doc().readText())
    }

    @Test fun editRejectsIdenticalStrings() {
        write("doc.md", "alpha")
        val result = edit("alpha", "alpha")
        assertTrue(result, result.startsWith("ERROR:") && result.contains("相同"))
    }
}
