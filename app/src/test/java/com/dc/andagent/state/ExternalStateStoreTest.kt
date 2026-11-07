package com.dc.andagent.state

import android.content.Context
import com.dc.andagent.ui.SettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * R102：外部镜像的核心承诺——写出去的数据在私有目录被清空后还能捞回来。
 * Robolectric 下默认没有 legacy 存储权限，因此走的是降级落点；
 * 但"快照 → 丢数据 → 恢复"这条链路与生产代码完全一致。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ExternalStateStoreTest {

    private lateinit var app: Context
    private lateinit var store: ExternalStateStore

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        SettingsStore(app).model = "before"
        store = ExternalStateStore(app, SettingsStore(app))
    }

    @Test
    fun syncThenRestoreBringsBackFilesAndSettings() {
        File(app.filesDir, "cron.json").writeText("""{"tasks":[1]}""")
        val skillDir = File(app.filesDir, "skills/demo").apply { mkdirs() }
        File(skillDir, "SKILL.md").writeText("# demo")

        val synced = store.syncNow()
        assertTrue(synced, synced.startsWith("已备份"))

        // 模拟换签名重装：私有目录被清空，配置回到默认值。
        File(app.filesDir, "cron.json").delete()
        File(app.filesDir, "skills").deleteRecursively()
        SettingsStore(app).model = "after"

        val restored = store.restoreNow()
        assertTrue(restored, restored.startsWith("已从"))

        assertEquals("""{"tasks":[1]}""", File(app.filesDir, "cron.json").readText())
        assertEquals("# demo", File(app.filesDir, "skills/demo/SKILL.md").readText())
        assertEquals("before", SettingsStore(app).model)
    }

    @Test
    fun snapshotExcludesApiKey() {
        SettingsStore(app).apiKey = "sk-secret"
        val synced = store.syncNow()
        assertTrue(synced, synced.startsWith("已备份"))

        val root = Regex("\"path\":\"([^\"]*)\"").find(store.statusJson())?.groupValues?.getOrNull(1)
        val settingsFile = root?.let { File(File(it, "snapshot"), "settings.json") }
        assertTrue("快照应包含 settings.json：$settingsFile", settingsFile?.isFile == true)
        assertFalse("API Key 不应进入外部快照", settingsFile!!.readText().contains("sk-secret"))
    }

    @Test
    fun emptyInstallDoesNotOverwriteExistingSnapshot() {
        File(app.filesDir, "cron.json").writeText("old")
        assertTrue(store.syncNow().startsWith("已备份"))

        // 模拟换签名重装：私有目录清空，设置回到默认。
        File(app.filesDir, "cron.json").delete()
        SettingsStore(app).apply { model = ""; baseUrl = ""; apiKey = "" }

        val skipped = store.syncNow()
        assertTrue(skipped, skipped.startsWith("已跳过"))
        assertTrue(store.statusJson().contains("\"pendingRestore\":true"))

        assertTrue(store.restoreNow().startsWith("已从"))
        assertEquals("old", File(app.filesDir, "cron.json").readText())
    }

    @Test
    fun statusReportsDisabledAfterTurningOff() {
        store.setEnabled(false)
        assertTrue(store.statusJson().contains("\"enabled\":false"))
        assertTrue(store.syncNow().startsWith("ERROR"))
    }
}
