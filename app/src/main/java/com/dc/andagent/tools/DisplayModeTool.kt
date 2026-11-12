package com.dc.andagent.tools

import android.app.Activity
import android.content.Context
import android.hardware.display.DisplayManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * R87c：display_mode / screen_lock_now 屏幕增强刀。
 *
 * 本文件上半部是纯 JVM 可测的 [DisplayModeCodec]（不依赖 Android API，Display.Mode
 * 无法在 JVM 单测里实例化，故用 [DisplayModeSnapshot] 承接读数）；下半部是工具层
 * [DisplayModeTool]，只负责取 Display、在主线程改 window.attributes 与拼 JSON。
 */

/** 系统按窗口选择显示模式时的边界说明，工具描述与 set/reset 返回共用同一口径。 */
internal const val DISPLAY_MODE_SCOPE_NOTE =
    "preferredDisplayModeId 只影响本 App 窗口，系统级显示模式切换普通应用无公开 API（需系统签名权限），本工具不改变系统显示设置。"

/**
 * 与 android.view.Display.Mode 对应的纯数据快照。
 * Display.Mode 在 API 34 没有公开的 getRoundedRefreshRate()，故 [roundedRefreshRate]
 * 由 [refreshRate] 四舍五入得到（系统选 mode 时惯用的整数刷新率，如 60/90/120）。
 */
internal data class DisplayModeSnapshot(
    val modeId: Int,
    val width: Int,
    val height: Int,
    val refreshRate: Float,
) {
    val roundedRefreshRate: Int get() = refreshRate.roundToInt()
    val pixels: Long get() = width.toLong() * height.toLong()
}

/** action=set 的刷新率选择结果。 */
internal sealed class RefreshRateSelection {
    data class Found(
        val modeId: Int,
        val width: Int,
        val height: Int,
        val refreshRate: Float,
    ) : RefreshRateSelection()

    /** 未命中时附带全部支持的刷新率（"%.2f" 文本、去重升序）。 */
    data class NotFound(val supportedRates: List<String>) : RefreshRateSelection()
}

/**
 * display_mode 的纯函数层：JSON 化、默认 mode 启发式（历史兼容保留，status 不再据此输出
 * isDefault）、刷新率匹配、HDR 类型名映射、reset 语义。
 */
internal object DisplayModeCodec {

    // 与 Display.HdrCapabilities.HDR_TYPE_* 公开常量值对齐（不引用 Android stub，保证 JVM 单测可跑）。
    const val HDR_DOLBY_VISION = 1
    const val HDR_HDR10 = 2
    const val HDR_HLG = 3
    const val HDR_HDR10_PLUS = 4

    // 0.5Hz 容差：真机常上报 59.94/59.88 等非整数率，请求 60 应命中（90 与 60/120 的差仍远超容差）。
    private const val RATE_EPSILON = 0.5f

    /** 刷新率四舍五入到 2 位小数，作为 JSON 数值输出。 */
    fun roundRate(rate: Float): Double = (rate * 100.0).roundToInt() / 100.0

    /** "%.2f" 文本，用于报错时列出支持值与返回标签。 */
    fun rateLabel(rate: Float): String = String.format(Locale.US, "%.2f", roundRate(rate))

    fun hdrTypeName(type: Int): String? = when (type) {
        HDR_DOLBY_VISION -> "DOLBY_VISION"
        HDR_HDR10 -> "HDR10"
        HDR_HDR10_PLUS -> "HDR10_PLUS"
        HDR_HLG -> "HLG"
        else -> null
    }

    fun hdrTypeNames(types: IntArray): List<String> =
        types.asSequence().mapNotNull(::hdrTypeName).distinct().toList()

    /**
     * 启发式挑选「最高分辨率 + 最高刷新率」的 mode（历史纯函数，保留供兼容/测试）。
     * Android 公开 API 不提供系统真实默认模式，工具层 status() 不再把结果当 isDefault 输出；
     * 同分辨率取最高刷新率，并列时取最小 modeId 保证稳定。
     */
    fun defaultModeId(modes: List<DisplayModeSnapshot>): Int? = modes
        .maxWithOrNull(
            compareBy<DisplayModeSnapshot> { it.pixels }
                .thenBy { it.refreshRate }
                .thenBy { -it.modeId },
        )?.modeId

    /** 峰值刷新率 = supportedModes 中的最大刷新率（2 位小数）。 */
    fun peakRefreshRate(modes: List<DisplayModeSnapshot>): Double? =
        modes.maxOfOrNull { roundRate(it.refreshRate) }

    /** 全部支持的刷新率（去重、数值升序、"%.2f" 文本）。 */
    fun supportedRateLabels(modes: List<DisplayModeSnapshot>): List<String> = modes
        .map { roundRate(it.refreshRate) }
        .distinct()
        .sorted()
        .map { String.format(Locale.US, "%.2f", it) }

    /**
     * modes 列表 JSON 化：每个 mode 含 modeId/width/height/refreshRate/roundedRefreshRate/isDefault/isCurrent。
     * Android 公开 API 不提供系统默认模式，defaultModeId=null 时 isDefault 输出 JsonNull
     * （「未知」如实可空，review 必修3：不再把未知报成 false）。
     */
    fun modesToJson(
        modes: List<DisplayModeSnapshot>,
        currentModeId: Int,
        defaultModeId: Int?,
    ): JsonArray = buildJsonArray {
        for (m in modes) {
            add(buildJsonObject {
                put("modeId", m.modeId)
                put("width", m.width)
                put("height", m.height)
                put("refreshRate", roundRate(m.refreshRate))
                put("roundedRefreshRate", m.roundedRefreshRate)
                put("isDefault", defaultModeId?.let { JsonPrimitive(m.modeId == it) } ?: JsonNull)
                put("isCurrent", m.modeId == currentModeId)
            })
        }
    }

    /**
     * action=set：按刷新率匹配 supportedModes，容差 0.5Hz（[RATE_EPSILON]），再选当前分辨率下该刷新率的模式。
     * - 0.5Hz 容差覆盖真机常见的 59.94/59.88 等非整数率（请求 60 可命中，需 ≥0.06 容差）；
     *   90 与 60/120 相差 30Hz，远超容差，不会误匹配。
     * - 该刷新率无任何模式、或只在别的分辨率存在 → [RefreshRateSelection.NotFound]（列出支持值，不切分辨率）
     * - 命中 → [RefreshRateSelection.Found]（同分辨率多模式时取 refreshRate 最接近、并列取最小 modeId）
     * - 请求值与命中模式的实际刷新率可能相差最多 0.5Hz（如 60.49 命中 60）；
     *   工具层 set 后重读 Display.mode，把观测刷新率作为 observedRate 如实回传（读不到为 null），不虚报为请求值。
     */
    fun selectModeForRefreshRate(
        modes: List<DisplayModeSnapshot>,
        requestedRate: Float,
        currentWidth: Int,
        currentHeight: Int,
    ): RefreshRateSelection {
        val byRate = modes.filter { abs(it.refreshRate - requestedRate) < RATE_EPSILON }
        val atCurrentResolution = byRate.filter { it.width == currentWidth && it.height == currentHeight }
        if (atCurrentResolution.isEmpty()) {
            return RefreshRateSelection.NotFound(supportedRateLabels(modes))
        }
        val best = atCurrentResolution.minWithOrNull(
            compareBy<DisplayModeSnapshot> { abs(it.refreshRate - requestedRate) }.thenBy { it.modeId },
        )!!
        return RefreshRateSelection.Found(best.modeId, best.width, best.height, best.refreshRate)
    }

    /** action=reset 语义：preferredDisplayModeId = 0，交还系统自动选择。 */
    fun resetModeId(): Int = 0
}

/**
 * R87c：display_mode 工具。action=status 读刷新率/HDR 能力；action=set 按刷新率切
 * preferredDisplayModeId（窗口级）；action=reset 恢复系统自动选择。纯逻辑在
 * [DisplayModeCodec]，本类只负责取 Display、主线程改 window 与拼 JSON。
 */
class DisplayModeTool(
    private val context: Context,
    private val activityProvider: () -> Activity?,
) : Tool {
    override val name = "display_mode"
    override val description =
        "读取或设置屏幕显示模式。action=status（默认）列出支持的分辨率/刷新率模式、当前模式、峰值刷新率、HDR 能力（HDR10/HDR10+/Dolby Vision/HLG）与广色域（Android 公开 API 不提供系统默认显示模式，无 isDefault 数据）；" +
            "action=set 按刷新率（refresh_rate 参数，Hz）设置窗口显示模式偏好（系统可能不采纳，返回含观测值）；action=reset 恢复系统自动选择。" + DISPLAY_MODE_SCOPE_NOTE

    override val parameters = schemaOf(
        "action" to strParam("status（默认，读取）| set（按 refresh_rate 切换）| reset（恢复自动选择）"),
        "refresh_rate" to strParam("action=set 时必填：目标刷新率（Hz），如 120。只支持设备 supportedModes 中存在的值；只匹配当前分辨率，不会切换分辨率"),
    )

    override val destructive: Boolean = false

    override suspend fun execute(args: JsonObject): String = runCatching {
        when ((args.strArg("action") ?: "status").lowercase().trim()) {
            "status" -> status()
            "set" -> setRate(args)
            "reset" -> reset()
            else -> "ERROR: action 仅支持 status|set|reset"
        }
    }.getOrElse { "ERROR: display_mode 执行失败：${it.message ?: it.javaClass.simpleName}" }

    /* ------------------------------ reads ------------------------------ */

    private fun display(): android.view.Display? {
        val dm = context.getSystemService(android.hardware.display.DisplayManager::class.java)
        val viaManager = dm?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
        if (viaManager != null) return viaManager
        // 回落：老路径 defaultDisplay（非 Activity 上下文拿不到，返回 null 如实报错）。
        return runCatching {
            @Suppress("DEPRECATION")
            (context as? Activity)?.windowManager?.defaultDisplay
        }.getOrNull()
    }

    private fun snapshots(d: android.view.Display): List<DisplayModeSnapshot> =
        d.supportedModes.map { DisplayModeSnapshot(it.modeId, it.physicalWidth, it.physicalHeight, it.refreshRate) }

    private fun hdrNames(d: android.view.Display): List<String> {
        val caps = runCatching { d.hdrCapabilities }.getOrNull() ?: return emptyList()
        return DisplayModeCodec.hdrTypeNames(caps.supportedHdrTypes ?: IntArray(0))
    }

    private fun status(): String {
        val d = display() ?: return "ERROR: 无法获取默认 Display（DisplayManager 不可用）"
        val modes = snapshots(d)
        if (modes.isEmpty()) return "ERROR: Display 未上报 supportedModes"
        val current = runCatching { d.mode }.getOrNull()
        val hdr = hdrNames(d)
        return buildJsonObject {
            put("modeId", current?.modeId ?: -1)
            put("current", buildJsonObject {
                put("width", current?.physicalWidth ?: -1)
                put("height", current?.physicalHeight ?: -1)
                put("refreshRate", current?.refreshRate?.let { DisplayModeCodec.roundRate(it) } ?: -1.0)
            })
            put("peakRefreshRate", DisplayModeCodec.peakRefreshRate(modes) ?: -1.0)
            // 工具层不再把启发式推断当系统默认 mode 输出：defaultModeId 传 null，
            // supportedModes 里 isDefault 为 JsonNull（字段保留，语义=公开 API 无默认模式数据）。
            put("supportedModes", DisplayModeCodec.modesToJson(modes, current?.modeId ?: -1, null))
            put("defaultModeNote", "Android 公开 API 不提供系统默认显示模式；peakRefreshRate/模式列表为全量事实，无 isDefault 推断标记")
            put("hdr", buildJsonObject {
                put("supported", hdr.isNotEmpty())
                put("types", buildJsonArray { hdr.forEach { add(JsonPrimitive(it)) } })
            })
            put("wideColorGamut", runCatching { d.isWideColorGamut }.getOrDefault(false))
            put("scope", DISPLAY_MODE_SCOPE_NOTE)
        }.toString()
    }

    /* ------------------------------ writes ------------------------------ */

    private suspend fun setRate(args: JsonObject): String {
        val requested = args.strArg("refresh_rate")?.toFloatOrNull()
            ?: return "ERROR: 缺少 refresh_rate（目标刷新率，Hz）"
        if (!requested.isFinite() || requested <= 0f) return "ERROR: refresh_rate 须为正数"
        val d = display() ?: return "ERROR: 无法获取默认 Display"
        val modes = snapshots(d)
        if (modes.isEmpty()) return "ERROR: Display 未上报 supportedModes"
        val current = runCatching { d.mode }.getOrNull()
            ?: return "ERROR: 无法读取当前显示模式"
        val selection = DisplayModeCodec.selectModeForRefreshRate(
            modes, requested, current.physicalWidth, current.physicalHeight,
        )
        val modeId = when (selection) {
            is RefreshRateSelection.Found -> selection.modeId
            is RefreshRateSelection.NotFound -> return (
                "ERROR: 不支持 ${DisplayModeCodec.rateLabel(requested)}Hz（当前分辨率下无此刷新率；" +
                    "supportedModes 刷新率（Hz）：${selection.supportedRates.joinToString("/")}；" +
                    "只匹配当前分辨率，不切换分辨率）"
                )
        }
        val activity = activityProvider()
            ?: return "ERROR: 需要前台 Activity 才能设置窗口显示模式（请先打开 AndAgent 界面）"
        val applied = onMain(activity) { a ->
            val params = a.window.attributes
            params.preferredDisplayModeId = modeId
            a.window.attributes = params
            "OK"
        }
        if (applied != "OK") return applied
        // review 必修2：preferredDisplayModeId 只是「窗口偏好」，系统可能忽略或自行切换，
        // 不能把请求值虚报为已生效。set 后重读当前 mode 作为观测值（读不到如实标 null）。
        val observed = runCatching { d.mode }.getOrNull()
        return buildJsonObject {
            put("ok", true)
            put("preferredDisplayModeId", modeId)
            put("requestedRate", DisplayModeCodec.roundRate(requested))
            put(
                "observedRate",
                observed?.refreshRate?.let { JsonPrimitive(DisplayModeCodec.roundRate(it)) } ?: JsonNull,
            )
            put(
                "observedModeId",
                observed?.modeId ?: -1,
            )
            put(
                "note",
                "preferredDisplayModeId 为窗口级偏好，系统可能不采纳；observedRate 为设置后重读 Display.mode 的实际观测值（null=读不到），请以观测值为准",
            )
            put("scope", DISPLAY_MODE_SCOPE_NOTE)
        }.toString()
    }

    private suspend fun reset(): String {
        val activity = activityProvider()
            ?: return "ERROR: 需要前台 Activity 才能恢复窗口显示模式"
        val applied = onMain(activity) { a ->
            val params = a.window.attributes
            params.preferredDisplayModeId = DisplayModeCodec.resetModeId()
            a.window.attributes = params
            "OK"
        }
        if (applied != "OK") return applied
        return "RESET: preferredDisplayModeId 已清零，恢复系统自动选择。" + DISPLAY_MODE_SCOPE_NOTE
    }

    /** 主线程串行执行 window 变更；Activity 不可用时返回 ERROR。 */
    private suspend fun onMain(activity: Activity, block: (Activity) -> String): String =
        withContext(Dispatchers.Main.immediate) {
            if (activity.isFinishing || activity.isDestroyed) "ERROR: Activity 不可用"
            else runCatching { block(activity) }.getOrElse { "ERROR: ${it.message ?: it.javaClass.simpleName}" }
        }
}
