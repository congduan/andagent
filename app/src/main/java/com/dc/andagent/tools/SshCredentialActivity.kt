package com.dc.andagent.tools

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayOutputStream

/**
 * R91.5: 原生存放 SSH 凭据（凭据永不过模型）。
 *
 * 用户在系统 Activity 里输入密码 / 选择私钥文件，保存时直接进
 * [SshCredentialStore]（Android Keystore 加密）。界面上的任何内容都不会
 * 进入 WebView、对话文本或模型上下文；列表也只展示元数据。
 *
 * 私钥通过 SAF（ACTION_OPEN_DOCUMENT）读取字节：文件路径与文件名都不显示，
 * 授权只在本次读取时使用（不持久化 URI）。
 */
class SshCredentialActivity : Activity() {

    private lateinit var store: SshCredentialStore

    private lateinit var nicknameInput: EditText
    private lateinit var hostInput: EditText
    private lateinit var portInput: EditText
    private lateinit var userInput: EditText
    private lateinit var authGroup: RadioGroup
    private lateinit var passwordInput: EditText
    private lateinit var keyInput: EditText
    private lateinit var keyPickButton: Button
    private lateinit var keyStatus: TextView
    private lateinit var listContainer: LinearLayout

    /** SAF 读取到的私钥内容（未落盘前的临时值）。 */
    private var pickedKey: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SshCredentialStore(this)
        setContentView(buildRoot())
        refreshList()
    }

    /* ------------------------------- UI -------------------------------- */

    private fun buildRoot(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        root.addView(TextView(this).apply {
            text = "SSH 凭据"
            textSize = 22f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(note(
            "密码与私钥只在本机输入，使用 Android Keystore 加密保存；" +
                "对话里只用 nickname 引用，明文永远不会进入对话或模型。",
        ))

        nicknameInput = field(root, "昵称（nickname，≤32 字符，无空格）", "例如 myvps")
        hostInput = field(root, "主机", "IP 或域名")
        portInput = field(root, "端口", "22").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("22")
        }
        userInput = field(root, "用户名", "root")

        root.addView(sectionLabel("认证方式"))
        authGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            addView(RadioButton(this@SshCredentialActivity).apply {
                id = AUTH_PASSWORD
                text = "密码"
                isChecked = true
            })
            addView(RadioButton(this@SshCredentialActivity).apply {
                id = AUTH_KEY
                text = "私钥"
            })
        }
        root.addView(authGroup)

        root.addView(sectionLabel("密码"))
        passwordInput = EditText(this).apply {
            hint = "登录密码（仅保存在本机）"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
        }
        root.addView(passwordInput, matchWidth())

        root.addView(sectionLabel("私钥（认证方式选「私钥」时使用）"))
        keyPickButton = Button(this).apply {
            text = "选择私钥文件…"
            setOnClickListener { pickKeyFile() }
        }
        root.addView(keyPickButton, matchWidth())
        keyStatus = note("未选择文件。也可直接粘贴 PEM 私钥内容。")
        root.addView(keyStatus)
        keyInput = EditText(this).apply {
            hint = "-----BEGIN OPENSSH PRIVATE KEY----- …"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
            setTextColor(Color.parseColor("#E6E8EF"))
        }
        root.addView(keyInput, matchWidth())

        authGroup.setOnCheckedChangeListener { _, checkedId ->
            val keyMode = checkedId == AUTH_KEY
            passwordInput.visibility = if (keyMode) View.GONE else View.VISIBLE
            keyPickButton.visibility = if (keyMode) View.VISIBLE else View.GONE
            keyStatus.visibility = if (keyMode) View.VISIBLE else View.GONE
            keyInput.visibility = if (keyMode) View.VISIBLE else View.GONE
        }
        authGroup.check(AUTH_PASSWORD)

        root.addView(Button(this).apply {
            text = "保存凭据"
            setOnClickListener { save() }
        }, matchWidthTop(dp(16)))

        root.addView(divider(dp(28)))
        root.addView(TextView(this).apply {
            text = "已保存的凭据（仅元数据）"
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listContainer)

        root.addView(Button(this).apply {
            text = "清空全部凭据"
            setOnClickListener { confirmWipe() }
        }, matchWidthTop(dp(20)))

        return ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0B0D11"))
            addView(root)
        }
    }

    private fun field(parent: LinearLayout, label: String, hintText: String): EditText {
        parent.addView(sectionLabel(label))
        val input = EditText(this).apply {
            hint = hintText
            setSingleLine()
        }
        parent.addView(input, matchWidth())
        return input
    }

    private fun sectionLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(Color.parseColor("#9AA3B2"))
        setPadding(0, dp(12), 0, dp(4))
    }

    private fun note(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(Color.parseColor("#9AA3B2"))
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun divider(top: Int): View = View(this).apply {
        setBackgroundColor(Color.parseColor("#232833"))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = top
        }
    }

    private fun matchWidth(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun matchWidthTop(top: Int): LinearLayout.LayoutParams =
        matchWidth().apply { topMargin = top }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

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
        val secret = if (auth == "key") {
            val pasted = keyInput.text.toString().trim()
            val picked = pickedKey.orEmpty()
            (if (picked.isNotEmpty()) picked else pasted).also {
                if (!it.contains("PRIVATE KEY")) {
                    toast("私钥内容无效：需要 PEM 格式（含 PRIVATE KEY）")
                    return
                }
            }
        } else {
            passwordInput.text.toString().also {
                if (it.isEmpty()) {
                    toast("请填写密码")
                    return
                }
            }
        }

        val existing = store.list().any { it.nickname == nickname }
        if (existing) {
            AlertDialog.Builder(this)
                .setTitle("覆盖已有凭据")
                .setMessage("凭据「$nickname」已存在，是否用本次输入覆盖？")
                .setPositiveButton("覆盖") { _, _ -> persist(nickname, host, port, user, auth, secret) }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        persist(nickname, host, port, user, auth, secret)
    }

    private fun persist(nickname: String, host: String, port: Int, user: String, auth: String, secret: String) {
        val message = runCatching { store.put(nickname, host, port, user, auth, secret) }
            .getOrElse { "保存失败：${it.message ?: it.javaClass.simpleName}" }
        toast(message, long = true)
        if (message.startsWith("已保存")) {
            passwordInput.setText("")
            keyInput.setText("")
            pickedKey = null
            keyStatus.text = "未选择文件。也可直接粘贴 PEM 私钥内容。"
            setResult(RESULT_OK)
            refreshList()
        }
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
        if (items.isEmpty()) {
            listContainer.addView(note("尚未保存任何凭据。"))
            return
        }
        items.forEach { meta ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, dp(6))
            }
            row.addView(TextView(this).apply {
                text = "${meta.nickname}\n${meta.user}@${meta.host}:${meta.port} · ${meta.authType}"
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(Button(this).apply {
                text = "删除"
                setOnClickListener { confirmDelete(meta.nickname) }
            })
            listContainer.addView(row)
            listContainer.addView(divider(0))
        }
    }

    private fun confirmDelete(nickname: String) {
        AlertDialog.Builder(this)
            .setTitle("删除凭据")
            .setMessage("删除「$nickname」？已连接的会话不受影响，但下次连接需要重新录入。")
            .setPositiveButton("删除") { _, _ ->
                val ok = store.delete(nickname)
                toast(if (ok) "已删除「$nickname」" else "凭据不存在：$nickname")
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
        private const val AUTH_PASSWORD = 0x5A01
        private const val AUTH_KEY = 0x5A02
        private const val REQ_PICK_KEY = 4311
        private const val MAX_KEY_BYTES = 65_536
    }
}
