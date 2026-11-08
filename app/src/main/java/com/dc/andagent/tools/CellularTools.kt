package com.dc.andagent.tools

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.TrafficStats
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.telephony.AccessNetworkConstants
import android.telephony.CellIdentityGsm
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellIdentityTdscdma
import android.telephony.CellIdentityWcdma
import android.telephony.CellInfo
import android.telephony.CellInfoCdma
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoTdscdma
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.CellSignalStrengthTdscdma
import android.telephony.NetworkScan
import android.telephony.NetworkScanRequest
import android.telephony.RadioAccessSpecifier
import android.telephony.ServiceState
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.telephony.TelephonyScanManager
import com.dc.andagent.bridge.PermissionBroker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.Executor
import kotlin.math.round

/**
 * R113 — 蜂窝深度刀（R85 c 的深化版）。
 *
 *  a) cell_info      CellInfoList 全量诊断：按 SIM 列出主服/邻区（GSM/WCDMA/TD-SCDMA/ CDMA/LTE/NR），
 *                    含 RSRP/RSRQ/RSSNR/SS-RSRP/SS-RSRQ/SS-SINR、CI/PCI/TAC/EARFCN/NR-ARFCN、
 *                    LTE 真实信道带宽、NR 频段与频率换算、信号质量评级。
 *  b) cell_scan      TelephonyManager.requestNetworkScan 主动邻区扫描（Android 10+）。
 *                    该系统接口要求 MODIFY_PHONE_STATE（签名权限）或运营商特权，普通应用会被拒；
 *                    被拒时如实返回 capability 结论并回落到 getAllCellInfo 的缓存邻区，不虚报扫描结果。
 *  c) nr_bandwidth   5G NR 深度：NR 频段（n41/n78…）、NR-ARFCN→中心频率、FR1/FR2、SA/NSA 判定、
 *                    LTE 锚点带宽。NR 实时信道带宽不在公开 API 内，工具给出频段上限估算并标 estimate。
 *  d) data_policy    流量配额：Data Saver、NetworkPolicyManager 配额（系统权限不足时如实报 denied）、
 *                    NetworkStatsManager 周期用量、Top 应用、给定套餐额度时的剩余与耗尽预测。
 *
 * 分工：telephony_info（R12）只给运营商/网络类型；data_usage（R12）只给 TrafficStats 累计值。
 * 本刀面向「信号质量诊断 / 邻区审计 / 5G 能力 / 流量配额」四类深度问题。
 */
val CELLULAR_TOOLS = listOf("cell_info", "cell_scan", "nr_bandwidth", "data_policy")

/* ------------------------------ pure helpers ------------------------------ */

/** 纯函数层：可 JVM 单测，不触碰 Android 服务。 */
object CellularCodec {

    /** TelephonyManager.NETWORK_TYPE_* → 可读名（公开 API 无 getNetworkTypeName，自己映射）。 */
    fun networkTypeName(type: Int): String = when (type) {
        1 -> "gprs"
        2 -> "edge"
        3 -> "umts"
        4 -> "cdma"
        5 -> "evdo_0"
        6 -> "evdo_a"
        7 -> "1xrtt"
        8 -> "hsdpa"
        9 -> "hsupa"
        10 -> "hspa"
        11 -> "iden"
        12 -> "evdo_b"
        13 -> "lte"
        14 -> "ehrpd"
        15 -> "hspap"
        16 -> "gsm"
        17 -> "td_scdma"
        18 -> "iwlan"
        19 -> "lte_ca"
        20 -> "nr"
        else -> "unknown"
    }

    /** AccessNetworkConstants.AccessNetworkType.* → 可读名。 */
    fun accessNetworkName(ran: Int): String = when (ran) {
        1 -> "geran"
        2 -> "utran"
        3 -> "eutran"
        4 -> "cdma2000"
        5 -> "iwlan"
        6 -> "ngran"
        else -> "unknown"
    }

    fun connectionStatusName(status: Int?): String? = when (status) {
        null, CELL_UNAVAILABLE -> null
        0 -> "none"
        1 -> "primary_serving"
        2 -> "secondary_serving"
        else -> "unknown"
    }

    /** 3GPP NR 频段号 → n## 标签。 */
    fun nrBandLabel(band: Int): String = if (band <= 0) "n?" else "n$band"

    /**
     * 频段上限信道带宽（3GPP TS 38.101-1/-2 各频段支持的最大信道带宽，MHz）。
     * 注意：这是频段能力上限，不是当前实际占用带宽——公开 API 不暴露实时 NR 信道带宽。
     */
    fun nrBandMaxBandwidthMhz(band: Int): Int? = when (band) {
        1, 2, 5, 7, 8, 18, 24, 25, 29, 30, 34, 38, 51, 53, 65, 74, 75, 76, 81, 82, 84,
        85, 89, 91, 92, 93, 94, 95, 97, 98, 99, 100, 101, 102, 104, 105, 106, 107 -> 20
        12 -> 15
        13, 14 -> 10
        3 -> 35
        26 -> 15
        28, 80, 83 -> 30
        39, 66, 86 -> 40
        20, 40, 41, 46, 48, 77, 78, 79, 90, 96 -> 100
        50 -> 60
        70 -> 30
        71 -> 35
        257, 258, 259, 260, 261, 262, 263 -> 400
        else -> null
    }

    /**
     * NR-ARFCN → 下行中心频率（MHz），按 3GPP TS 38.104 表 5.4.2.1-1：
     * 0..599999 → 5 kHz 步进（0–3000MHz）；600000..2016666 → 15 kHz 步进；
     * 2016667..3279165 → 60 kHz 步进。超出范围返回 null。
     */
    fun nrArfcnToMhz(arfcn: Int): Double? {
        if (arfcn <= 0) return null
        val mhz = when {
            arfcn <= 599_999 -> arfcn * 0.005
            arfcn <= 2_016_666 -> 3000.0 + (arfcn - 600_000) * 0.015
            arfcn <= 3_279_165 -> 24_250.08 + (arfcn - 2_016_667) * 0.06
            else -> return null
        }
        return round3(mhz)
    }

    /** FR1（≤7125MHz）/ FR2（毫米波）。 */
    fun frequencyRange(mhz: Double): String = if (mhz < 7125.0) "FR1" else "FR2"

    /** LTE 频段下行参数（F_DL_low MHz, N_Offs_DL），TS 36.101 表 5.7.3-1 常用频段子集。 */
    private val LTE_DL: Map<Int, Pair<Double, Int>> = mapOf(
        1 to (2110.0 to 0),
        2 to (1930.0 to 600),
        3 to (1805.0 to 1200),
        4 to (2110.0 to 1950),
        5 to (869.0 to 2400),
        7 to (2620.0 to 2750),
        8 to (925.0 to 3450),
        12 to (729.0 to 5010),
        13 to (746.0 to 5180),
        17 to (734.0 to 5730),
        18 to (860.0 to 5850),
        19 to (875.0 to 6000),
        20 to (791.0 to 6150),
        25 to (1930.0 to 8040),
        26 to (859.0 to 8690),
        28 to (758.0 to 9210),
        30 to (2350.0 to 9770),
        34 to (2010.0 to 36200),
        38 to (2570.0 to 37750),
        39 to (1880.0 to 38250),
        40 to (2300.0 to 38650),
        41 to (2496.0 to 39650),
        42 to (3400.0 to 41590),
        66 to (2110.0 to 66436),
    )

    /** LTE EARFCN + 频段 → 下行中心频率（MHz）；频段未知或不在表内返回 null。 */
    fun lteEarfcnToMhz(earfcn: Int, band: Int?): Double? {
        if (earfcn <= 0) return null
        val entry = band?.let { LTE_DL[it] } ?: return null
        return round3(entry.first + 0.1 * (earfcn - entry.second))
    }

    fun lteBandWidthMhz(khz: Int?): Double? {
        val v = khz?.takeIf { it > 0 } ?: return null
        return round3(v / 1000.0)
    }

    /** 信号质量分档（沿用业界常用阈值）。 */
    fun rsrpLabel(dbm: Int): String = when {
        dbm >= -80 -> "excellent"
        dbm >= -90 -> "good"
        dbm >= -100 -> "fair"
        dbm >= -110 -> "poor"
        else -> "very_poor"
    }

    fun rsrqLabel(db: Int): String = when {
        db >= -10 -> "excellent"
        db >= -15 -> "good"
        db >= -20 -> "fair"
        else -> "poor"
    }

    fun sinrLabel(db: Int): String = when {
        db >= 20 -> "excellent"
        db >= 13 -> "good"
        db >= 0 -> "fair"
        else -> "poor"
    }

    fun levelName(level: Int?): String? = when (level) {
        null -> null
        0 -> "none"
        1 -> "poor"
        2 -> "moderate"
        3 -> "good"
        4 -> "great"
        else -> null
    }

    private fun rank(label: String?): Int? = when (label) {
        "excellent" -> 5
        "good" -> 4
        "fair" -> 3
        "poor" -> 2
        "very_poor" -> 1
        else -> null
    }

    private fun label(rank: Int): String = when (rank) {
        5 -> "excellent"
        4 -> "good"
        3 -> "fair"
        2 -> "poor"
        else -> "very_poor"
    }

    /** 综合结论：取各指标里最差的一档（RSRP/RSRQ/SINR 任一崩都不用 good 骗人）。 */
    fun overallLabel(vararg labels: String?): String? {
        val ranks = labels.mapNotNull { rank(it) }
        if (ranks.isEmpty()) return null
        return label(ranks.min())
    }

    /** NetworkScan 错误码 → 可读名。 */
    fun scanErrorName(code: Int): String = when (code) {
        0 -> "success"
        1 -> "modem_error"
        2 -> "invalid_scan"
        3 -> "modem_unavailable"
        4 -> "unsupported"
        10000 -> "radio_interface_error"
        10001 -> "invalid_scan_id"
        10002 -> "interrupted"
        else -> "unknown_$code"
    }

    /** 单次流量配额评估。 */
    data class QuotaProjection(
        val usedBytes: Long,
        val limitBytes: Long,
        val percentUsed: Double,
        val remainingBytes: Long,
        val dailyAvgBytes: Long,
        /** 剩余天数（按窗口内日均用量线性外推）；日均 0 或额度已用完为 null / 0。 */
        val daysUntilLimit: Double?,
        val level: String,
    )

    fun quotaProjection(usedBytes: Long, limitBytes: Long?, windowDays: Int): QuotaProjection? {
        val limit = limitBytes?.takeIf { it > 0 } ?: return null
        val days = windowDays.coerceAtLeast(1)
        val used = usedBytes.coerceAtLeast(0)
        val percent = used * 100.0 / limit
        val remaining = (limit - used).coerceAtLeast(0)
        val daily = used / days
        val daysUntil = when {
            remaining == 0L -> 0.0
            daily <= 0L -> null
            else -> round(remaining.toDouble() / daily * 100.0) / 100.0
        }
        val level = when {
            used >= limit -> "over"
            percent >= 80.0 -> "warning"
            percent >= 60.0 -> "watch"
            else -> "ok"
        }
        return QuotaProjection(used, limit, round(percent * 100.0) / 100.0, remaining, daily, daysUntil, level)
    }

    private fun round3(v: Double): Double = round(v * 1000.0) / 1000.0
}

/** CellInfo / CellSignalStrength 的「不可用」哨兵值（CellInfo.UNAVAILABLE = Int.MAX_VALUE）。 */
private const val CELL_UNAVAILABLE = Int.MAX_VALUE

/** 哨兵值 → null，避免把 2147483647 当成真实 dBm 交给模型。 */
private fun Int.known(): Int? = if (this == CELL_UNAVAILABLE) null else this

private fun Long.knownLong(): Long? = if (this == Long.MAX_VALUE || this <= 0) null else this

/**
 * 兼容包装：CellSignalStrength 的部分字段是较新 API（LTE RSRP/RSRQ/RSSNR/CQI 为 API 28、
 * GSM bitErrorRate 为 29、GSM RSSI 与 WCDMA EcNo 为 30），在更老的系统上调用会抛
 * NoSuchMethodError。这里统一降级为 null，宁可少一个字段也不让工具崩。
 */
private inline fun <T> probe(block: () -> T): T? = try {
    block()
} catch (_: Throwable) {
    null
}

/** 字节 → MB 字符串（两位小数），数据类与工具共用。 */
private fun mb(bytes: Long): String = "%.2f".format(bytes / 1024.0 / 1024.0)

/* -------------------------------- tool -------------------------------- */

class CellularTool(
    private val context: Context,
    private val permissions: PermissionBroker,
    override val name: String,
) : Tool {

    override val description: String = when (name) {
        "cell_info" ->
            "蜂窝信号诊断：按 SIM 读取 CellInfoList 主服/邻区（GSM/WCDMA/TD-SCDMA/CDMA/LTE/NR），" +
                "含 RSRP/RSRQ/RSSNR/SS-RSRP/SS-RSRQ/SS-SINR、CI/PCI/TAC/EARFCN/NR-ARFCN、" +
                "LTE 真实信道带宽、NR 频段与中心频率、信号质量评级。需要定位权限。"
        "cell_scan" ->
            "主动邻区扫描（TelephonyManager.requestNetworkScan，Android 10+）：让基带重新搜索周边小区，" +
                "返回邻区频段/PCI/信号强度。该系统接口要求 MODIFY_PHONE_STATE 或运营商特权，普通应用常被拒——" +
                "被拒时如实返回 capability 结论并给出缓存邻区（source=cached）。耗时 10~60 秒，需定位权限且定位开关打开。"
        "nr_bandwidth" ->
            "5G NR 深度信息：数据网络类型、NR 频段（n41/n78…）、NR-ARFCN 换算的中心频率、FR1/FR2、" +
                "SA/NSA 判定、LTE 锚点真实带宽；并给出各 NR 频段的信道带宽上限估算（公开 API 无实时 NR 带宽，字段标 estimate）。"
        "data_policy" ->
            "流量配额与省流量策略：Data Saver(restrictBackgroundStatus)、NetworkPolicyManager 系统配额" +
                "（缺 MANAGE_NETWORK_POLICY 时如实报 denied）、移动网络周期用量（需「使用情况访问」）、" +
                "Top 应用流量；可传 limit_mb 套餐额度，工具算出剩余量与耗尽天数预测。"
        else -> "蜂窝网络工具"
    }

    override val parameters: JsonObject = when (name) {
        "cell_info" -> schemaOf(
            "include_neighbors" to boolParam("是否包含邻区（默认 true）；只要主服小区时传 false"),
            "max_cells" to intParam("每个 SIM 返回的小区上限，默认 24，范围 1..60"),
        )
        "cell_scan" -> schemaOf(
            "seconds" to intParam("扫描窗口秒数，默认 20，范围 5..180"),
            "ran" to strParam("扫描的无线接入网：all（默认）/lte/nr/wcdma/gsm"),
        )
        "nr_bandwidth" -> schemaOf()
        "data_policy" -> schemaOf(
            "days" to intParam("统计窗口天数，默认 30，范围 1..90"),
            "top" to intParam("返回的 Top 应用数，默认 10，0 表示不返回"),
            "limit_mb" to intParam("套餐额度（MB）；给出后计算剩余量与耗尽预测，缺省用系统配额（若可读）"),
            "include_apps" to boolParam("是否返回按应用流量排行，默认 true"),
        )
        else -> schemaOf()
    }

    /** 全部只读，不进确认闸。 */
    override val destructive: Boolean = false

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        runCatching {
            when (name) {
                "cell_info" -> cellInfo(args)
                "cell_scan" -> cellScan(args)
                "nr_bandwidth" -> nrBandwidth()
                "data_policy" -> dataPolicy(args)
                else -> "ERROR: 未知蜂窝工具 $name"
            }
        }.getOrElse { "ERROR: $name 执行失败：${it.message ?: it.javaClass.simpleName}" }
    }

    /* ---------------------------- cell_info ---------------------------- */

    private suspend fun cellInfo(args: JsonObject): String {
        val permission = if (Build.VERSION.SDK_INT >= 29) {
            Manifest.permission.ACCESS_FINE_LOCATION
        } else {
            Manifest.permission.ACCESS_COARSE_LOCATION
        }
        // 一次申请到位：位置（CellInfo 强制）+ 电话状态（双卡枚举）；任一被拒都按需降级
        permissions.ensure(listOf(permission, Manifest.permission.READ_PHONE_STATE))
        if (!permissions.isGranted(permission)) {
            return "ERROR: 未授予定位权限。Android 要求读取基站（CellInfo）必须持有位置权限，且需要定位开关打开"
        }
        val includeNeighbors = args.boolArg("include_neighbors") ?: true
        val maxCells = (args.intArg("max_cells") ?: 24).coerceIn(1, 60)
        val warnings = mutableListOf<String>()
        if (!locationEnabled()) warnings += "系统定位开关已关闭：多数机型在定位关闭时 getAllCellInfo 返回空列表"

        val hasPhoneState = permissions.isGranted(Manifest.permission.READ_PHONE_STATE)
        if (!hasPhoneState) warnings += "未授予 READ_PHONE_STATE：只能读取默认 SIM，双卡需要该权限"

        val subs = activeSubscriptions()
        val targets: List<Pair<Int?, String?>> =
            if (subs.isEmpty()) listOf(null to null)
            else subs.map { it.subscriptionId to it.carrierName?.toString() }

        val sims = mutableListOf<JsonObject>()
        var total = 0
        for ((subId, carrier) in targets) {
            val tm = telephonyFor(subId)
            val cells = try {
                tm?.allCellInfo.orEmpty()
            } catch (e: SecurityException) {
                warnings += "SIM $subId 读取小区失败：${e.message ?: "SecurityException"}"
                emptyList()
            } catch (e: Exception) {
                warnings += "SIM $subId 读取小区失败：${e.message ?: e.javaClass.simpleName}"
                emptyList()
            }
            val registered = cells.filter { it.isRegistered }
            val neighbors = if (includeNeighbors) cells.filterNot { it.isRegistered } else emptyList()
            // 主服优先，其次邻区；总量受 max_cells 约束
            val ordered = (registered + neighbors).take(maxCells)
            total += cells.size
            sims += buildJsonObject {
                put("subId", subId)
                put("slot", subs.firstOrNull { it.subscriptionId == subId }?.simSlotIndex)
                put("carrier", carrier)
                put("mccMnc", tm?.let { it.simOperator?.takeIf { v -> v.isNotBlank() } })
                put("dataNetworkType", tm?.let { runCatching { CellularCodec.networkTypeName(it.dataNetworkType) }.getOrNull() })
                put("dataState", tm?.let { runCatching { dataStateName(it.dataState) }.getOrNull() })
                put("roaming", tm?.let { runCatching { it.isNetworkRoaming }.getOrNull() })
                put("dataEnabled", tm?.let { runCatching { it.isDataEnabled }.getOrNull() })
                put("signalLevel", tm?.let { probe { it.signalStrength?.level } })
                put("registeredCells", registered.size)
                put("neighborCells", neighbors.size)
                putJsonArray("cells") { ordered.forEach { add(cellJson(it)) } }
            }
        }

        return buildJsonObject {
            put("apiLevel", Build.VERSION.SDK_INT)
            put("locationEnabled", locationEnabled())
            put("cellCount", total)
            putJsonArray("sims") { sims.forEach { add(it) } }
            if (warnings.isNotEmpty()) {
                putJsonArray("warnings") { warnings.forEach { add(it) } }
            }
            if (total == 0) {
                put(
                    "hint",
                    "没有读到任何小区：确认已授予「位置信息」权限、系统定位开关打开；部分机型还需插入可用 SIM 或进入有信号区域",
                )
            }
        }.toString()
    }

    private fun cellJson(info: CellInfo): JsonObject {
        val head = buildJsonObject {
            put("registered", info.isRegistered)
            if (Build.VERSION.SDK_INT >= 28) {
                put("connectionStatus", CellularCodec.connectionStatusName(info.cellConnectionStatus))
            }
            put("ageMs", (System.currentTimeMillis() - info.timeStamp).coerceAtLeast(0))
        }
        val detail = when {
            Build.VERSION.SDK_INT >= 29 && info is CellInfoNr -> {
                val id = info.cellIdentity as CellIdentityNr
                val ss = info.cellSignalStrength as CellSignalStrengthNr
                buildJsonObject {
                    put("type", "nr")
                    putJsonObject("identity") { nrIdentity(id) }
                    putJsonObject("signal") { nrSignal(ss) }
                    nrQuality(ss)?.let { put("quality", it) }
                }
            }
            info is CellInfoLte -> {
                val id = info.cellIdentity
                val ss = info.cellSignalStrength
                buildJsonObject {
                    put("type", "lte")
                    putJsonObject("identity") { lteIdentity(id) }
                    putJsonObject("signal") { lteSignal(ss) }
                    lteQuality(ss, id)?.let { put("quality", it) }
                }
            }
            info is CellInfoGsm -> {
                val id = info.cellIdentity
                val ss = info.cellSignalStrength
                buildJsonObject {
                    put("type", "gsm")
                    putJsonObject("identity") {
                        plmn(id)
                        put("lac", id.lac.known())
                        put("cid", id.cid.known())
                        put("arfcn", id.arfcn.known())
                        put("bsic", id.bsic.known())
                    }
                    putJsonObject("signal") {
                        put("dbm", ss.dbm.known())
                        put("rssi", probe { ss.rssi }?.known())
                        put("bitErrorRate", probe { ss.bitErrorRate }?.known())
                        put("timingAdvance", probe { ss.timingAdvance }?.known())
                        put("level", ss.level)
                        put("qualityLabel", CellularCodec.levelName(ss.level))
                    }
                }
            }
            info is CellInfoWcdma -> {
                val id = info.cellIdentity
                val ss = info.cellSignalStrength
                buildJsonObject {
                    put("type", "wcdma")
                    putJsonObject("identity") {
                        plmn(id)
                        put("lac", id.lac.known())
                        put("cid", id.cid.known())
                        put("psc", id.psc.known())
                        put("uarfcn", id.uarfcn.known())
                    }
                    putJsonObject("signal") {
                        put("dbm", ss.dbm.known())
                        put("ecNo", probe { ss.ecNo }?.known())
                        put("level", ss.level)
                        put("qualityLabel", CellularCodec.levelName(ss.level))
                    }
                }
            }
            info is CellInfoCdma -> {
                val id = info.cellIdentity
                val ss = info.cellSignalStrength
                buildJsonObject {
                    put("type", "cdma")
                    putJsonObject("identity") {
                        put("networkId", id.networkId.known())
                        put("systemId", id.systemId.known())
                        put("basestationId", id.basestationId.known())
                        val lat = id.latitude.known()
                        val lon = id.longitude.known()
                        if (lat != null && lon != null && lat != 0 && lon != 0) {
                            // 公开 API 只能拿到 1/4 秒精度的粗坐标（XX.YY 格式的整数）
                            put("latitudeApprox", lat / 4.0)
                            put("longitudeApprox", lon / 4.0)
                        }
                    }
                    putJsonObject("signal") {
                        put("cdmaDbm", ss.cdmaDbm.known())
                        put("cdmaEcio", ss.cdmaEcio.known())
                        put("evdoDbm", ss.evdoDbm.known())
                        put("evdoEcio", ss.evdoEcio.known())
                        put("evdoSnr", ss.evdoSnr.known())
                        put("level", ss.level)
                        put("qualityLabel", CellularCodec.levelName(ss.level))
                    }
                }
            }
            Build.VERSION.SDK_INT >= 29 && info is CellInfoTdscdma -> {
                val id = info.cellIdentity as CellIdentityTdscdma
                val ss = info.cellSignalStrength as CellSignalStrengthTdscdma
                buildJsonObject {
                    put("type", "td_scdma")
                    putJsonObject("identity") {
                        plmn(id)
                        put("lac", id.lac.known())
                        put("cid", id.cid.known())
                        put("cpid", id.cpid.known())
                        put("uarfcn", id.uarfcn.known())
                    }
                    putJsonObject("signal") {
                        put("dbm", ss.dbm.known())
                        put("rscp", probe { ss.rscp }?.known())
                        put("level", ss.level)
                        put("qualityLabel", CellularCodec.levelName(ss.level))
                    }
                }
            }
            else -> buildJsonObject { put("type", "unknown") }
        }
        return JsonObject(head + detail)
    }

    /* ------------------------- identity / signal ------------------------- */

    private fun JsonObjectBuilder.plmn(id: CellIdentityGsm) {
        if (Build.VERSION.SDK_INT >= 28) {
            put("mcc", id.mccString)
            put("mnc", id.mncString)
            put("operator", id.operatorAlphaLong?.toString())
        } else {
            put("mcc", id.mcc.known()?.toString())
            put("mnc", id.mnc.known()?.let { "%02d".format(it) })
        }
    }

    private fun JsonObjectBuilder.plmn(id: CellIdentityWcdma) {
        if (Build.VERSION.SDK_INT >= 28) {
            put("mcc", id.mccString)
            put("mnc", id.mncString)
            put("operator", id.operatorAlphaLong?.toString())
        } else {
            put("mcc", id.mcc.known()?.toString())
            put("mnc", id.mnc.known()?.let { "%02d".format(it) })
        }
    }

    private fun JsonObjectBuilder.plmn(id: CellIdentityTdscdma) {
        put("mcc", id.mccString)
        put("mnc", id.mncString)
        put("operator", id.operatorAlphaLong?.toString())
    }

    private fun JsonObjectBuilder.lteIdentity(id: CellIdentityLte) {
        if (Build.VERSION.SDK_INT >= 28) {
            put("mcc", id.mccString)
            put("mnc", id.mncString)
            put("operator", id.operatorAlphaLong?.toString())
        } else {
            put("mcc", id.mcc.known()?.toString())
            put("mnc", id.mnc.known()?.let { "%02d".format(it) })
        }
        put("ci", id.ci.known())
        put("pci", id.pci.known())
        put("tac", id.tac.known())
        put("earfcn", id.earfcn.known())
        val bandwidthKhz = probe { id.bandwidth }?.known()
        put("bandwidthKhz", bandwidthKhz)
        put("bandwidthMhz", CellularCodec.lteBandWidthMhz(bandwidthKhz))
        val band = if (Build.VERSION.SDK_INT >= 30) id.bands?.firstOrNull()?.known() else null
        put("band", band)
        put("frequencyMhz", CellularCodec.lteEarfcnToMhz(id.earfcn, band))
    }

    private fun JsonObjectBuilder.nrIdentity(id: CellIdentityNr) {
        put("mcc", id.mccString)
        put("mnc", id.mncString)
        put("nci", id.nci.knownLong())
        put("pci", id.pci.known())
        put("tac", id.tac.known())
        val arfcn = id.nrarfcn.known()
        put("nrarfcn", arfcn)
        val mhz = arfcn?.let { CellularCodec.nrArfcnToMhz(it) }
        put("frequencyMhz", mhz)
        put("frequencyRange", mhz?.let { CellularCodec.frequencyRange(it) })
        val bands = if (Build.VERSION.SDK_INT >= 30) id.bands?.filter { it > 0 }.orEmpty() else emptyList()
        putJsonArray("bands") { bands.forEach { add(CellularCodec.nrBandLabel(it)) } }
        val estimates = bands.mapNotNull { CellularCodec.nrBandMaxBandwidthMhz(it) }
        put("bandwidthEstimateMhz", estimates.maxOrNull())
        put("bandwidthEstimate", estimates.isNotEmpty())
    }

    private fun JsonObjectBuilder.lteSignal(ss: CellSignalStrengthLte) {
        put("rsrp", probe { ss.rsrp }?.known())
        put("rsrq", probe { ss.rsrq }?.known())
        put("rssnr", probe { ss.rssnr }?.known())
        put("rssi", probe { ss.rssi }?.known())
        put("cqi", probe { ss.cqi }?.known())
        put("timingAdvance", probe { ss.timingAdvance }?.known())
        put("dbm", ss.dbm.known())
        put("asuLevel", ss.asuLevel.known())
        put("level", ss.level)
        put("levelLabel", CellularCodec.levelName(ss.level))
    }

    private fun JsonObjectBuilder.nrSignal(ss: CellSignalStrengthNr) {
        put("ssRsrp", ss.ssRsrp.known())
        put("ssRsrq", ss.ssRsrq.known())
        put("ssSinr", ss.ssSinr.known())
        put("csiRsrp", ss.csiRsrp.known())
        put("csiRsrq", ss.csiRsrq.known())
        put("csiSinr", ss.csiSinr.known())
        put("timingAdvanceMicros", ss.timingAdvanceMicros.known())
        put("dbm", ss.dbm.known())
        put("asuLevel", ss.asuLevel.known())
        put("level", ss.level)
        put("levelLabel", CellularCodec.levelName(ss.level))
    }

    private fun lteQuality(ss: CellSignalStrengthLte, id: CellIdentityLte): JsonObject? {
        val rsrp = probe { ss.rsrp }?.known()
        val rsrq = probe { ss.rsrq }?.known()
        val sinr = probe { ss.rssnr }?.known()
        val bandwidthMhz = CellularCodec.lteBandWidthMhz(probe { id.bandwidth }?.known())
        if (rsrp == null && rsrq == null && sinr == null && bandwidthMhz == null) return null
        val rsrpLabel = rsrp?.let { CellularCodec.rsrpLabel(it) }
        val rsrqLabel = rsrq?.let { CellularCodec.rsrqLabel(it) }
        val sinrLabel = sinr?.let { CellularCodec.sinrLabel(it) }
        return buildJsonObject {
            put("rsrpLabel", rsrpLabel)
            put("rsrqLabel", rsrqLabel)
            put("sinrLabel", sinrLabel)
            put("overall", CellularCodec.overallLabel(rsrpLabel, rsrqLabel, sinrLabel))
            put("bandwidthMhz", bandwidthMhz)
        }
    }

    private fun nrQuality(ss: CellSignalStrengthNr): JsonObject? {
        val rsrp = ss.ssRsrp.known()
        val rsrq = ss.ssRsrq.known()
        val sinr = ss.ssSinr.known()
        if (rsrp == null && rsrq == null && sinr == null) return null
        val rsrpLabel = rsrp?.let { CellularCodec.rsrpLabel(it) }
        val rsrqLabel = rsrq?.let { CellularCodec.rsrqLabel(it) }
        val sinrLabel = sinr?.let { CellularCodec.sinrLabel(it) }
        return buildJsonObject {
            put("ssRsrpLabel", rsrpLabel)
            put("ssRsrqLabel", rsrqLabel)
            put("ssSinrLabel", sinrLabel)
            put("overall", CellularCodec.overallLabel(rsrpLabel, rsrqLabel, sinrLabel))
        }
    }

    /* ---------------------------- cell_scan ---------------------------- */

    private suspend fun cellScan(args: JsonObject): String {
        if (Build.VERSION.SDK_INT < 29) {
            return buildJsonObject {
                put("ok", false)
                put("reason", "unsupported_api")
                put("message", "requestNetworkScan 需要 Android 10（API 29）及以上；当前 API ${Build.VERSION.SDK_INT}")
                put("hint", "用 cell_info 查看系统缓存的邻区")
            }.toString()
        }
        if (!permissions.ensure(listOf(Manifest.permission.ACCESS_FINE_LOCATION))) {
            return "ERROR: 未授予精确位置权限，系统拒绝基站扫描"
        }
        if (!locationEnabled()) {
            return buildJsonObject {
                put("ok", false)
                put("reason", "location_disabled")
                put("message", "系统定位开关已关闭，基站扫描不会返回结果")
                put("hint", "打开「位置信息」后重试；也可用 cell_info 读取缓存小区")
            }.toString()
        }

        val seconds = (args.intArg("seconds") ?: 20).coerceIn(5, 180)
        val ran = (args.strArg("ran") ?: "all").lowercase()
        val specifiers = when (ran) {
            "all", "" -> null
            "lte" -> arrayOf(RadioAccessSpecifier(AccessNetworkConstants.AccessNetworkType.EUTRAN, null, null))
            "nr" -> arrayOf(RadioAccessSpecifier(AccessNetworkConstants.AccessNetworkType.NGRAN, null, null))
            "wcdma" -> arrayOf(RadioAccessSpecifier(AccessNetworkConstants.AccessNetworkType.UTRAN, null, null))
            "gsm" -> arrayOf(RadioAccessSpecifier(AccessNetworkConstants.AccessNetworkType.GERAN, null, null))
            else -> return "ERROR: ran 仅支持 all|lte|nr|wcdma|gsm"
        }
        val request = try {
            // searchPeriodicity 仅对 PERIODIC 生效；maxSearchTime 由系统校验（秒，60 起步）
            NetworkScanRequest(
                NetworkScanRequest.SCAN_TYPE_ONE_SHOT,
                specifiers,
                60,
                seconds.coerceAtLeast(60),
                false,
                0,
                null,
            )
        } catch (e: Exception) {
            return "ERROR: 构造扫描请求失败：${e.message ?: e.javaClass.simpleName}"
        }

        val tm = telephonyFor(null) ?: return "ERROR: 电话服务不可用"
        val collected = java.util.Collections.synchronizedMap(LinkedHashMap<String, CellInfo>())
        val errors = java.util.Collections.synchronizedList(mutableListOf<Int>())
        val done = CompletableDeferred<Unit>()
        val callback = object : TelephonyScanManager.NetworkScanCallback() {
            override fun onResults(results: MutableList<CellInfo>?) {
                results?.forEach { collected[cellKey(it)] = it }
            }

            override fun onComplete() {
                done.complete(Unit)
            }

            override fun onError(error: Int) {
                errors.add(error)
                done.complete(Unit)
            }
        }

        val startedAt = SystemClock.elapsedRealtime()
        val scan: NetworkScan = try {
            tm.requestNetworkScan(request, Executor { it.run() }, callback)
                ?: return "ERROR: 扫描请求未被系统受理（返回 null）"
        } catch (e: SecurityException) {
            // 公开 API 的权限门槛：MODIFY_PHONE_STATE（signature|privileged）或运营商特权
            return degradedScan(
                reason = "permission_denied",
                message = "系统拒绝主动扫描：requestNetworkScan 要求 MODIFY_PHONE_STATE（签名权限）或运营商特权，" +
                    "普通应用无法满足（${e.message ?: "SecurityException"}）",
                seconds = seconds,
            )
        } catch (e: Exception) {
            return degradedScan(
                reason = "scan_start_failed",
                message = "发起基站扫描失败：${e.message ?: e.javaClass.simpleName}",
                seconds = seconds,
            )
        }

        val finished = withTimeoutOrNull((seconds + 20) * 1000L) { done.await() } != null
        if (!finished) runCatching { scan.stopScan() }
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        val cells = synchronized(collected) { collected.values.toList() }
        val errorCodes = synchronized(errors) { errors.toList() }

        if (cells.isEmpty() && errorCodes.isNotEmpty() && errorCodes.none { it == 0 }) {
            return degradedScan(
                reason = "scan_error",
                message = "扫描失败：${errorCodes.joinToString(",") { CellularCodec.scanErrorName(it) }}",
                seconds = seconds,
            )
        }

        val sorted = cells.sortedByDescending { signalDbm(it) ?: Int.MIN_VALUE }
        return buildJsonObject {
            put("ok", true)
            put("source", "network_scan")
            put("ran", ran)
            put("scanMs", elapsed)
            put("completed", finished)
            put("cellCount", cells.size)
            put("registeredCount", cells.count { it.isRegistered })
            putJsonArray("cells") { sorted.take(40).forEach { add(cellJson(it)) } }
            if (errorCodes.isNotEmpty()) {
                putJsonArray("errors") { errorCodes.forEach { add(CellularCodec.scanErrorName(it)) } }
            }
            if (!finished) put("note", "扫描窗口内未收到完成回调，已调用 stopScan 并返回已收集结果")
        }.toString()
    }

    /** 扫描被拒/失败时的降级结果：如实说明原因 + 给缓存邻区，绝不假装扫到了新小区。 */
    private fun degradedScan(reason: String, message: String, seconds: Int): String {
        val cached = try {
            telephonyFor(null)?.allCellInfo.orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
        return buildJsonObject {
            put("ok", false)
            put("reason", reason)
            put("message", message)
            put("requestedSeconds", seconds)
            put("source", "cached")
            put("degraded", true)
            put("cellCount", cached.size)
            putJsonArray("cells") {
                cached.sortedByDescending { signalDbm(it) ?: Int.MIN_VALUE }.take(40).forEach { add(cellJson(it)) }
            }
            put(
                "hint",
                "主动扫描需要系统级电话权限（MODIFY_PHONE_STATE）。普通应用可用 cell_info 读缓存邻区；" +
                    "若目标是排查覆盖问题，可对比多次 cell_info 的邻区变化。",
            )
        }.toString()
    }

    private fun cellKey(info: CellInfo): String {
        val type = when {
            Build.VERSION.SDK_INT >= 29 && info is CellInfoNr -> "nr"
            info is CellInfoLte -> "lte"
            info is CellInfoGsm -> "gsm"
            info is CellInfoWcdma -> "wcdma"
            info is CellInfoCdma -> "cdma"
            Build.VERSION.SDK_INT >= 29 && info is CellInfoTdscdma -> "tdscdma"
            else -> "unknown"
        }
        return "$type:${info.cellIdentity.hashCode()}"
    }

    /** 排序用信号强度（dBm，越大越好）；NR 用 SS-RSRP。 */
    private fun signalDbm(info: CellInfo): Int? = when {
        Build.VERSION.SDK_INT >= 29 && info is CellInfoNr ->
            (info.cellSignalStrength as CellSignalStrengthNr).ssRsrp.known()
        info is CellInfoLte -> probe { info.cellSignalStrength.rsrp }?.known()
        info is CellInfoGsm -> info.cellSignalStrength.dbm.known()
        info is CellInfoWcdma -> info.cellSignalStrength.dbm.known()
        info is CellInfoCdma -> info.cellSignalStrength.cdmaDbm.known()
        Build.VERSION.SDK_INT >= 29 && info is CellInfoTdscdma -> info.cellSignalStrength.dbm.known()
        else -> null
    }

    /* --------------------------- nr_bandwidth --------------------------- */

    private suspend fun nrBandwidth(): String {
        val warnings = mutableListOf<String>()
        val permission = if (Build.VERSION.SDK_INT >= 29) {
            Manifest.permission.ACCESS_FINE_LOCATION
        } else {
            Manifest.permission.ACCESS_COARSE_LOCATION
        }
        permissions.ensure(listOf(permission, Manifest.permission.READ_PHONE_STATE))
        val hasLocation = permissions.isGranted(permission)
        if (!hasLocation) warnings += "未授予定位权限：读不到 NR/LTE 小区（频段、ARFCN、带宽）"

        val tm = telephonyFor(null) ?: return "ERROR: 电话服务不可用"
        val hasPhoneState = permissions.isGranted(Manifest.permission.READ_PHONE_STATE)
        if (!hasPhoneState) warnings += "未授予 READ_PHONE_STATE：数据网络类型可能返回 unknown"

        val dataNetworkType = runCatching { tm.dataNetworkType }.getOrDefault(-1)
        val typeName = CellularCodec.networkTypeName(dataNetworkType)
        val dataState = runCatching { dataStateName(tm.dataState) }.getOrNull()

        val cells = if (hasLocation) {
            try {
                tm.allCellInfo.orEmpty()
            } catch (e: Exception) {
                warnings += "读取小区失败：${e.message ?: e.javaClass.simpleName}"
                emptyList()
            }
        } else {
            emptyList()
        }

        val nrCells = cells.filter { Build.VERSION.SDK_INT >= 29 && it is CellInfoNr }
        val lteCells = cells.filterIsInstance<CellInfoLte>()
        val registeredNr = nrCells.filter { it.isRegistered }
        val registeredLte = lteCells.filter { it.isRegistered }

        // SA/NSA：数据网络类型为 NR → SA（部分厂商 NSA 也上报 NR，故仅作启发式）；
        // LTE 数据 + 已注册 NR 小区 → NSA 双连接
        val nsaSa = when {
            typeName == "nr" -> "sa_or_reported_nr"
            nrCells.isNotEmpty() && typeName.startsWith("lte") -> "nsa"
            registeredNr.isNotEmpty() -> "nsa_idle"
            typeName == "unknown" || dataNetworkType <= 0 -> "unknown"
            else -> "none"
        }

        val state = runCatching { tm.serviceState }.getOrNull()
        val ranTechs = state?.let { collectRanTechs(it) }.orEmpty()

        return buildJsonObject {
            put("apiLevel", Build.VERSION.SDK_INT)
            put("dataNetworkType", typeName)
            put("dataState", dataState)
            put("dataEnabled", runCatching { tm.isDataEnabled }.getOrNull())
            put("roaming", runCatching { tm.isNetworkRoaming }.getOrNull())
            put("simOperator", runCatching { tm.simOperator }.getOrNull())
            put("nrMode", nsaSa)
            putJsonArray("registeredRats") { ranTechs.forEach { add(it) } }
            put("nrCellCount", nrCells.size)
            put("nrRegisteredCount", registeredNr.size)
            putJsonArray("nrCells") {
                val ordered = (registeredNr + nrCells.filterNot { it.isRegistered })
                ordered.take(12).forEach { add(cellJson(it)) }
            }
            putJsonObject("lteAnchor") {
                val anchor = registeredLte.firstOrNull() ?: lteCells.firstOrNull()
                if (anchor == null) {
                    put("available", false)
                } else {
                    val id = anchor.cellIdentity
                    val ss = anchor.cellSignalStrength
                    put("available", true)
                    put("registered", anchor.isRegistered)
                    put("earfcn", id.earfcn.known())
                    val band = if (Build.VERSION.SDK_INT >= 30) id.bands?.firstOrNull()?.known() else null
                    put("band", band)
                    val bandwidthKhz = probe { id.bandwidth }?.known()
                    put("bandwidthKhz", bandwidthKhz)
                    put("bandwidthMhz", CellularCodec.lteBandWidthMhz(bandwidthKhz))
                    put("frequencyMhz", CellularCodec.lteEarfcnToMhz(id.earfcn, band))
                    put("rsrp", probe { ss.rsrp }?.known())
                    put("rsrq", probe { ss.rsrq }?.known())
                    put("sinr", probe { ss.rssnr }?.known())
                }
            }
            putJsonArray("nrBandwidthEstimates") {
                val bands = nrCells
                    .filter { Build.VERSION.SDK_INT >= 30 }
                    .flatMap { (it.cellIdentity as CellIdentityNr).bands?.toList().orEmpty() }
                    .filter { it > 0 }
                    .distinct()
                for (band in bands) {
                    add(buildJsonObject {
                        put("band", CellularCodec.nrBandLabel(band))
                        put("maxChannelBandwidthMhz", CellularCodec.nrBandMaxBandwidthMhz(band))
                    })
                }
            }
            if (warnings.isNotEmpty()) putJsonArray("warnings") { warnings.forEach { add(it) } }
            put(
                "note",
                "NR 实时信道带宽不在 Android 公开 API 内（CellIdentityNr 只有频段与 NR-ARFCN）；" +
                    "bandwidthEstimateMhz / maxChannelBandwidthMhz 是 3GPP 频段能力上限，运营商实际部署通常更窄，" +
                    "不要当成当前占用带宽。LTE 带宽（bandwidthKhz）来自 CellIdentityLte.getBandwidth()，是真实值。",
            )
        }.toString()
    }

    private fun collectRanTechs(state: ServiceState): List<String> {
        // getNetworkRegistrationInfoList 是 API 30 才公开的；低版本不猜，交给 dataNetworkType
        if (Build.VERSION.SDK_INT < 30) return emptyList()
        return runCatching {
            state.networkRegistrationInfoList.orEmpty().mapNotNull { info ->
                val tech = runCatching { info.accessNetworkTechnology }.getOrNull() ?: return@mapNotNull null
                val name = CellularCodec.accessNetworkName(tech)
                if (name == "unknown") null else name
            }.distinct()
        }.getOrElse { emptyList() }
    }

    /* ----------------------------- data_policy ----------------------------- */

    private suspend fun dataPolicy(args: JsonObject): String {
        val days = (args.intArg("days") ?: 30).coerceIn(1, 90)
        val top = (args.intArg("top") ?: 10).coerceIn(0, 30)
        val limitMb = args.longArg("limit_mb")?.takeIf { it > 0 }
        val includeApps = args.boolArg("include_apps") ?: true
        val warnings = mutableListOf<String>()

        val cm = context.getSystemService(ConnectivityManager::class.java)
        val restrictStatus = runCatching { cm?.restrictBackgroundStatus }.getOrNull()
        val policy = readNetworkPolicies()

        val usageAccess = hasUsageAccess()
        if (!usageAccess) {
            warnings += "缺少「使用情况访问」权限：无法读取周期用量；到 设置 → 特殊应用权限 → 使用情况访问 授予 AndAgent"
        }
        val end = System.currentTimeMillis()
        val begin = end - days * 86_400_000L
        val deviceUsage = if (usageAccess) readDeviceMobileUsage(begin, end) else null
        if (usageAccess && deviceUsage == null) {
            warnings += "NetworkStatsManager 查询失败（部分机型需插入 SIM / 有移动流量记录）"
        }
        val usedBytes = (deviceUsage?.first ?: 0L) + (deviceUsage?.second ?: 0L)

        val systemLimit = policy.items
            .firstOrNull { it.isMobile && (it.limitBytes ?: -1) > 0 }
            ?.limitBytes
        val effectiveLimit = limitMb?.let { it * 1024L * 1024L } ?: systemLimit
        val quota = CellularCodec.quotaProjection(usedBytes, effectiveLimit, days)

        val apps = if (includeApps && top > 0 && usageAccess) readTopApps(begin, end, top) else emptyList()

        val tm = telephonyFor(null)
        return buildJsonObject {
            putJsonObject("dataSaver") {
                put("restrictBackgroundStatus", restrictBackgroundName(restrictStatus))
                put("appRestricted", restrictStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED)
                put("meteredActiveNetwork", runCatching { cm?.isActiveNetworkMetered }.getOrNull())
            }
            putJsonObject("systemPolicy") {
                put("access", policy.access)
                policy.error?.let { put("error", it) }
                putJsonArray("items") { policy.items.forEach { add(it.toJson()) } }
            }
            putJsonObject("mobileData") {
                val type = runCatching { tm?.dataNetworkType }.getOrNull() ?: -1
                put("networkType", CellularCodec.networkTypeName(type))
                put("state", runCatching { tm?.dataState?.let { dataStateName(it) } }.getOrNull())
                put("enabled", runCatching { tm?.isDataEnabled }.getOrNull())
            }
            putJsonObject("usage") {
                put("windowDays", days)
                put("windowStartMs", begin)
                put("available", deviceUsage != null)
                put("rxBytes", deviceUsage?.first)
                put("txBytes", deviceUsage?.second)
                put("totalBytes", if (deviceUsage == null) null else usedBytes)
                put("totalMB", if (deviceUsage == null) null else mb(usedBytes))
            }
            putJsonObject("quota") {
                if (quota == null) {
                    put("available", false)
                    put(
                        "hint",
                        if (effectiveLimit == null) {
                            "未取到套餐额度：传 limit_mb 指定套餐大小，或授予系统网络策略读取权限（普通应用通常拿不到）"
                        } else {
                            "额度不可用"
                        },
                    )
                } else {
                    put("available", true)
                    put("limitBytes", quota.limitBytes)
                    put("limitMB", mb(quota.limitBytes))
                    put("usedBytes", quota.usedBytes)
                    put("usedMB", mb(quota.usedBytes))
                    put("percentUsed", quota.percentUsed)
                    put("remainingBytes", quota.remainingBytes)
                    put("remainingMB", mb(quota.remainingBytes))
                    put("dailyAvgMB", mb(quota.dailyAvgBytes))
                    put("daysUntilLimit", quota.daysUntilLimit)
                    put("level", quota.level)
                    put("limitSource", if (limitMb != null) "user" else "system_policy")
                }
            }
            if (apps.isNotEmpty()) putJsonArray("apps") { apps.forEach { add(it) } }
            putJsonObject("sinceBoot") {
                put("deviceRxBytes", TrafficStats.getTotalRxBytes())
                put("deviceTxBytes", TrafficStats.getTotalTxBytes())
                put("mobileRxBytes", TrafficStats.getMobileRxBytes())
                put("mobileTxBytes", TrafficStats.getMobileTxBytes())
                put("appRxBytes", TrafficStats.getUidRxBytes(Process.myUid()))
                put("appTxBytes", TrafficStats.getUidTxBytes(Process.myUid()))
            }
            if (warnings.isNotEmpty()) putJsonArray("warnings") { warnings.forEach { add(it) } }
        }.toString()
    }

    private data class PolicyItem(
        val matchRule: Int?,
        val cycleDay: Int?,
        val cycleTimezone: String?,
        val warningBytes: Long?,
        val limitBytes: Long?,
        val metered: Boolean?,
    ) {
        /** NetworkTemplate.MATCH_MOBILE_WILDCARD = 0，MATCH_MOBILE = 10。 */
        val isMobile: Boolean get() = matchRule == 0 || matchRule == 10

        fun toJson(): JsonObject = buildJsonObject {
            put("kind", if (isMobile) "mobile" else "other")
            put("matchRule", matchRule)
            put("cycleDay", cycleDay)
            put("cycleTimezone", cycleTimezone)
            put("warningBytes", warningBytes)
            put("limitBytes", limitBytes)
            put("warningMB", warningBytes?.let { mb(it) })
            put("limitMB", limitBytes?.let { mb(it) })
            put("metered", metered)
        }
    }

    private data class PolicyRead(val access: String, val error: String?, val items: List<PolicyItem>)

    /**
     * NetworkPolicyManager 是 @SystemApi：getNetworkPolicies() 需要 MANAGE_NETWORK_POLICY。
     * 这里做能力探测——反射被系统限制或权限不足时如实上报，不伪造配额。
     */
    private fun readNetworkPolicies(): PolicyRead {
        val service = try {
            context.getSystemService("netpolicy")
        } catch (e: Throwable) {
            null
        } ?: return PolicyRead("unavailable", "系统未提供 netpolicy 服务", emptyList())

        val method = try {
            service.javaClass.getMethod("getNetworkPolicies")
        } catch (e: Throwable) {
            return PolicyRead(
                "blocked",
                "NetworkPolicyManager 属非 SDK 接口，反射被系统限制（${e.javaClass.simpleName}）",
                emptyList(),
            )
        }
        val raw = try {
            method.invoke(service)
        } catch (e: Throwable) {
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            return PolicyRead(
                "denied",
                "读取系统流量配额需要 MANAGE_NETWORK_POLICY 系统权限：${cause.message ?: cause.javaClass.simpleName}",
                emptyList(),
            )
        }
        val array = raw as? Array<*> ?: return PolicyRead("unsupported", "getNetworkPolicies 返回类型异常", emptyList())
        val items = array.mapNotNull { policy -> policy?.let { readPolicy(it) } }
        return PolicyRead("ok", null, items)
    }

    private fun readPolicy(policy: Any): PolicyItem? = runCatching {
        fun longOf(name: String): Long? = runCatching {
            (policy.javaClass.getMethod(name).invoke(policy) as? Long)?.takeIf { it >= 0 }
        }.getOrNull()

        fun intOf(name: String): Int? = runCatching {
            policy.javaClass.getMethod(name).invoke(policy) as? Int
        }.getOrNull()

        fun boolOf(name: String): Boolean? = runCatching {
            policy.javaClass.getMethod(name).invoke(policy) as? Boolean
        }.getOrNull()

        fun strOf(name: String): String? = runCatching {
            policy.javaClass.getMethod(name).invoke(policy) as? String
        }.getOrNull()

        val rule = runCatching {
            val template = policy.javaClass.getMethod("getTemplate").invoke(policy) ?: return@runCatching null
            template.javaClass.getMethod("getMatchRule").invoke(template) as? Int
        }.getOrNull()

        PolicyItem(
            matchRule = rule,
            cycleDay = intOf("getCycleDay"),
            cycleTimezone = strOf("getCycleTimezone"),
            warningBytes = longOf("getWarningBytes"),
            limitBytes = longOf("getLimitBytes"),
            metered = boolOf("isMetered"),
        )
    }.getOrNull()

    private fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        return appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        ) == AppOpsManager.MODE_ALLOWED
    }

    @Suppress("DEPRECATION")
    private fun readDeviceMobileUsage(begin: Long, end: Long): Pair<Long, Long>? {
        val manager = context.getSystemService(NetworkStatsManager::class.java) ?: return null
        return try {
            val bucket = manager.querySummaryForDevice(ConnectivityManager.TYPE_MOBILE, null, begin, end)
            bucket.rxBytes to bucket.txBytes
        } catch (_: Exception) {
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun readTopApps(begin: Long, end: Long, top: Int): List<JsonObject> {
        val manager = context.getSystemService(NetworkStatsManager::class.java) ?: return emptyList()
        val perUid = HashMap<Int, LongArray>()
        try {
            val stats: NetworkStats = manager.querySummary(ConnectivityManager.TYPE_MOBILE, null, begin, end)
            try {
                val bucket = NetworkStats.Bucket()
                while (stats.hasNextBucket()) {
                    if (!stats.getNextBucket(bucket)) break
                    val acc = perUid.getOrPut(bucket.uid) { LongArray(2) }
                    acc[0] += bucket.rxBytes
                    acc[1] += bucket.txBytes
                }
            } finally {
                runCatching { stats.close() }
            }
        } catch (_: Exception) {
            return emptyList()
        }
        val index = uidIndex()
        return perUid.entries
            .filter { it.value[0] + it.value[1] > 0 }
            .sortedByDescending { it.value[0] + it.value[1] }
            .take(top)
            .map { (uid, values) ->
                val known = index[uid]
                buildJsonObject {
                    put("uid", uid)
                    put("package", known?.first ?: "uid:$uid")
                    put("label", known?.second)
                    put("rxBytes", values[0])
                    put("txBytes", values[1])
                    put("totalMB", mb(values[0] + values[1]))
                }
            }
    }

    private fun uidIndex(): Map<Int, Pair<String, String>> {
        val manager = context.packageManager
        val index = HashMap<Int, Pair<String, String>>()
        runCatching {
            for (info in manager.getInstalledPackages(0)) {
                val app = info.applicationInfo ?: continue
                if (index.containsKey(app.uid)) continue
                val label = runCatching { manager.getApplicationLabel(app).toString() }
                    .getOrDefault(info.packageName)
                index[app.uid] = info.packageName to label
            }
        }
        return index
    }

    /* ------------------------------ plumbing ------------------------------ */

    private fun telephonyFor(subId: Int?): TelephonyManager? {
        val base = context.getSystemService(TelephonyManager::class.java) ?: return null
        if (subId == null || subId < 0) return base
        return runCatching { base.createForSubscriptionId(subId) }.getOrDefault(base)
    }

    private fun activeSubscriptions(): List<SubscriptionInfo> = try {
        context.getSystemService(SubscriptionManager::class.java)?.activeSubscriptionInfoList.orEmpty()
    } catch (_: SecurityException) {
        emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    @Suppress("DEPRECATION")
    private fun locationEnabled(): Boolean = try {
        val lm = context.getSystemService(LocationManager::class.java)
        if (Build.VERSION.SDK_INT >= 28) {
            lm?.isLocationEnabled == true
        } else {
            Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.LOCATION_MODE,
                Settings.Secure.LOCATION_MODE_OFF,
            ) != Settings.Secure.LOCATION_MODE_OFF
        }
    } catch (_: Exception) {
        false
    }

    private fun dataStateName(state: Int): String = when (state) {
        0 -> "disconnected"
        1 -> "connecting"
        2 -> "connected"
        3 -> "suspended"
        else -> "unknown"
    }

    private fun restrictBackgroundName(status: Int?): String = when (status) {
        null -> "unknown"
        ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED -> "disabled"
        ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED -> "whitelisted"
        ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED -> "enabled"
        else -> "unknown"
    }
}
