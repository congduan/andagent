package com.dc.andagent.tools

import android.content.Context
import android.os.Build
import android.os.PowerManager

/**
 * R124 a: 后台存活引导（电池优化白名单 + 厂商 ROM 设置路径）。
 *
 * 断连根因是 Android 进程生命周期（cached 冻结 / Doze / 厂商省电），与 UI 架构无关；
 * 本对象只做「检测 + 出文案」，不持有任何连接、不碰凭据。
 * [vendorGuide] 与 [hintFor] 是纯 JVM 逻辑，可脱离 Android 运行时单测；
 * [isIgnoringBattery] / [whitelistHint] 是其上的 Android 薄封装。
 */
object BackgroundSurvival {

    /** ssh_connect 成功后、未白名单时附在结果尾部的双语提示行。 */
    private const val HINT_LINE =
        "提示：未加入电池优化白名单，退后台可能断连。可运行 open_settings(panel=battery_whitelist) 或到系统设置关闭电池优化。 / " +
            "Tip: not exempt from battery optimization — SSH/ADB may disconnect in the background. " +
            "Run open_settings(panel=battery_whitelist) or disable battery optimization in Settings."

    /**
     * ssh_exec 检测到会话断开后的重连引导：让模型用已加密保存的凭据重连，
     * 不经过任何 password / key 传参路径（R91.5 铁律），等效自愈且不过模型凭据。
     */
    const val SSH_RECONNECT_HINT =
        "会话已断开。可用 credential=<nickname> 重新 ssh_connect（凭据已加密保存，无需重输）；" +
            "凭据只在本机加密存储，不需要重新输入密码或私钥。 / " +
            "Session dropped. Reconnect with credential=<nickname> via ssh_connect; credentials are stored encrypted on-device, no password/key re-entry."

    /** 是否已加入电池优化白名单。API 23 以下无 Doze 白名单概念，视为已豁免。 */
    fun isIgnoringBattery(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false
        } catch (e: Exception) {
            // never-crash：个别 ROM 的 PowerManager 抛异常时按「未白名单」处理，宁可多提示。
            false
        }
    }

    /** Android 薄封装：未白名单返回提示行，已白名单返回 null。 */
    fun whitelistHint(context: Context): String? = hintFor(isIgnoringBattery(context))

    /** 纯逻辑：ignoring=true → null（已白名单）；false → 双语提示行。 */
    fun hintFor(ignoringBatteryOptimizations: Boolean): String? =
        if (ignoringBatteryOptimizations) null else HINT_LINE

    /** 厂商 ROM 归一后的后台保活设置指引（中英双语）。Build.MANUFACTURER 大小写/子品牌不敏感。 */
    fun vendorGuide(manufacturer: String): String {
        val m = manufacturer.trim().lowercase()
        return when {
            m.contains("xiaomi") || m.contains("redmi") ->
                "小米 / Redmi（MIUI / HyperOS）：设置 → 应用设置 → 授权管理 → 自启动，允许 AndAgent；" +
                    "省电策略改为「无限制」。 / Xiaomi/Redmi (MIUI): allow Autostart and set the battery saver policy to 'No restrictions' for AndAgent."
            m.contains("huawei") || m.contains("honor") ->
                "华为 / 荣耀（EMUI / MagicOS）：设置 → 应用 → 启动管理，把 AndAgent 改为「手动管理」，" +
                    "勾选允许自启动、关联启动、后台活动。 / Huawei/Honor (EMUI): set Launch management to Manual and allow auto-launch, associated launch and background activity."
            m.contains("oppo") || m.contains("realme") || m.contains("oneplus") ->
                "OPPO / realme / 一加（ColorOS）：设置 → 应用管理 → 自启动，允许 AndAgent；" +
                    "电池 → 允许后台运行。 / ColorOS: allow Autostart and 'Allow background running' for AndAgent."
            m.contains("vivo") || m.contains("iqoo") ->
                "vivo / iQOO（OriginOS / Funtouch）：设置 → 电池 → 后台高耗电，允许 AndAgent 后台运行，并允许自启动。 / " +
                    "vivo: allow 'Background high power consumption' and Autostart for AndAgent."
            m.contains("samsung") ->
                "三星（One UI）：设置 → 电池 → 后台使用限制，把 AndAgent 从「休眠应用」移除并加入「永不休眠」。 / " +
                    "Samsung (One UI): remove AndAgent from 'Sleeping apps' under Battery → Background usage limits."
            else ->
                "通用：系统设置 → 应用 → AndAgent → 电池，关闭电池优化 / 允许后台运行，并允许自启动。 / " +
                    "Generic: Settings → Apps → AndAgent → Battery — disable battery optimization, allow background activity and Autostart."
        }
    }
}
