package com.dc.andagent.tools

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayOutputStream

/**
 * R91.5: 原生存放 / 编辑 SSH 凭据（凭据永不过模型）。
 *
 * 用户在系统 Activity 里输入密码 / 选择私钥文件，保存时直接进
 * [SshCredentialStore]（Android Keystore 加密）。界面上的任何内容都不会
 * 进入 WebView、对话文本或模型上下文；列表也只展示元数据。
 *
 * 编辑态只预填元数据，**不解密、不回显 secret**：密码 / 私钥留空即通过
 * [SshCredentialStore.update] 复用原有密文，填写才重新加密替换。
 *
 * 私钥通过 SAF（ACTION_OPEN_DOCUMENT）读取字节：文件路径与文件名都不显示，
 * 授权只在本次读取时使用（不持久化 URI）。
 */
class SshCredentialActivity : Activity() {

    private lateinit var store: SshCredentialStore
    private lateinit var scrollView: ScrollView
    private lateinit var formTitle: TextView
    private lateinit var formNote: TextView
    private lateinit var secretNote: TextView
    private lateinit var nicknameInput: EditText
    private lateinit var hostInput: EditText
    private lateinit var portInput: EditText
    private lateinit var userInput: EditText
    private lateinit var authGroup: RadioGroup
    private lateinit var passwordBlock: LinearLayout
    private lateinit var keyBlock: LinearLayout
    private lateinit var passwordInput: EditText
    private lateinit var keyInput: EditText
    private lateinit var keyPickButton: TextView
    private lateinit var keyStatus: TextView
    private lateinit var saveButton: TextView
    private lateinit var cancelEditButton: TextView
    private lateinit var listTitle: TextView
    private lateinit var listContainer: LinearLayout

    /** SAF 读取到的私钥内容（未落盘前的临时值）。 */
    private var pickedKey: String? = null

    /** 非 null 表示正在编辑该 nickname 的凭据（新增态为 null）。 */
    private var editingNickname: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SshCredentialStore(this)
        setContentView(buildRoot())
        refreshList()
        // 设置页「编辑」按钮 / 外部入口：只传 nickname，secret 始终留在原生侧。
        intent?.getStringExtra(EXTRA_EDIT_NICKNAME)?.let { nickname ->
            store.list().firstOrNull { it.nickname == nickname }?.let(::startEdit)
        }
    }

    /* ------------------------------- UI -------------------------------- */

    private fun buildRoot(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }

        // 固定标题栏：滚动表单时「关闭」始终可见。
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(18), dp(12), dp(14))
        }
        header.addView(TextView(this).apply {
            text = "SSH 凭据"
            textSize = 20f
            setTextColor(TEXT)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(TextView(this).apply {
            text = "✕"
            contentDescription = "关闭"
            textSize = 20f
            setTextColor(TEXT)
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setPadding(dp(12), dp(4), dp(12), dp(4))
            background = rounded(Color.TRANSPARENT, BORDER)
            setOnClickListener { finish() }
        })
        root.addView(header, matchWidth())
        root.addView(View(this).apply { setBackgroundColor(BORDER) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(28))
        }

        // ---- 录入 / 编辑表单卡片 ----
        val form = card()
        formTitle = TextView(this).apply {
            textSize = 16f
            setTextColor(TEXT)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        }
        form.addView(formTitle)
        formNote = note("")
        form.addView(formNote)

        nicknameInput = field(form, "昵称（nickname，≤32 字符，无空格）", "例如 myvps")
        hostInput = field(form, "主机", "IP 或域名")
        portInput = field(form, "端口", "22").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine()
            setText("22")
        }
        userInput = field(form, "用户名", "root")

        form.addView(sectionLabel("认证方式"))
        authGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            addView(RadioButton(this@SshCredentialActivity).apply {
                id = AUTH_PASSWORD
                text = "密码"
                setTextColor(TEXT)
                isChecked = true
            })
            addView(RadioButton(this@SshCredentialActivity).apply {
                id = AUTH_KEY
                text = "私钥"
                setTextColor(TEXT)
            })
        }
        form.addView(authGroup)

        secretNote = note("编辑模式下密码 / 私钥不会回显；留空表示保留原有凭据，填写则替换。")
        form.addView(secretNote)

        passwordBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        passwordBlock.addView(sectionLabel("密码"))
        passwordInput = styledInput("登录密码（仅保存在本机）").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
        }
        passwordBlock.addView(passwordInput, matchWidth())
        form.addView(passwordBlock)

        keyBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        keyBlock.addView(sectionLabel("私钥（认证方式选「私钥」时使用）"))
        keyPickButton = actionButton("选择私钥文件…", accent = false) { pickKeyFile() }
        keyBlock.addView(keyPickButton, matchWidth())
        keyStatus = note("未选择文件。也可直接粘贴 PEM 私钥内容。")
        keyBlock.addView(keyStatus)
        keyInput = styledInput("-----BEGIN OPENSSH PRIVATE KEY----- …", multiline = true)
        keyBlock.addView(keyInput, matchWidth())
        form.addView(keyBlock)

        authGroup.setOnCheckedChangeListener { _, checkedId ->
            val keyMode = checkedId == AUTH_KEY
            passwordBlock.visibility = if (keyMode) View.GONE else View.VISIBLE
            keyBlock.visibility = if (keyMode) View.VISIBLE else View.GONE
        }
        authGroup.check(AUTH_PASSWORD)

        val formActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        saveButton = actionButton("保存凭据", accent = true) { save() }
        formActions.addView(saveButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        cancelEditButton = actionButton("取消编辑", accent = false) { resetForm() }
        formActions.addView(cancelEditButton,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { leftMargin = dp(10) })
        form.addView(formActions, matchWidth())
        content.addView(form, matchWidth())

        // ---- 已保存凭据列表 ----
        val listHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(24), 0, dp(6))
        }
        listTitle = TextView(this).apply {
            textSize = 16f
            setTextColor(TEXT)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        }
        listHeader.addView(listTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        listHeader.addView(TextView(this).apply {
            text = "仅元数据"
            textSize = 12f
            setTextColor(MUTED)
        })
        content.addView(listHeader, matchWidth())
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(listContainer, matchWidth())

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(24), 0, 0)
        }
        bottom.addView(actionButton("清空全部凭据", accent = false) { confirmWipe() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bottom.addView(actionButton("关闭", accent = true) { finish() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { leftMargin = dp(10) })
        content.addView(bottom, matchWidth())

        scrollView = ScrollView(this).apply { setBackgroundColor(BG) }
        scrollView.addView(content)
        root.addView(scrollView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        syncEditChrome()
        return root
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(CARD, BORDER, 14)
        setPadding(dp(16), dp(14), dp(16), dp(16))
    }

    private fun rounded(fill: Int, stroke: Int, radiusDp: Int = 10): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radiusDp).toFloat()
            if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
        }

    private fun actionButton(label: String, accent: Boolean, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(if (accent) Color.WHITE else TEXT)
            background = rounded(if (accent) ACCENT else Color.TRANSPARENT,
                if (accent) Color.TRANSPARENT else BORDER)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    private fun styledInput(hintText: String, multiline: Boolean = false): EditText =
        EditText(this).apply {
            hint = hintText
            setTextColor(TEXT)
            setHintTextColor(MUTED)
            textSize = 14f
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(INPUT_BG, BORDER)
            if (multiline) {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                minLines = 3
                gravity = Gravity.TOP or Gravity.START
            } else {
                setSingleLine()
            }
        }

    private fun field(parent: LinearLayout, label: String, hintText: String): EditText {
        parent.addView(sectionLabel(label))
        val input = styledInput(hintText)
        parent.addView(input, matchWidth())
        return input
    }

    private fun sectionLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(MUTED)
        setPadding(0, dp(12), 0, dp(5))
    }

    private fun note(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(MUTED)
        setPadding(0, dp(6), 0, dp(6))
    }

    private fun matchWidth(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun matchWidthTop(top: Int): LinearLayout.LayoutParams =
        matchWidth().apply { topMargin = top }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /* ---------------------------- form state --------------------------- */

    private fun syncEditChrome() {
        val editing = editingNickname
        formTitle.text = if (editing != null) "编辑凭据" else "录入新凭据"
        formNote.text = if (editing != null) {
            "正在编辑「$editing」。可修改昵称、主机、端口、用户名与认证方式；昵称不可与已有凭据重名。"
        } else {
            "密码与私钥只在本机输入，使用 Android Keystore 加密保存；对话里只用 nickname 引用，明文永远不会进入对话或模型。"
        }
        saveButton.text = if (editing != null) "保存修改" else "保存凭据"
        cancelEditButton.visibility = if (editing != null) View.VISIBLE else View.GONE
        secretNote.visibility = if (editing != null) View.VISIBLE else View.GONE
        passwordInput.hint = if (editing != null) "留空保留原密码；填写则替换" else "登录密码（仅保存在本机）"
        if (pickedKey == null) {
            keyStatus.text = if (editing != null) "留空保留原私钥；也可选择文件或粘贴 PEM 替换。"
            else "未选择文件。也可直接粘贴 PEM 私钥内容。"
        }
    }

    private fun clearSecretInputs() {
        passwordInput.setText("")
        keyInput.setText("")
        pickedKey = null
    }

    private fun startEdit(meta: SshCredentialMeta) {
        editingNickname = meta.nickname
        nicknameInput.setText(meta.nickname)
        hostInput.setText(meta.host)
        portInput.setText(meta.port.toString())
        userInput.setText(meta.user)
        authGroup.check(if (meta.authType == "key") AUTH_KEY else AUTH_PASSWORD)
        clearSecretInputs()
        syncEditChrome()
        scrollView.post { scrollView.smoothScrollTo(0, 0) }
    }

    private fun resetForm() {
        editingNickname = null
        nicknameInput.setText("")
        hostInput.setText("")
        portInput.setText("22")
        userInput.setText("")
        authGroup.check(AUTH_PASSWORD)
        clearSecretInputs()
        syncEditChrome()
    }

    /* ------------------------------ actions ----------------------------- */

    private fun currentAuth(): String =
        if (authGroup.checkedRadioButtonId == AUTH_KEY) "key" else "password"

    private fun save() {
        val nickname = nicknameInput.text.toString().trim()
        if (!SshCredentialStore.isValidNickname(nickname)) {
            toast("昵称不能包含空白且最长 32 字符")
            return
        }
        val host = hostInput.text.toString().trim()
        if (host.isEmpty() || host.any { it.isWhitespace() }) {
            toast("请填写有效的主机")
            return
        }
        val port = portInput.text.toString().trim().toIntOrNull()
        if (port == null || port !in 1..65535) {
            toast("端口必须是 1..65535")
            return
        }
        val user = userInput.text.toString().trim()
        if (user.isEmpty() || user.any { it.isWhitespace() }) {
            toast("请填写有效的用户名")
            return
        }

        val auth = currentAuth()
        val picked = pickedKey.orEmpty()
        val pasted = keyInput.text.toString().trim()
        val rawSecret = if (auth == "key") (if (picked.isNotEmpty()) picked else pasted)
        else passwordInput.text.toString()
        if (auth == "key" && rawSecret.isNotEmpty() && !rawSecret.contains("PRIVATE KEY")) {
            toast("私钥内容无效：需要 PEM 格式（含 PRIVATE KEY）")
            return
        }
        val editing = editingNickname
        if (editing == null && rawSecret.isEmpty()) {
            toast(if (auth == "key") "请选择或粘贴私钥" else "请填写密码")
            return
        }
        // null 表示编辑态留空 → 复用原密文，不解密、不回显。
        val secret = rawSecret.takeIf { it.isNotEmpty() }

        if (editing != null) {
            persist { store.update(editing, nickname, host, port, user, auth, secret) }
            return
        }
        if (store.list().any { it.nickname == nickname }) {
            AlertDialog.Builder(this)
                .setTitle("覆盖已有凭据")
                .setMessage("凭据「$nickname」已存在，是否用本次输入覆盖？如需只改主机等信息，可取消后点击列表里的「编辑」。")
                .setPositiveButton("覆盖") { _, _ ->
                    persist { store.put(nickname, host, port, user, auth, rawSecret) }
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        persist { store.put(nickname, host, port, user, auth, rawSecret) }
    }

    private fun persist(write: () -> String) {
        runCatching(write).fold(
            onSuccess = { message ->
                toast(message, long = true)
                resetForm()
                setResult(RESULT_OK)
                refreshList()
            },
            onFailure = { toast(it.message ?: "保存失败：${it.javaClass.simpleName}", long = true) },
        )
    }

    private fun pickKeyFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        runCatching { startActivityForResult(intent, REQ_PICK_KEY) }
            .onFailure { toast("无法打开文件选择器：${it.message}") }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK_KEY || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val text = runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                var total = 0
                while (true) {
                    val read = input.read(chunk)
                    if (read <= 0) break
                    total += read
                    if (total > MAX_KEY_BYTES) error("私钥文件过大（>64KB）")
                    buffer.write(chunk, 0, read)
                }
                buffer.toString(Charsets.UTF_8.name())
            } ?: error("无法读取所选文件")
        }.getOrElse {
            toast("读取私钥失败：${it.message ?: it.javaClass.simpleName}")
            return
        }
        if (!text.contains("PRIVATE KEY")) {
            toast("所选文件不是 PEM 私钥")
            return
        }
        pickedKey = text
        // 不显示文件名 / 路径：它们同样属于敏感信息。
        keyStatus.text = "已读取私钥（${text.length} 字符，文件名与路径不显示）"
    }

    private fun refreshList() {
        listContainer.removeAllViews()
        val items = store.list()
        listTitle.text = "已保存的凭据（${items.size}）"
        if (items.isEmpty()) {
            listContainer.addView(note("尚未保存任何凭据。"))
            return
        }
        items.forEach { meta ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(CARD, BORDER, 12)
                setPadding(dp(12), dp(10), dp(8), dp(10))
            }
            val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            info.addView(TextView(this).apply {
                text = meta.nickname
                textSize = 15f
                setTextColor(TEXT)
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            })
            info.addView(TextView(this).apply {
                text = "${meta.user}@${meta.host}:${meta.port} · " +
                    (if (meta.authType == "key") "私钥" else "密码")
                textSize = 12f
                setTextColor(MUTED)
            })
            row.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(actionButton("编辑", accent = false) { startEdit(meta) })
            row.addView(actionButton("删除", accent = false) { confirmDelete(meta.nickname) })
            listContainer.addView(row, matchWidthTop(dp(8)))
        }
    }

    private fun confirmDelete(nickname: String) {
        AlertDialog.Builder(this)
            .setTitle("删除凭据")
            .setMessage("删除「$nickname」？已连接的会话不受影响，但下次连接需要重新录入。")
            .setPositiveButton("删除") { _, _ ->
                val ok = store.delete(nickname)
                toast(if (ok) "已删除「$nickname」" else "凭据不存在：$nickname")
                if (editingNickname == nickname) resetForm()
                setResult(RESULT_OK)
                refreshList()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmWipe() {
        AlertDialog.Builder(this)
            .setTitle("清空全部凭据")
            .setMessage("将删除全部 SSH 凭据，无法撤销。")
            .setPositiveButton("清空") { _, _ ->
                val count = runCatching { store.wipeAll() }
                    .getOrElse {
                        toast("清空失败：${it.message ?: it.javaClass.simpleName}")
                        return@setPositiveButton
                    }
                toast("已清空 $count 条凭据")
                resetForm()
                setResult(RESULT_OK)
                refreshList()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toast(message: String, long: Boolean = false) {
        Toast.makeText(this, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    companion object {
        /** 从设置页「编辑」进入时只传 nickname（元数据），不传任何 secret。 */
        const val EXTRA_EDIT_NICKNAME = "com.dc.andagent.ssh.EDIT_NICKNAME"

        private const val AUTH_PASSWORD = 0x5A01
        private const val AUTH_KEY = 0x5A02
        private const val REQ_PICK_KEY = 4311
        private const val MAX_KEY_BYTES = 65_536

        private val BG = Color.parseColor("#0B0D11")
        private val CARD = Color.parseColor("#151922")
        private val INPUT_BG = Color.parseColor("#10141C")
        private val BORDER = Color.parseColor("#232833")
        private val TEXT = Color.parseColor("#E6E8EF")
        private val MUTED = Color.parseColor("#9AA3B2")
        private val ACCENT = Color.parseColor("#5F7DFF")
    }
}
