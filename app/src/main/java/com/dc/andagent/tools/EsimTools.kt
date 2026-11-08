package com.dc.andagent.tools

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.euicc.DownloadableSubscription
import android.telephony.euicc.EuiccManager
import com.dc.andagent.bridge.PermissionBroker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * R122 — eSIM 管理刀（把「换卡」变成一句话）。
 *
 *  a) esim_status   eSIM 能力与 profile 清单：EuiccManager.isEuiccSupported/isEnabled、EID、
 *                   SubscriptionManager 里 isEmbedded 的在档 profile（运营商/昵称/激活态/ICCID 掩码）。
 *                   国行机型多缺失 LPA 或直接阉割 eSIM —— isEnabled=false 时如实说明，不虚报。
 *  b) esim_download EuiccManager.downloadSubscription：激活码（LPA:1$smdp$matchId）下载 profile，
 *                   可选确认码与昵称、下载后是否直接启用。
 *  c) esim_enable   EuiccManager.switchToSubscription(subId)：启用（切换到）指定 eSIM profile。
 *  d) esim_disable  switchToSubscription(INVALID_SUBSCRIPTION_ID)：停用当前 eSIM（不删档）。
 *  e) esim_delete   EuiccManager.deleteSubscription(subId)：删除 profile（不可逆，confirm=true 强闸）。
 *
 * 权限边界如实上报：download/switch/delete 要求 WRITE_EMBEDDED_SUBSCRIPTIONS（签名权限）
 * 或该 profile 的运营商特权（UICC carrier privilege），普通应用通常被拒 —— 被拒时返回
 * reason=permission_denied 与系统设置引导，不虚报操作成功。删除额外要求 confirm=true。
 */
val ESIM_TOOLS = listOf("esim_status", "esim_download", "esim_enable", "esim_disable", "esim_delete")

/* ------------------------------ pure helpers ------------------------------ */

/** 纯函数层：可 JVM 单测，不触碰 Android 服务。 */
object EsimCodec {

    /** 激活码解析结果：LPA: 前缀剥离后的原始码 + 结构化字段（best-effort）。 */
    data class ActivationCode(val raw: String, val smdpAddress: String?, val matchingId: String?)

    /**
     * 解析激活码。接受：
     *  - "LPA:1$smdp.example.com$MATCH-ID"（扫码常见的 LPA: URI）
     *  - "1$smdp.example.com$MATCH-ID"（裸激活码）
     *  - "2$..."（带 token 的变体）
     * 空白自动去除；结构不合规（无 $ 分段或空地址段）返回 null，不猜。
     */
    fun parseActivationCode(input: String): ActivationCode? {
        val trimmed = input.trim().removePrefix("LPA:").trim()
        if (trimmed.isEmpty()) return null
        val parts = trimmed.split('$')
        // 至少 1$smdp 两段：首段（AC 编号）与 SM-DP+ 地址非空；matchId 可为空（部分 SM-DP+ 允许）
        if (parts.size < 2 || parts[0].isBlank() || parts[1].isBlank()) return null
        return ActivationCode(
            raw = trimmed,
            smdpAddress = parts[1],
            matchingId = parts.getOrNull(2)?.takeIf { it.isNotBlank() },
        )
    }

    /** ICCID 隐私掩码：只留末 4 位，其余以 * 占位。 */
    fun maskIccid(iccId: String?): String? {
        if (iccId.isNullOrBlank()) return null
        val tail = iccId.takeLast(4)
        val masked = "*".repeat((iccId.length - 4).coerceAtLeast(0))
        return masked + tail
    }

    /** EuiccManager 回调 result code（EXTRA_EMBEDDED_SUBSCRIPTION_RESULT_CODE）→ 可读名。 */
    fun resultCodeName(code: Int): String = when (code) {
        0 -> "ok"                       // RESULT_OK
        1 -> "resolvable_errors"        // RESULT_RESOLVABLE_ERRORS（需用户在 LPA UI 确认）
        2 -> "non_resolvable_errors"    // RESULT_NON_RESOLVABLE_ERRORS
        else -> "unknown($code)"
    }

    /** detailed code（EXTRA_EMBEDDED_SUBSCRIPTION_DETAILED_CODE，RESULT_EMBEDDED_SUBSCRIPTION_*）→ 可读名。 */
    fun detailedCodeName(code: Int): String = when (code) {
        0 -> "ok"                               // RESULT_EMBEDDED_SUBSCRIPTION_OK
        -1 -> "generic_error"                   // ..._GENERIC_ERROR
        -2 -> "error"                           // ..._ERROR
        -3 -> "unsupported_version"             // ..._UNSUPPORTED_VERSION
        -4 -> "unsupported_dismissal"           // ..._UNSUPPORTED_DISMISSAL
        -5 -> "metadata_invalid"                // ..._METADATA_INVALID
        -6 -> "install_error"                   // ..._INSTALL_ERROR
        -7 -> "disallowed_by_policy"            // ..._DISALLOWED_BY_POLICY
        -8 -> "cancelled_by_user"               // ..._CANCELLED_BY_USER
        -9 -> "preferences_empty"               // ..._PREFERENCES_EMPTY
        Int.MIN_VALUE -> "no_detailed_code"
        else -> "unknown($code)"
    }

    /** 组装「被拒/受限」统一说明，供各写操作复用。 */
    fun restrictedHint(reason: String): String = when (reason) {
        "euicc_disabled" ->
            "本机未启用 eSIM（EuiccManager.isEnabled=false）：多数国行机型阉割 eSIM/LPA，" +
                "或厂商未提供嵌入式 SIM 服务。可在 设置 → 关于手机 里确认是否有 eSIM 相关条目。"
        "permission_denied" ->
            "eSIM 写操作要求 WRITE_EMBEDDED_SUBSCRIPTIONS（签名权限）或目标 profile 的运营商特权，" +
                "普通应用通常被拒。可改用：① 运营商官方 App / 官网二维码在系统 LPA 里激活；" +
                "② 设置 → 网络与互联网 → SIM 卡 里手动下载。"
        else -> "未知限制：$reason"
    }
}

/* -------------------------------- tool -------------------------------- */

class EsimTool(
    private val context: Context,
    private val permissions: PermissionBroker,
    override val name: String,
) : Tool {

    override val description: String = when (name) {
        "esim_status" ->
            "eSIM 能力与 profile 清单：是否支持/启用 eSIM、EID、在档 eSIM profile（运营商/昵称/激活态/" +
                "ICCID 掩码）。换卡前先看这张卡。部分国行机型阉割 eSIM，isEnabled=false 时如实说明。"
        "esim_download" ->
            "下载 eSIM profile（EuiccManager.downloadSubscription）：传 LPA:1\$smdp\$匹配ID 激活码" +
                "（运营商给的二维码内容），可选下载后是否立即启用。SM-DP+ 若要求确认码/用户同意，" +
                "系统 LPA 会以 resolvable_error 回调，需用户在其界面确认。写操作要求" +
                " WRITE_EMBEDDED_SUBSCRIPTIONS 或运营商特权，普通应用常被拒——被拒时如实报告并给出" +
                "运营商 App / 系统设置替代路径，不虚报成功。"
        "esim_enable" ->
            "启用（切换到）指定 eSIM profile（EuiccManager.switchToSubscription，sub_id 来自 esim_status）。" +
                "需要签名权限或运营商特权，被拒时如实报告。"
        "esim_disable" ->
            "停用当前 eSIM profile（不删除，switchToSubscription 到空）。需要签名权限或运营商特权，" +
                "被拒时如实报告。"
        "esim_delete" ->
            "删除 eSIM profile（EuiccManager.deleteSubscription，不可逆）。必须 confirm=true；" +
                "需要签名权限或运营商特权，被拒时如实报告。"
        else -> "eSIM 工具"
    }

    override val parameters: JsonObject = when (name) {
        "esim_status" -> schemaOf()
        "esim_download" -> schemaOf(
            "activation_code" to strParam("激活码：运营商二维码内容（LPA:1\$smdp\$匹配ID）或裸激活码 1\$smdp\$匹配ID"),
            "switch_after" to boolParam("下载成功后是否立即启用该 profile，默认 false"),
        )
        "esim_enable" -> schemaOf(
            "sub_id" to intParam("要启用的 profile 的 subscriptionId（见 esim_status）"),
        )
        "esim_disable" -> schemaOf()
        "esim_delete" -> schemaOf(
            "sub_id" to intParam("要删除的 profile 的 subscriptionId（见 esim_status）"),
            "confirm" to boolParam("必须 true：删除不可逆，无法恢复"),
        )
        else -> schemaOf()
    }

    /** 全部改设备状态或含不可逆删除，进确认闸；status 只读。 */
    override val destructive: Boolean = name != "esim_status"

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        runCatching {
            when (name) {
                "esim_status" -> status()
                "esim_download" -> download(args)
                "esim_enable" -> enable(args)
                "esim_disable" -> disable()
                "esim_delete" -> delete(args)
                else -> "ERROR: 未知 eSIM 工具 $name"
            }
        }.getOrElse { "ERROR: $name 执行失败：${it.message ?: it.javaClass.simpleName}" }
    }

    /* ------------------------------ esim_status ------------------------------ */

    private suspend fun status(): String {
        val warnings = mutableListOf<String>()
        val granted = permissions.ensure(listOf(Manifest.permission.READ_PHONE_STATE))
        if (!granted || !permissions.isGranted(Manifest.permission.READ_PHONE_STATE)) {
            warnings += "未授予 READ_PHONE_STATE：profile 清单与 EID 可能拿不到"
        }

        val mgr = euiccManager()
            ?: return buildJsonObject {
                put("ok", false)
                put("euiccSupported", false)
                put("reason", "euicc_service_missing")
                put("hint", "本机没有 EuiccManager 服务：设备不支持 eSIM，或系统未提供 LPA 实现")
                putJsonArray("warnings") { warnings.forEach { add(it) } }
            }.toString()

        // 公开 SDK 无 isEuiccSupported（@SystemApi），以 isEnabled 作为能力判定
        val euiccEnabled = runCatching { mgr.isEnabled }.getOrDefault(false)

        val eid: String? = if (permissions.isGranted(Manifest.permission.READ_PHONE_STATE)) {
            runCatching { mgr.eid }.getOrNull()
        } else {
            null
        }
        if (eid == null && euiccEnabled) {
            warnings += "EID 未取到：API 29+ 对第三方应用收紧了 EID 读取（需特权或运营商特权）"
        }

        val (embedded, totalActive) = embeddedProfiles()
        if (totalActive == 0 && euiccEnabled) {
            warnings += "当前没有任何激活的 SIM profile（物理 SIM 与 eSIM 均无）"
        }

        return buildJsonObject {
            put("ok", true)
            put("apiLevel", Build.VERSION.SDK_INT)
            put("euiccSupported", euiccEnabled)
            put("euiccEnabled", euiccEnabled)
            put("eid", eid)
            put("activeSubscriptionCount", totalActive)
            putJsonArray("profiles") { embedded.forEach { add(it) } }
            if (!euiccEnabled) {
                put("hint", EsimCodec.restrictedHint("euicc_disabled"))
            }
            if (warnings.isNotEmpty()) {
                putJsonArray("warnings") { warnings.forEach { add(it) } }
            }
        }.toString()
    }

    /* ----------------------------- esim_download ----------------------------- */

    private suspend fun download(args: JsonObject): String {
        val code = EsimCodec.parseActivationCode(args.strArg("activation_code").orEmpty())
            ?: return "ERROR: activation_code 无效：需要 LPA:1\$smdp\$匹配ID 或 1\$smdp\$匹配ID 格式的激活码"
        val switchAfter = args.boolArg("switch_after") ?: false

        val sub = DownloadableSubscription.forActivationCode(code.raw)
        // 确认码（SM-DP+ 可选 PIN）与昵称由系统 LPA 界面在下载过程中补填，
        // 公开 SDK 无 setConfirmationCode/setNickname，工具无法代填。

        return euiccOperation(timeoutMs = 180_000L, op = "download") { mgr, pi ->
            mgr.downloadSubscription(sub, switchAfter, pi)
        }
    }

    /* ------------------------- enable / disable / delete ------------------------- */

    private suspend fun enable(args: JsonObject): String {
        val subId = args.intArg("sub_id")
            ?: return "ERROR: 缺少 sub_id（见 esim_status 的 profiles[].subId）"
        return euiccOperation(timeoutMs = 60_000L, op = "enable") { mgr, pi ->
            mgr.switchToSubscription(subId, pi)
        }
    }

    private suspend fun disable(): String {
        // SubscriptionManager.INVALID_SUBSCRIPTION_ID = -1：切到「无 profile」= 停用当前 eSIM
        return euiccOperation(timeoutMs = 60_000L, op = "disable") { mgr, pi ->
            mgr.switchToSubscription(SubscriptionManager.INVALID_SUBSCRIPTION_ID, pi)
        }
    }

    private suspend fun delete(args: JsonObject): String {
        val subId = args.intArg("sub_id")
            ?: return "ERROR: 缺少 sub_id（见 esim_status 的 profiles[].subId）"
        if (args.boolArg("confirm") != true) {
            return "ERROR: 删除 eSIM profile 不可逆，必须显式传 confirm=true"
        }
        return euiccOperation(timeoutMs = 60_000L, op = "delete") { mgr, pi ->
            mgr.deleteSubscription(subId, pi)
        }
    }

    /* ------------------------------ plumbing ------------------------------ */

    private fun euiccManager(): EuiccManager? =
        context.getSystemService(EuiccManager::class.java)

    /** 一次 READ_PHONE_STATE 申请，返回（嵌入 profile JSON 列表, 激活 SIM 总数）。 */
    private fun embeddedProfiles(): Pair<List<JsonObject>, Int> {
        val subs: List<SubscriptionInfo> = try {
            context.getSystemService(SubscriptionManager::class.java)
                ?.activeSubscriptionInfoList.orEmpty()
        } catch (_: SecurityException) {
            return emptyList<JsonObject>() to 0
        } catch (_: Exception) {
            return emptyList<JsonObject>() to 0
        }
        val embedded = subs.filter { runCatching { it.isEmbedded }.getOrDefault(false) }
            .map { sub ->
                buildJsonObject {
                    put("subId", sub.subscriptionId)
                    put("slot", sub.simSlotIndex)
                    put("carrier", sub.carrierName?.toString())
                    put("displayName", sub.displayName?.toString())
                    put("isActive", sub.subscriptionId >= 0)
                    put("iccIdMasked", EsimCodec.maskIccid(runCatching { sub.iccId }.getOrNull()))
                }
            }
        return embedded to subs.size
    }

    /**
     * EuiccManager 回调式操作的统一执行器：动态注册一次性 receiver 接收 LPA 回调，
     * 超时/权限拒绝/失败码全部如实上报。result/extras 见 EuiccManager 文档。
     */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private suspend fun euiccOperation(
        timeoutMs: Long,
        op: String,
        invoke: (EuiccManager, PendingIntent) -> Unit,
    ): String {
        val mgr = euiccManager()
            ?: return errorJson(op, "euicc_service_missing", EsimCodec.restrictedHint("euicc_disabled"))
        if (!runCatching { mgr.isEnabled }.getOrDefault(false)) {
            return errorJson(op, "euicc_disabled", EsimCodec.restrictedHint("euicc_disabled"))
        }

        val action = "com.dc.andagent.esim.$op.${System.nanoTime()}"
        val pi = PendingIntent.getBroadcast(
            context,
            action.hashCode(),
            Intent(action).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val deferred = CompletableDeferred<Pair<Int, Int>>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                // 结果码经 PendingIntent.send(context, resultCode, intent) 携带（receiver 的
                // getResultCode()：0=ok, 1=resolvable, 2=error）；detailed code 走 extra。
                val rc = resultCode
                val dc = intent.getIntExtra(EuiccManager.EXTRA_EMBEDDED_SUBSCRIPTION_DETAILED_CODE, Int.MIN_VALUE)
                if (!deferred.isCompleted) deferred.complete(rc to dc)
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, IntentFilter(action))
        }
        try {
            try {
                invoke(mgr, pi)
            } catch (e: SecurityException) {
                return errorJson(op, "permission_denied", EsimCodec.restrictedHint("permission_denied") +
                    "（系统返回：${e.message ?: "SecurityException"}）")
            }
            val (rc, dc) = withTimeoutOrNull(timeoutMs) { deferred.await() }
                ?: return errorJson(op, "timeout", "LPA 在 ${timeoutMs / 1000} 秒内未回调：下载类操作可能仍在后台进行，" +
                    "稍后用 esim_status 核对实际结果，不要重复提交同一激活码")
            val ok = rc == 0 && (dc == 0 || dc == Int.MIN_VALUE)
            return buildJsonObject {
                put("ok", ok)
                put("operation", op)
                put("resultCode", rc)
                put("resultCodeName", EsimCodec.resultCodeName(rc))
                put("detailedCode", dc)
                put("detailedCodeName", EsimCodec.detailedCodeName(dc))
                if (!ok) {
                    put("hint", when {
                        rc == 1 -> "存在可解决的确认项（如用户同意书/确认码）：需在系统 LPA 界面完成确认，" +
                            "本工具无法代点系统弹窗"
                        dc == -8 -> "用户在系统界面取消了操作"
                        dc == -7 -> "被策略拒绝：profile 可能被运营商策略限制在指定设备/指定时机启用"
                        else -> "LPA 返回错误（${EsimCodec.detailedCodeName(dc)}），未执行成功"
                    })
                }
            }.toString()
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    private fun errorJson(op: String, reason: String, hint: String): String = buildJsonObject {
        put("ok", false)
        put("operation", op)
        put("reason", reason)
        put("hint", hint)
    }.toString()
}
