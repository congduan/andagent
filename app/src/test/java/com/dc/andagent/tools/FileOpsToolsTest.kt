package com.dc.andagent.tools

import android.content.Context
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * R59: safety-gate tests for [FileOpsTool] / [ZipTool].
 *
 * Only public behaviour is asserted (execute() over the JSON schema); the private
 * helpers (resolveFile/putFile/encrypted) are reached through the tools so the
 * tests stay valid if they are renamed.
 */
@RunWith(RobolectricTestRunner::class)
class FileOpsToolsTest {

    private lateinit var app: Context
    private lateinit var root: File

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        // ZipTool/FileOps error strings are bilingual (tr(zh, en)); pin the UI
        // language to zh AFTER application init (Application.onCreate calls
        // Lang.attach, which would overwrite an earlier setMode) so the Chinese
        // fragments asserted below are stable on any host locale.
        com.dc.andagent.i18n.Lang.setMode(app, com.dc.andagent.i18n.Lang.ZH)
        root = File(app.cacheDir, "r59").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @After fun tearDown() {
        com.dc.andagent.i18n.Lang.setMode(app, com.dc.andagent.i18n.Lang.SYSTEM)
        root.deleteRecursively()
    }

    /* ----------------------------- helpers ----------------------------- */

    private fun s(value: String) = JsonPrimitive(value)
    private fun b(value: Boolean) = JsonPrimitive(value)
    private fun obj(vararg pairs: Pair<String, JsonElement>) = JsonObject(pairs.toMap())

    private fun fileOp(name: String, args: JsonObject): String = runBlocking {
        FileOpsTool(app, name).execute(args)
    }

    private fun zipOp(name: String, args: JsonObject): String = runBlocking {
        ZipTool(name).execute(args)
    }

    private fun jsonSources(vararg paths: String): String =
        paths.joinToString(",", "[", "]") { JsonPrimitive(it).toString() }

    private fun assertErrorContains(fragment: String, result: String) {
        assertTrue("期望以 ERROR 开头，实际：$result", result.startsWith("ERROR"))
        assertTrue("期望错误信息包含「$fragment」，实际：$result", result.contains(fragment))
    }

    private fun payload(result: String): JsonObject = Json.parseToJsonElement(result).jsonObject

    private fun writeFile(name: String, content: String): File =
        File(root, name).apply {
            parentFile?.mkdirs()
            writeText(content)
        }

    private fun writeZip(target: File, vararg entries: Pair<String, String>) {
        target.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(target)).use { out ->
            entries.forEach { (entryName, content) ->
                out.putNextEntry(ZipEntry(entryName))
                out.write(content.toByteArray())
                out.closeEntry()
            }
        }
    }

    /** Flips bit 0 of the general-purpose flag of the first local file header. */
    private fun markEncrypted(src: File, dst: File) {
        val bytes = src.readBytes()
        var i = 0
        var patched = false
        while (i + 30 <= bytes.size) {
            if (bytes[i] == 0x50.toByte() && bytes[i + 1] == 0x4B.toByte() &&
                bytes[i + 2] == 0x03.toByte() && bytes[i + 3] == 0x04.toByte()
            ) {
                bytes[i + 6] = (bytes[i + 6].toInt() or 0x01).toByte()
                patched = true
                break
            }
            i++
        }
        assertTrue("测试样本未找到 local file header", patched)
        dst.writeBytes(bytes)
    }

    /* ------------------------- path resolution ------------------------- */

    @Test fun relativeAndMissingPathsAreRejected() {
        assertErrorContains("仅支持绝对文件路径", fileOp("file_info", obj("path" to s("relative/path.txt"))))
        assertErrorContains("仅支持绝对文件路径", fileOp("file_mkdir", obj("path" to s("./x"))))
        assertErrorContains("缺少 path", fileOp("file_info", obj()))
        assertErrorContains("缺少 path", fileOp("file_delete", obj("confirm" to b(true))))
    }

    @Test fun blockedSystemPrefixesAreRejected() {
        val blocked = listOf(
            "/system/build.prop",
            "/vendor/etc/hosts",
            "/proc/self/environ",
            "/dev/null",
            "/sys/kernel/mm",
            "/apex/com.android.runtime",
            "/data/data/com.dc.andagent/files/x",
        )
        blocked.forEach { path ->
            assertErrorContains("系统保护目录", fileOp("file_info", obj("path" to s(path))))
            assertErrorContains("系统保护目录", fileOp("file_mkdir", obj("path" to s(path))))
            assertErrorContains("系统保护目录", fileOp("file_delete", obj("path" to s(path), "confirm" to b(true), "recursive" to b(true))))
            assertErrorContains("系统保护目录", zipOp("zip_list", obj("path" to s(path))))
        }
    }

    @Test fun canonicalisationIsAppliedBeforeThePrefixCheck() {
        // "/proc/../tmp" canonically becomes "/tmp": ".." must be resolved
        // before the prefix check, otherwise an allowed path could masquerade
        // as a blocked one (and vice versa).
        val result = fileOp("file_info", obj("path" to s("/proc/../tmp")))
        assertFalse("规范化后不应命中保护目录：$result", result.contains("系统保护目录"))
        assertTrue("规范化后应读到 /tmp 的信息：$result", result.startsWith("{"))
        assertEquals("/tmp", payload(result).getValue("path").jsonPrimitive.content)
    }

    /* ----------------------- copy / move --------------------------------- */

    @Test fun copyRequiresConfirmToOverwrite() {
        val src = writeFile("copy-src.txt", "new")
        val dst = writeFile("copy-dst.txt", "old")

        assertErrorContains("confirm=true", fileOp("file_copy", obj("source" to s(src.path), "dest" to s(dst.path))))
        assertEquals("拒绝时不得覆盖", "old", dst.readText())

        val ok = fileOp("file_copy", obj("source" to s(src.path), "dest" to s(dst.path), "confirm" to b(true)))
        assertTrue(ok, ok.startsWith("WRITTEN"))
        assertEquals("new", dst.readText())
        assertTrue("源文件应保留", src.exists())
    }

    @Test fun copyRejectsMissingSourceAndSameSourceDest() {
        val src = writeFile("only.txt", "x")
        assertErrorContains("文件不存在", fileOp("file_copy", obj("source" to s(File(root, "nope.txt").path), "dest" to s(src.path))))
        assertErrorContains("源与目标相同", fileOp("file_copy", obj("source" to s(src.path), "dest" to s(src.path))))
    }

    @Test fun moveRemovesSourceAfterCopy() {
        val src = writeFile("move-src.txt", "payload")
        val dst = File(root, "moved/move-dst.txt")
        val ok = fileOp("file_move", obj("source" to s(src.path), "dest" to s(dst.path)))
        assertTrue(ok, ok.startsWith("WRITTEN"))
        assertFalse("移动后源应消失", src.exists())
        assertEquals("payload", dst.readText())
    }

    @Test fun copyDirectoryRecursively() {
        writeFile("tree/a.txt", "A")
        writeFile("tree/sub/b.txt", "B")
        val dst = File(root, "tree-copy")
        val ok = fileOp("file_copy", obj("source" to s(File(root, "tree").path), "dest" to s(dst.path)))
        assertTrue(ok, ok.startsWith("WRITTEN"))
        assertEquals("A", File(dst, "a.txt").readText())
        assertEquals("B", File(dst, "sub/b.txt").readText())
    }

    /* ---------------------------- delete -------------------------------- */

    @Test fun deleteRequiresExplicitConfirm() {
        val victim = writeFile("victim.txt", "keep me")
        assertErrorContains("confirm=true", fileOp("file_delete", obj("path" to s(victim.path))))
        assertErrorContains("confirm=true", fileOp("file_delete", obj("path" to s(victim.path), "confirm" to b(false))))
        assertTrue("未确认时不得删除", victim.exists())

        val ok = fileOp("file_delete", obj("path" to s(victim.path), "confirm" to b(true)))
        assertTrue(ok, ok.startsWith("DELETED"))
        assertFalse(victim.exists())
    }

    @Test fun deleteRefusesTheAppRootEvenWhenRecursive() {
        val filesDir = app.filesDir.canonicalFile
        assertTrue("前置条件：filesDir 应存在", filesDir.exists())
        val result = fileOp(
            "file_delete",
            obj("path" to s(filesDir.path), "confirm" to b(true), "recursive" to b(true)),
        )
        assertErrorContains("拒绝删除应用根目录", result)
        assertTrue("应用根目录必须仍然存在", filesDir.exists())
    }

    @Test fun deleteRefusesTheAppDataDirEvenWhenRecursive() {
        val dataDir = File(app.applicationInfo.dataDir).canonicalFile
        assertTrue("前置条件：dataDir 应存在", dataDir.exists())
        val probe = File(dataDir, "r59-data-dir-probe.txt").apply { writeText("survive") }
        try {
            val result = fileOp(
                "file_delete",
                obj("path" to s(dataDir.path), "confirm" to b(true), "recursive" to b(true)),
            )
            assertErrorContains("拒绝删除应用根目录", result)
            assertTrue("应用数据根目录必须仍然存在", dataDir.exists())
            assertTrue("dataDir 内文件不得被递归删除", probe.exists())
            assertEquals("survive", probe.readText())
        } finally {
            probe.delete()
        }
    }

    @Test fun deleteNonEmptyDirectoryNeedsRecursiveFlag() {
        val dir = File(root, "nonempty").apply { mkdirs() }
        writeFile("nonempty/child.txt", "x")

        assertErrorContains(
            "递归删除需 recursive=true",
            fileOp("file_delete", obj("path" to s(dir.path), "confirm" to b(true))),
        )
        assertTrue("未加 recursive 时目录必须保留", dir.exists())

        val ok = fileOp(
            "file_delete",
            obj("path" to s(dir.path), "confirm" to b(true), "recursive" to b(true)),
        )
        assertTrue(ok, ok.startsWith("DELETED"))
        assertFalse(dir.exists())
    }

    @Test fun emptyDirectoryCanBeDeletedWithoutRecursive() {
        val dir = File(root, "empty").apply { mkdirs() }
        val ok = fileOp("file_delete", obj("path" to s(dir.path), "confirm" to b(true)))
        assertTrue(ok, ok.startsWith("DELETED"))
        assertFalse(dir.exists())
    }

    /* ---------------------------- rename -------------------------------- */

    @Test fun renameRequiresConfirm() {
        val file = writeFile("rename-me.txt", "x")
        assertErrorContains("重命名需要 confirm=true", fileOp("file_rename", obj("path" to s(file.path), "name" to s("renamed.txt"))))
        assertTrue(file.exists())
    }

    @Test fun renameRejectsPathSeparatorsAndDotNames() {
        val file = writeFile("guard.txt", "x")
        val bad = listOf("a/b.txt", "a\\b.txt", ".", "..", "../escape.txt", "/abs.txt")
        bad.forEach { name ->
            val result = fileOp(
                "file_rename",
                obj("path" to s(file.path), "name" to s(name), "confirm" to b(true)),
            )
            assertErrorContains("路径分隔符", result)
            assertTrue("原文件应保持不变：$name", File(root, "guard.txt").exists())
        }
    }

    @Test fun renameRejectsExistingTargetAndRenamesOtherwise() {
        val file = writeFile("orig.txt", "x")
        writeFile("taken.txt", "y")
        assertErrorContains(
            "目标名称已存在",
            fileOp("file_rename", obj("path" to s(file.path), "name" to s("taken.txt"), "confirm" to b(true))),
        )

        val ok = fileOp("file_rename", obj("path" to s(file.path), "name" to s("fresh.txt"), "confirm" to b(true)))
        assertTrue(ok, ok.startsWith("WRITTEN"))
        assertFalse(file.exists())
        assertEquals("x", File(root, "fresh.txt").readText())
    }

    /* ----------------------------- mkdir -------------------------------- */

    @Test fun mkdirCreatesNestedDirectoriesAndReportsExisting() {
        val nested = File(root, "a/b/c")
        val ok = fileOp("file_mkdir", obj("path" to s(nested.path)))
        assertTrue(ok, ok.startsWith("WRITTEN"))
        assertTrue(nested.isDirectory)

        val again = fileOp("file_mkdir", obj("path" to s(nested.path)))
        assertTrue(again, again.startsWith("目录已存在"))
    }

    /* ----------------------------- hash --------------------------------- */

    @Test fun hashComputesKnownDigests() {
        val file = writeFile("hello.txt", "hello")

        val md5 = payload(fileOp("file_hash", obj("path" to s(file.path), "algorithm" to s("md5"))))
        assertEquals("MD5", md5.getValue("algorithm").jsonPrimitive.content)
        assertEquals("5d41402abc4b2a76b9719d911017c592", md5.getValue("hash").jsonPrimitive.content)
        assertEquals(5L, md5.getValue("size").jsonPrimitive.content.toLong())

        // default = sha256
        val sha256 = payload(fileOp("file_hash", obj("path" to s(file.path))))
        assertEquals("SHA-256", sha256.getValue("algorithm").jsonPrimitive.content)
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            sha256.getValue("hash").jsonPrimitive.content,
        )

        val sha1 = payload(fileOp("file_hash", obj("path" to s(file.path), "algorithm" to s("sha1"))))
        assertEquals("aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d", sha1.getValue("hash").jsonPrimitive.content)
    }

    @Test fun hashRejectsDirectoriesAndOversizeFiles() {
        val dir = File(root, "hashdir").apply { mkdirs() }
        assertErrorContains("哈希仅支持文件", fileOp("file_hash", obj("path" to s(dir.path))))

        // Sparse file: length check happens before any read.
        val big = File(root, "big.bin")
        RandomAccessFile(big, "rw").use { it.setLength(201L * 1024 * 1024) }
        assertErrorContains("超过 200MB", fileOp("file_hash", obj("path" to s(big.path))))
        big.delete()
    }

    @Test fun infoReportsMetadata() {
        writeFile("info.txt", "1234")
        val info = payload(fileOp("file_info", obj("path" to s(File(root, "info.txt").path))))
        assertEquals("info.txt", info.getValue("name").jsonPrimitive.content)
        assertEquals("false", info.getValue("isDirectory").jsonPrimitive.content)
        assertEquals("4", info.getValue("size").jsonPrimitive.content)
        assertEquals("txt", info.getValue("extension").jsonPrimitive.content)
    }

    /* --------------------------- zip_create ----------------------------- */

    @Test fun zipCreateRequiresConfirmAndSanitisedSources() {
        val source = writeFile("zsrc.txt", "data")
        val target = writeFile("exists.zip", "not a zip")

        assertErrorContains(
            "覆盖请加 confirm=true",
            zipOp("zip_create", obj("path" to s(target.path), "sources_json" to s(jsonSources(source.path)))),
        )
        assertEquals("拒绝覆盖时已有 ZIP 的字节不得改变", "not a zip", target.readText())
        assertErrorContains(
            "sources_json 为空",
            zipOp("zip_create", obj("path" to s(File(root, "empty.zip").path), "sources_json" to s("[]"))),
        )
        assertErrorContains(
            "必须是 JSON 字符串数组",
            zipOp("zip_create", obj("path" to s(File(root, "bad.zip").path), "sources_json" to s("{oops}"))),
        )

        val ok = zipOp(
            "zip_create",
            obj(
                "path" to s(File(root, "ok.zip").path),
                "sources_json" to s(jsonSources(source.path)),
                "confirm" to b(true),
            ),
        )
        assertTrue(ok, ok.startsWith("WRITTEN"))
        assertTrue(File(root, "ok.zip").length() > 0)
    }

    @Test fun zipEntryNamesContainingDotDotAreRejected() {
        val evil = writeFile("weird..name.txt", "x")
        val result = zipOp(
            "zip_create",
            obj("path" to s(File(root, "evil.zip").path), "sources_json" to s(jsonSources(evil.path))),
        )
        assertErrorContains("非法条目名", result)
        // zip_create opens the target before validating entries, so an empty
        // archive may be left behind; what matters is that no entry with ".."
        // ever made it into it.
        val leftover = File(root, "evil.zip")
        if (leftover.exists()) {
            java.util.zip.ZipFile(leftover).use { assertEquals(0, it.size()) }
        }
    }

    /* ---------------------------- zip_list ------------------------------ */

    @Test fun zipListAndExtractRoundTrip() {
        val zip = File(root, "round.zip")
        writeZip(zip, "a.txt" to "alpha", "dir/b.txt" to "beta")

        val listed = payload(zipOp("zip_list", obj("path" to s(zip.path))))
        assertEquals(2, listed.getValue("count").jsonPrimitive.content.toInt())

        val dest = File(root, "round-out")
        val ok = zipOp("zip_extract", obj("path" to s(zip.path), "dest" to s(dest.path)))
        assertTrue(ok, ok.startsWith("WRITTEN"))
        assertEquals("alpha", File(dest, "a.txt").readText())
        assertEquals("beta", File(dest, "dir/b.txt").readText())
    }

    @Test fun extractBlocksZipSlipEntries() {
        val zip = File(root, "slip.zip")
        writeZip(zip, "../escaped.txt" to "pwn")
        val dest = File(root, "slip-out")
        val result = zipOp("zip_extract", obj("path" to s(zip.path), "dest" to s(dest.path)))
        assertErrorContains("路径穿越被拦截", result)
        assertFalse("不得写出目标目录之外", File(root, "escaped.txt").exists())
    }

    @Test fun extractRequiresConfirmBeforeOverwriting() {
        val zip = File(root, "over.zip")
        writeZip(zip, "keep.txt" to "fresh")
        val dest = File(root, "over-out")

        assertTrue(zipOp("zip_extract", obj("path" to s(zip.path), "dest" to s(dest.path))).startsWith("WRITTEN"))
        assertEquals("fresh", File(dest, "keep.txt").readText())

        File(dest, "keep.txt").writeText("stale")
        val refused = zipOp("zip_extract", obj("path" to s(zip.path), "dest" to s(dest.path)))
        assertErrorContains("覆盖请加 confirm=true", refused)
        assertEquals("拒绝时不得覆盖", "stale", File(dest, "keep.txt").readText())

        val ok = zipOp(
            "zip_extract",
            obj("path" to s(zip.path), "dest" to s(dest.path), "confirm" to b(true)),
        )
        assertTrue(ok, ok.startsWith("WRITTEN"))
        assertEquals("fresh", File(dest, "keep.txt").readText())
    }

    @Test fun extractHonoursEntriesFilter() {
        val zip = File(root, "filter.zip")
        writeZip(zip, "one.txt" to "1", "two.txt" to "2")
        val dest = File(root, "filter-out")
        val only = jsonSources("two.txt")
        val ok = zipOp("zip_extract", obj("path" to s(zip.path), "dest" to s(dest.path), "entries_json" to s(only)))
        assertTrue(ok, ok.startsWith("WRITTEN"))
        assertTrue(File(dest, "two.txt").exists())
        assertFalse("未选中的条目不应解压", File(dest, "one.txt").exists())
    }

    /* --------------------------- encryption ----------------------------- */

    @Test fun encryptedArchivesAreRejectedByEveryReader() {
        val plain = File(root, "plain.zip")
        writeZip(plain, "secret.txt" to "data")
        val encrypted = File(root, "enc.zip")
        markEncrypted(plain, encrypted)

        // Control: the untouched sample is readable.
        assertTrue(zipOp("zip_list", obj("path" to s(plain.path))).startsWith("{"))

        assertErrorContains("加密", zipOp("zip_list", obj("path" to s(encrypted.path))))
        assertErrorContains("加密", zipOp("zip_extract", obj("path" to s(encrypted.path), "dest" to s(File(root, "enc-out").path))))
        assertErrorContains("加密", zipOp("zip_test", obj("path" to s(encrypted.path))))
        assertErrorContains("加密", zipOp("zip_add", obj("path" to s(encrypted.path), "sources_json" to s(jsonSources(plain.path)))))
    }

    /* ----------------------------- zip_add ------------------------------ */

    @Test fun addReplacesSameNameEntryAndKeepsOthers() {
        val zip = File(root, "add.zip")
        writeZip(zip, "same.txt" to "old", "keep.txt" to "kept")

        val updated = writeFile("incoming/same.txt", "new")
        val ok = zipOp("zip_add", obj("path" to s(zip.path), "sources_json" to s(jsonSources(updated.path))))
        assertTrue(ok, ok.startsWith("WRITTEN"))

        val listed = payload(zipOp("zip_list", obj("path" to s(zip.path))))
        assertEquals("同名条目应被替换而不是追加", 2, listed.getValue("count").jsonPrimitive.content.toInt())

        val dest = File(root, "add-out")
        assertTrue(zipOp("zip_extract", obj("path" to s(zip.path), "dest" to s(dest.path))).startsWith("WRITTEN"))
        assertEquals("new", File(dest, "same.txt").readText())
        assertEquals("kept", File(dest, "keep.txt").readText())
        assertFalse("不应残留临时文件", File(root, "add.tmp.zip").exists())
    }

    /* ----------------------------- zip_test ----------------------------- */

    @Test fun zipTestReportsCrcStatus() {
        val zip = File(root, "valid.zip")
        writeZip(zip, "v.txt" to "hello")
        val result = payload(zipOp("zip_test", obj("path" to s(zip.path))))
        assertEquals(1, result.getValue("entries").jsonPrimitive.content.toInt())
        assertEquals(0, result.getValue("crcFailures").jsonPrimitive.content.toInt())
        assertEquals("true", result.getValue("valid").jsonPrimitive.content)
    }

    /* --------------------------- size ceiling --------------------------- */

    @Test fun extractAbortsWhenUnpackedDataExceeds500MB() {
        val zip = File(root, "huge.zip")
        ZipOutputStream(FileOutputStream(zip)).use { out ->
            out.putNextEntry(ZipEntry("huge.bin"))
            val chunk = ByteArray(64 * 1024)
            var written = 0L
            while (written < 501L * 1024 * 1024) {
                out.write(chunk)
                written += chunk.size
            }
            out.closeEntry()
        }
        val dest = File(root, "huge-out")
        val result = zipOp("zip_extract", obj("path" to s(zip.path), "dest" to s(dest.path)))
        assertErrorContains("超过 500MB", result)
        val leftovers = dest.listFiles()?.map { it.name }.orEmpty()
        assertTrue("超限中止后不得留下残留输出（含 .part），实际：$leftovers", leftovers.isEmpty())
    }
}
