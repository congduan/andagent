package com.dc.andagent.tools

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.MealType
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateRequest as AggregateRequestT
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * Health Connect 全量读取：步数、心率、睡眠、血氧、体重、运动记录、营养。
 *
 * [HealthConnectClient] 是 suspend API；每次读取前检查对应权限，未授权时
 * 触发系统授权页（ MainActivity 的 healthPermissionRequester 请求全量
 * 只读权限集），失败则返回中文提示而不是异常。
 */

private const val HEALTH_CONNECT_PACKAGE = "com.google.android.apps.healthdata"
private const val HEALTH_MARKET_LINK = "market://details?id=$HEALTH_CONNECT_PACKAGE"

/** 全量只读权限集：工具按需检查单项，授权页一次性请求全部。 */
val HEALTH_READ_PERMISSIONS: Set<String> = setOf(
    HealthPermission.getReadPermission(StepsRecord::class),
    HealthPermission.getReadPermission(HeartRateRecord::class),
    HealthPermission.getReadPermission(SleepSessionRecord::class),
    HealthPermission.getReadPermission(OxygenSaturationRecord::class),
    HealthPermission.getReadPermission(WeightRecord::class),
    HealthPermission.getReadPermission(ExerciseSessionRecord::class),
    HealthPermission.getReadPermission(NutritionRecord::class),
)

private sealed class HealthReady {
    data class Ready(val client: HealthConnectClient) : HealthReady()
    data class NotReady(val message: String) : HealthReady()
}

private suspend fun healthReady(context: Context, permission: String, label: String): HealthReady {
    val status = runCatching { HealthConnectClient.getSdkStatus(context) }.getOrElse { -1 }
    when (status) {
        HealthConnectClient.SDK_UNAVAILABLE -> return HealthReady.NotReady(
            "ERROR: 未安装 Health Connect。Android 13 及以下请先安装 Health Connect 应用（$HEALTH_MARKET_LINK）",
        )
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> return HealthReady.NotReady(
            "ERROR: Health Connect 需要更新，请在应用商店更新后再试（$HEALTH_MARKET_LINK）",
        )
    }
    val client = runCatching { HealthConnectClient.getOrCreate(context) }.getOrElse {
        return HealthReady.NotReady(
            "ERROR: 无法连接 Health Connect：${it.message ?: it.javaClass.simpleName}",
        )
    }
    val granted = runCatching {
        client.permissionController.getGrantedPermissions()
    }.getOrElse {
        return HealthReady.NotReady(
            "ERROR: 无法读取 Health Connect 权限状态：${it.message ?: it.javaClass.simpleName}",
        )
    }
    if (permission !in granted) {
        return HealthReady.NotReady(
            "ERROR: 未授予${label}读取权限。请打开设置页点击「授权健康数据」，或在 Health Connect 中允许 AndAgent 读取${label}",
        )
    }
    return HealthReady.Ready(client)
}

/** 统一入口：权限检查 →（缺权限时）弹授权页并复查 → 执行读取 → 统一兜底错误。 */
private suspend fun withHealthClient(
    context: Context,
    requestPermission: (suspend () -> Boolean)?,
    permission: String,
    label: String,
    block: suspend (HealthConnectClient) -> JsonObject,
): String {
    var ready = healthReady(context, permission, label)
    if (ready is HealthReady.NotReady &&
        ready.message.startsWith("ERROR: 未授予") &&
        requestPermission != null &&
        requestPermission()
    ) {
        ready = healthReady(context, permission, label)
    }
    if (ready is HealthReady.NotReady) return ready.message
    val client = (ready as HealthReady.Ready).client
    return runCatching { block(client).toString() }
        .getOrElse { "ERROR: 读取健康数据失败：${it.message ?: it.javaClass.simpleName}" }
}

/* ------------------------------ 通用小工具 ------------------------------ */

/** 第 offset 天（0 = 今天）的 [起, 止) Instant 区间。 */
private fun dayRange(zone: ZoneId, date: LocalDate): Pair<Instant, Instant> =
    date.atStartOfDay(zone).toInstant() to date.plusDays(1).atStartOfDay(zone).toInstant()

private fun fmt1(v: Double): String = "%.1f".format(v)

private suspend inline fun <reified T : Record> readRecords(
    client: HealthConnectClient,
    start: Instant,
    end: Instant,
): List<T> {
    if (!end.isAfter(start)) return emptyList()
    @Suppress("DEPRECATION")
    return client.readRecords(
        ReadRecordsRequest(T::class, TimeRangeFilter.between(start, end)),
    ).records
}

private fun exerciseTypeName(code: Int): String = when (code) {
    ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "步行"
    ExerciseSessionRecord.EXERCISE_TYPE_RUNNING -> "跑步"
    ExerciseSessionRecord.EXERCISE_TYPE_BIKING -> "骑行"
    ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> "动感单车"
    ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "徒步"
    ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> "公开水域游泳"
    ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL -> "泳池游泳"
    ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING -> "力量训练"
    ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING -> "HIIT"
    ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS -> "自重训练"
    ExerciseSessionRecord.EXERCISE_TYPE_YOGA -> "瑜伽"
    ExerciseSessionRecord.EXERCISE_TYPE_PILATES -> "普拉提"
    ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL -> "椭圆机"
    ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE -> "划船机"
    ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> "跑步机"
    ExerciseSessionRecord.EXERCISE_TYPE_DANCING -> "舞蹈"
    ExerciseSessionRecord.EXERCISE_TYPE_BASKETBALL -> "篮球"
    ExerciseSessionRecord.EXERCISE_TYPE_FOOTBALL_AMERICAN -> "美式橄榄球"
    ExerciseSessionRecord.EXERCISE_TYPE_SOCCER -> "足球"
    ExerciseSessionRecord.EXERCISE_TYPE_TENNIS -> "网球"
    ExerciseSessionRecord.EXERCISE_TYPE_BADMINTON -> "羽毛球"
    ExerciseSessionRecord.EXERCISE_TYPE_TABLE_TENNIS -> "乒乓球"
    ExerciseSessionRecord.EXERCISE_TYPE_VOLLEYBALL -> "排球"
    ExerciseSessionRecord.EXERCISE_TYPE_GOLF -> "高尔夫"
    ExerciseSessionRecord.EXERCISE_TYPE_BOXING -> "拳击"
    ExerciseSessionRecord.EXERCISE_TYPE_MARTIAL_ARTS -> "武术"
    ExerciseSessionRecord.EXERCISE_TYPE_ROCK_CLIMBING -> "攀岩"
    ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING -> "爬楼梯"
    ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE -> "登楼机"
    ExerciseSessionRecord.EXERCISE_TYPE_SKATING -> "滑冰"
    ExerciseSessionRecord.EXERCISE_TYPE_SKIING -> "滑雪"
    ExerciseSessionRecord.EXERCISE_TYPE_SNOWSHOEING -> "雪地行走"
    else -> "运动(#$code)"
}

private fun mealTypeName(code: Int): String = when (code) {
    MealType.MEAL_TYPE_BREAKFAST -> "早餐"
    MealType.MEAL_TYPE_LUNCH -> "午餐"
    MealType.MEAL_TYPE_DINNER -> "晚餐"
    MealType.MEAL_TYPE_SNACK -> "加餐"
    else -> "未指定"
}

/* ------------------------------ 步数（原有） ------------------------------ */

private suspend fun readSteps(client: HealthConnectClient, start: Instant, end: Instant): Long =
    readRecords<StepsRecord>(client, start, end).sumOf { it.count }

class StepsTodayTool(
    private val context: Context,
    private val requestPermission: (suspend () -> Boolean)? = null,
) : Tool {
    override val name = "steps_today"
    override val description = "读取今天的步数（Health Connect，需用户授权步数读取权限）。"
    override val parameters = schemaOf()

    override suspend fun execute(args: JsonObject): String = withHealthClient(
        context, requestPermission,
        HealthPermission.getReadPermission(StepsRecord::class), "步数",
    ) { client ->
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val (start, end) = dayRange(zone, today).let { it.first to Instant.now() }
        val steps = readSteps(client, start, end)
        buildJsonObject {
            put("date", today.toString())
            put("steps", steps)
            put("timezone", zone.id)
            put("note", "数据来自 Health Connect；若手机/手表未同步，数字可能偏低")
        }
    }
}

class StepsHistoryTool(
    private val context: Context,
    private val requestPermission: (suspend () -> Boolean)? = null,
) : Tool {
    override val name = "steps_history"
    override val description = "读取最近 N 天每日步数（Health Connect），默认 7 天，最多 30 天。"
    override val parameters = schemaOf(
        "days" to intParam("天数，默认 7，范围 1–30"),
    )

    override suspend fun execute(args: JsonObject): String = withHealthClient(
        context, requestPermission,
        HealthPermission.getReadPermission(StepsRecord::class), "步数",
    ) { client ->
        val days = (args.intArg("days") ?: 7).coerceIn(1, 30)
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        var total = 0L
        val daily = buildJsonArray {
            for (offset in (days - 1) downTo 0) {
                val date = today.minusDays(offset.toLong())
                val (start, end) = dayRange(zone, date)
                val steps = readSteps(client, start, end)
                total += steps
                add(buildJsonObject {
                    put("date", date.toString())
                    put("steps", steps)
                })
            }
        }
        buildJsonObject {
            put("days", days)
            put("timezone", zone.id)
            put("total", total)
            put("average", if (days > 0) "%.0f".format(total.toDouble() / days) else "0")
            put("daily", daily)
        }
    }
}

/* ------------------------------ 心率 ------------------------------ */

class HeartRateTool(
    private val context: Context,
    private val requestPermission: (suspend () -> Boolean)? = null,
) : Tool {
    override val name = "health_heart_rate"
    override val description =
        "读取最近 N 天的心率数据（Health Connect）：每日心率最低/平均/最高与静息心率，默认 7 天，最多 30 天。"
    override val parameters = schemaOf(
        "days" to intParam("天数，默认 7，范围 1–30"),
    )

    override suspend fun execute(args: JsonObject): String = withHealthClient(
        context, requestPermission,
        HealthPermission.getReadPermission(HeartRateRecord::class), "心率",
    ) { client ->
        val days = (args.intArg("days") ?: 7).coerceIn(1, 30)
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        var allMin = Int.MAX_VALUE
        var allMax = 0
        var allSum = 0L
        var allCount = 0L
        var restingSum = 0L
        var restingCount = 0L
        val daily = buildJsonArray {
            for (offset in (days - 1) downTo 0) {
                val date = today.minusDays(offset.toLong())
                val (start, end) = dayRange(zone, date)
                val samples = readRecords<HeartRateRecord>(client, start, end)
                    .flatMap { it.samples }
                    .map { it.beatsPerMinute.toInt() }
                val resting = readRecords<RestingHeartRateRecord>(client, start, end)
                    .maxByOrNull { it.time }?.beatsPerMinute
                if (samples.isNotEmpty()) {
                    allMin = minOf(allMin, samples.min())
                    allMax = maxOf(allMax, samples.max())
                    allSum += samples.sum()
                    allCount += samples.size
                }
                resting?.let { restingSum += it; restingCount++ }
                add(buildJsonObject {
                    put("date", date.toString())
                    put("samples", samples.size)
                    if (samples.isNotEmpty()) {
                        put("minBpm", samples.min())
                        put("avgBpm", fmt1(samples.average()))
                        put("maxBpm", samples.max())
                    }
                    if (resting != null) put("restingBpm", resting)
                })
            }
        }
        buildJsonObject {
            put("days", days)
            put("timezone", zone.id)
            if (allCount > 0) {
                put("overallMinBpm", allMin)
                put("overallAvgBpm", fmt1(allSum.toDouble() / allCount))
                put("overallMaxBpm", allMax)
            }
            if (restingCount > 0) put("restingAvgBpm", fmt1(restingSum.toDouble() / restingCount))
            put("daily", daily)
            put("note", "若手表/手环未与 Health Connect 同步，数据可能缺失或偏低")
        }
    }
}

/* ------------------------------ 睡眠 ------------------------------ */

class SleepTool(
    private val context: Context,
    private val requestPermission: (suspend () -> Boolean)? = null,
) : Tool {
    override val name = "health_sleep"
    override val description =
        "读取最近 N 天的睡眠记录（Health Connect）：每次睡眠起止、总时长与深睡/浅睡/REM/清醒分期分钟数，默认 7 天，最多 30 天。"
    override val parameters = schemaOf(
        "days" to intParam("天数，默认 7，范围 1–30"),
    )

    override suspend fun execute(args: JsonObject): String = withHealthClient(
        context, requestPermission,
        HealthPermission.getReadPermission(SleepSessionRecord::class), "睡眠",
    ) { client ->
        val days = (args.intArg("days") ?: 7).coerceIn(1, 30)
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val windowStart = today.minusDays((days - 1).toLong()).atStartOfDay(zone).toInstant()
        val sessions = readRecords<SleepSessionRecord>(client, windowStart, Instant.now())
            .sortedBy { it.startTime }
        var totalMinutes = 0L
        val daily = buildJsonArray {
            for (offset in (days - 1) downTo 0) {
                val date = today.minusDays(offset.toLong())
                val dayStart = date.atStartOfDay(zone)
                val dayEnd = date.plusDays(1).atStartOfDay(zone)
                // 睡眠会话归到「醒来那天」：与该日 0–24 点有重叠即计入。
                val daySessions = sessions.filter {
                    it.endTime > dayStart.toInstant() && it.startTime < dayEnd.toInstant()
                }
                if (daySessions.isEmpty()) continue
                var deep = 0L; var light = 0L; var rem = 0L; var awake = 0L; var total = 0L
                val items = buildJsonArray {
                    for (s in daySessions) {
                        val dur = java.time.Duration.between(s.startTime, s.endTime)
                        total += dur.toMinutes()
                        var d = 0L; var l = 0L; var r = 0L; var a = 0L
                        for (st in s.stages) {
                            val mins = java.time.Duration.between(st.startTime, st.endTime).toMinutes()
                            when (st.stage) {
                                SleepSessionRecord.STAGE_TYPE_DEEP -> d += mins
                                SleepSessionRecord.STAGE_TYPE_LIGHT -> l += mins
                                SleepSessionRecord.STAGE_TYPE_REM -> r += mins
                                SleepSessionRecord.STAGE_TYPE_AWAKE,
                                SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
                                SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> a += mins
                            }
                        }
                        deep += d; light += l; rem += r; awake += a
                        add(buildJsonObject {
                            put("start", s.startTime.toString())
                            put("end", s.endTime.toString())
                            put("durationMinutes", dur.toMinutes())
                            s.title?.takeIf { it.isNotBlank() }?.let { put("title", it) }
                            put("deepMinutes", d)
                            put("lightMinutes", l)
                            put("remMinutes", r)
                            put("awakeMinutes", a)
                        })
                    }
                }
                totalMinutes += total
                add(buildJsonObject {
                    put("date", date.toString())
                    put("totalMinutes", total)
                    put("deepMinutes", deep)
                    put("lightMinutes", light)
                    put("remMinutes", rem)
                    put("awakeMinutes", awake)
                    put("sessions", items)
                })
            }
        }
        buildJsonObject {
            put("days", days)
            put("timezone", zone.id)
            put("sessionCount", sessions.size)
            put("avgMinutes", if (days > 0) fmt1(totalMinutes.toDouble() / days) else "0")
            put("daily", daily)
        }
    }
}

/* ------------------------------ 血氧 ------------------------------ */

class OxygenSaturationTool(
    private val context: Context,
    private val requestPermission: (suspend () -> Boolean)? = null,
) : Tool {
    override val name = "health_spo2"
    override val description =
        "读取最近 N 天的血氧饱和度 SpO2（Health Connect）：每日最低/平均/最高百分比，默认 7 天，最多 30 天。"
    override val parameters = schemaOf(
        "days" to intParam("天数，默认 7，范围 1–30"),
    )

    override suspend fun execute(args: JsonObject): String = withHealthClient(
        context, requestPermission,
        HealthPermission.getReadPermission(OxygenSaturationRecord::class), "血氧",
    ) { client ->
        val days = (args.intArg("days") ?: 7).coerceIn(1, 30)
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val daily = buildJsonArray {
            for (offset in (days - 1) downTo 0) {
                val date = today.minusDays(offset.toLong())
                val (start, end) = dayRange(zone, date)
                val values = readRecords<OxygenSaturationRecord>(client, start, end)
                    .map { it.percentage.value }
                if (values.isEmpty()) continue
                add(buildJsonObject {
                    put("date", date.toString())
                    put("samples", values.size)
                    put("minPct", fmt1(values.min()))
                    put("avgPct", fmt1(values.average()))
                    put("maxPct", fmt1(values.max()))
                })
            }
        }
        buildJsonObject {
            put("days", days)
            put("timezone", zone.id)
            put("daily", daily)
            put("note", "血氧读数常来自夜间手表监测；缺测不代表异常")
        }
    }
}

/* ------------------------------ 体重 ------------------------------ */

class WeightTool(
    private val context: Context,
    private val requestPermission: (suspend () -> Boolean)? = null,
) : Tool {
    override val name = "health_weight"
    override val description =
        "读取最近 N 天的体重记录（Health Connect）：逐条读数与最新值、区间变化，默认 30 天，最多 90 天。"
    override val parameters = schemaOf(
        "days" to intParam("天数，默认 30，范围 1–90"),
    )

    override suspend fun execute(args: JsonObject): String = withHealthClient(
        context, requestPermission,
        HealthPermission.getReadPermission(WeightRecord::class), "体重",
    ) { client ->
        val days = (args.intArg("days") ?: 30).coerceIn(1, 90)
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val windowStart = today.minusDays((days - 1).toLong()).atStartOfDay(zone).toInstant()
        val records = readRecords<WeightRecord>(client, windowStart, Instant.now())
            .sortedBy { it.time }
        val kgList = records.map { it.weight.inKilograms }
        buildJsonObject {
            put("days", days)
            put("timezone", zone.id)
            put("count", records.size)
            if (kgList.isNotEmpty()) {
                put("latestKg", fmt1(kgList.last()))
                put("latestTime", records.last().time.toString())
                put("firstKg", fmt1(kgList.first()))
                put("deltaKg", fmt1(kgList.last() - kgList.first()))
            }
            put("records", buildJsonArray {
                for ((i, r) in records.withIndex()) add(buildJsonObject {
                    put("time", r.time.toString())
                    put("kg", fmt1(kgList[i]))
                })
            })
        }
    }
}

/* ------------------------------ 运动记录 ------------------------------ */

private suspend fun sessionMetrics(
    client: HealthConnectClient,
    start: Instant,
    end: Instant,
): Pair<Double, Double> {
    val result = runCatching {
        client.aggregate(
            AggregateRequestT(
                metrics = setOf(
                    DistanceRecord.DISTANCE_TOTAL,
                    TotalCaloriesBurnedRecord.ENERGY_TOTAL,
                ),
                timeRangeFilter = TimeRangeFilter.between(start, end),
            ),
        )
    }.getOrNull()
    val km = result?.get(DistanceRecord.DISTANCE_TOTAL)?.inKilometers ?: 0.0
    val kcal = result?.get(TotalCaloriesBurnedRecord.ENERGY_TOTAL)?.inKilocalories ?: 0.0
    return km to kcal
}

class ExerciseSessionTool(
    private val context: Context,
    private val requestPermission: (suspend () -> Boolean)? = null,
) : Tool {
    override val name = "health_exercise"
    override val description =
        "读取最近 N 天的运动记录（Health Connect）：每次运动的类型、起止、时长、距离与消耗，默认 7 天，最多 30 天。"
    override val parameters = schemaOf(
        "days" to intParam("天数，默认 7，范围 1–30"),
    )

    override suspend fun execute(args: JsonObject): String = withHealthClient(
        context, requestPermission,
        HealthPermission.getReadPermission(ExerciseSessionRecord::class), "运动记录",
    ) { client ->
        val days = (args.intArg("days") ?: 7).coerceIn(1, 30)
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val windowStart = today.minusDays((days - 1).toLong()).atStartOfDay(zone).toInstant()
        val sessions = readRecords<ExerciseSessionRecord>(client, windowStart, Instant.now())
            .sortedBy { it.startTime }
        var totalMinutes = 0L
        var totalKm = 0.0
        var totalKcal = 0.0
        val items = buildJsonArray {
            for (s in sessions) {
                val dur = java.time.Duration.between(s.startTime, s.endTime)
                totalMinutes += dur.toMinutes()
                val (km, kcal) = sessionMetrics(client, s.startTime, s.endTime)
                totalKm += km
                totalKcal += kcal
                add(buildJsonObject {
                    put("type", exerciseTypeName(s.exerciseType))
                    put("start", s.startTime.toString())
                    put("end", s.endTime.toString())
                    put("durationMinutes", dur.toMinutes())
                    if (km > 0.0) put("distanceKm", fmt1(km))
                    if (kcal > 0.0) put("calories", fmt1(kcal))
                    s.title?.takeIf { it.isNotBlank() }?.let { put("title", it) }
                })
            }
        }
        buildJsonObject {
            put("days", days)
            put("timezone", zone.id)
            put("sessionCount", sessions.size)
            put("totalMinutes", totalMinutes)
            put("totalDistanceKm", fmt1(totalKm))
            put("totalCalories", fmt1(totalKcal))
            put("sessions", items)
        }
    }
}

/* ------------------------------ 营养 ------------------------------ */

class NutritionTool(
    private val context: Context,
    private val requestPermission: (suspend () -> Boolean)? = null,
) : Tool {
    override val name = "health_nutrition"
    override val description =
        "读取最近 N 天的饮食记录（Health Connect）：每餐名称、餐次、热量与蛋白质/脂肪/碳水，并汇总每日摄入，默认 7 天，最多 30 天。"
    override val parameters = schemaOf(
        "days" to intParam("天数，默认 7，范围 1–30"),
    )

    override suspend fun execute(args: JsonObject): String = withHealthClient(
        context, requestPermission,
        HealthPermission.getReadPermission(NutritionRecord::class), "营养",
    ) { client ->
        val days = (args.intArg("days") ?: 7).coerceIn(1, 30)
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        var loggedDays = 0
        var kcalSum = 0.0
        val daily = buildJsonArray {
            for (offset in (days - 1) downTo 0) {
                val date = today.minusDays(offset.toLong())
                val (start, end) = dayRange(zone, date)
                val records = readRecords<NutritionRecord>(client, start, end)
                if (records.isEmpty()) continue
                loggedDays++
                var kcal = 0.0; var protein = 0.0; var fat = 0.0; var carb = 0.0
                val meals = buildJsonArray {
                    for (r in records) {
                        r.energy?.let { kcal += it.inKilocalories }
                        r.protein?.let { protein += it.inGrams }
                        r.totalFat?.let { fat += it.inGrams }
                        r.totalCarbohydrate?.let { carb += it.inGrams }
                        add(buildJsonObject {
                            put("mealType", mealTypeName(r.mealType))
                            if (!r.name.isNullOrBlank()) put("name", r.name)
                            r.energy?.let { put("calories", fmt1(it.inKilocalories)) }
                            r.protein?.let { put("proteinG", fmt1(it.inGrams)) }
                            r.totalFat?.let { put("fatG", fmt1(it.inGrams)) }
                            r.totalCarbohydrate?.let { put("carbG", fmt1(it.inGrams)) }
                        })
                    }
                }
                kcalSum += kcal
                add(buildJsonObject {
                    put("date", date.toString())
                    put("calories", fmt1(kcal))
                    put("proteinG", fmt1(protein))
                    put("fatG", fmt1(fat))
                    put("carbG", fmt1(carb))
                    put("meals", meals)
                })
            }
        }
        buildJsonObject {
            put("days", days)
            put("timezone", zone.id)
            put("loggedDays", loggedDays)
            put("avgDailyCalories", if (loggedDays > 0) fmt1(kcalSum / loggedDays) else "0")
            put("daily", daily)
        }
    }
}

/* ------------------------------ 周报聚合 ------------------------------ */

class HealthWeeklyReportTool(
    private val context: Context,
    private val requestPermission: (suspend () -> Boolean)? = null,
) : Tool {
    override val name = "health_weekly_report"
    override val description =
        "聚合最近 N 天（默认 7，最多 28）的健康数据（Health Connect）：步数、睡眠、心率、血氧、体重、运动、营养的每日汇总与环比趋势，用于生成周报/健康趋势/运动建议。数据为空的部分会标 hasData=false，叙述请由会话生成。"
    override val parameters = schemaOf(
        "days" to intParam("天数，默认 7，范围 1–28"),
    )

    override suspend fun execute(args: JsonObject): String = withHealthClient(
        context, requestPermission,
        HealthPermission.getReadPermission(StepsRecord::class), "健康数据",
    ) { client ->
        val days = (args.intArg("days") ?: 7).coerceIn(1, 28)
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val windowStart = today.minusDays((days - 1).toLong()).atStartOfDay(zone).toInstant()
        val now = Instant.now()
        val prevStart = today.minusDays((2L * days - 1)).atStartOfDay(zone).toInstant()
        val prevEnd = today.minusDays((days - 1).toLong()).atStartOfDay(zone).toInstant()

        // 步数（含上一周期用于环比）
        val stepsDaily = mutableListOf<Pair<LocalDate, Long>>()
        var stepsWindow = 0L
        for (offset in (days - 1) downTo 0) {
            val date = today.minusDays(offset.toLong())
            val (start, end) = dayRange(zone, date)
            val steps = readSteps(client, start, end)
            stepsWindow += steps
            stepsDaily.add(date to steps)
        }
        var stepsPrev = 0L
        runCatching {
            readRecords<StepsRecord>(client, prevStart, prevEnd).forEach { stepsPrev += it.count }
        }

        // 睡眠（按醒来日聚合）
        val sleepSessions = runCatching {
            readRecords<SleepSessionRecord>(client, windowStart, now)
        }.getOrDefault(emptyList())
        val sleepByDay = sleepSessions.groupBy {
            java.time.LocalDateTime.ofInstant(it.endTime, zone).toLocalDate()
        }
        val sleepDaily = sleepByDay.entries.sortedBy { it.key }.map { (date, list) ->
            val mins = list.sumOf { java.time.Duration.between(it.startTime, it.endTime).toMinutes() }
            date to mins
        }
        val sleepTotal = sleepDaily.sumOf { it.second }

        // 心率 / 血氧
        val hrSamples = runCatching {
            readRecords<HeartRateRecord>(client, windowStart, now)
                .flatMap { it.samples }
                .map { it.beatsPerMinute.toInt() }
        }.getOrDefault(emptyList())
        val resting = runCatching {
            readRecords<RestingHeartRateRecord>(client, windowStart, now).map { it.beatsPerMinute }
        }.getOrDefault(emptyList())
        val spo2 = runCatching {
            readRecords<OxygenSaturationRecord>(client, windowStart, now).map { it.percentage.value }
        }.getOrDefault(emptyList())

        // 体重
        val weights = runCatching {
            readRecords<WeightRecord>(client, windowStart, now).sortedBy { it.time }
        }.getOrDefault(emptyList())

        // 运动
        val exercise = runCatching {
            readRecords<ExerciseSessionRecord>(client, windowStart, now).sortedBy { it.startTime }
        }.getOrDefault(emptyList())
        var exMinutes = 0L; var exKm = 0.0; var exKcal = 0.0
        val exItems = buildJsonArray {
            for (s in exercise) {
                val dur = java.time.Duration.between(s.startTime, s.endTime)
                exMinutes += dur.toMinutes()
                val (km, kcal) = sessionMetrics(client, s.startTime, s.endTime)
                exKm += km; exKcal += kcal
                add(buildJsonObject {
                    put("type", exerciseTypeName(s.exerciseType))
                    put("start", s.startTime.toString())
                    put("durationMinutes", dur.toMinutes())
                    if (km > 0.0) put("distanceKm", fmt1(km))
                    if (kcal > 0.0) put("calories", fmt1(kcal))
                })
            }
        }

        // 营养
        var loggedDays = 0
        var kcalSum = 0.0
        val nutritionDaily = buildJsonArray {
            for (offset in (days - 1) downTo 0) {
                val date = today.minusDays(offset.toLong())
                val (start, end) = dayRange(zone, date)
                val records = runCatching {
                    readRecords<NutritionRecord>(client, start, end)
                }.getOrDefault(emptyList())
                if (records.isEmpty()) continue
                loggedDays++
                var kcal = 0.0; var protein = 0.0; var fat = 0.0; var carb = 0.0
                for (r in records) {
                    r.energy?.let { kcal += it.inKilocalories }
                    r.protein?.let { protein += it.inGrams }
                    r.totalFat?.let { fat += it.inGrams }
                    r.totalCarbohydrate?.let { carb += it.inGrams }
                }
                kcalSum += kcal
                add(buildJsonObject {
                    put("date", date.toString())
                    put("calories", fmt1(kcal))
                    put("proteinG", fmt1(protein))
                    put("fatG", fmt1(fat))
                    put("carbG", fmt1(carb))
                })
            }
        }

        // 趋势（环比）
        val stepsDeltaPct = if (stepsPrev > 0) (stepsWindow - stepsPrev) * 100.0 / stepsPrev else null
        val sleepPrevMinutes = runCatching {
            val prevSessions = readRecords<SleepSessionRecord>(client, prevStart, prevEnd)
            prevSessions.sumOf { java.time.Duration.between(it.startTime, it.endTime).toMinutes() }
        }.getOrDefault(0L)
        val sleepDeltaMin = if (sleepPrevMinutes > 0) sleepTotal - sleepPrevMinutes else null
        val weightDeltaKg = if (weights.size >= 2) {
            weights.last().weight.inKilograms - weights.first().weight.inKilograms
        } else null

        buildJsonObject {
            put("days", days)
            put("timezone", zone.id)
            put("windowStart", windowStart.toString())
            put("windowEnd", now.toString())
            put("steps", buildJsonObject {
                put("hasData", stepsWindow > 0 || stepsPrev > 0)
                put("total", stepsWindow)
                put("average", if (days > 0) fmt1(stepsWindow.toDouble() / days) else "0")
                put("prevTotal", stepsPrev)
                stepsDeltaPct?.let { put("deltaPct", fmt1(it)) }
                put("daily", buildJsonArray {
                    for ((d, s) in stepsDaily) add(buildJsonObject {
                        put("date", d.toString()); put("steps", s)
                    })
                })
            })
            put("sleep", buildJsonObject {
                put("hasData", sleepDaily.isNotEmpty())
                put("sessionCount", sleepSessions.size)
                put("avgMinutes", if (days > 0) fmt1(sleepTotal.toDouble() / days) else "0")
                sleepDeltaMin?.let { put("deltaMinutesPrevPeriod", it) }
                put("daily", buildJsonArray {
                    for ((d, m) in sleepDaily) add(buildJsonObject {
                        put("date", d.toString()); put("totalMinutes", m)
                    })
                })
            })
            put("heartRate", buildJsonObject {
                put("hasData", hrSamples.isNotEmpty())
                if (hrSamples.isNotEmpty()) {
                    put("minBpm", hrSamples.min())
                    put("avgBpm", fmt1(hrSamples.average()))
                    put("maxBpm", hrSamples.max())
                }
                if (resting.isNotEmpty()) put("restingAvgBpm", fmt1(resting.average()))
            })
            put("spo2", buildJsonObject {
                put("hasData", spo2.isNotEmpty())
                if (spo2.isNotEmpty()) {
                    put("minPct", fmt1(spo2.min()))
                    put("avgPct", fmt1(spo2.average()))
                }
            })
            put("weight", buildJsonObject {
                put("hasData", weights.isNotEmpty())
                if (weights.isNotEmpty()) {
                    put("latestKg", fmt1(weights.last().weight.inKilograms))
                    put("latestTime", weights.last().time.toString())
                    weightDeltaKg?.let { put("deltaKg", fmt1(it)) }
                }
            })
            put("exercise", buildJsonObject {
                put("hasData", exercise.isNotEmpty())
                put("sessionCount", exercise.size)
                put("totalMinutes", exMinutes)
                put("totalDistanceKm", fmt1(exKm))
                put("totalCalories", fmt1(exKcal))
                put("sessions", exItems)
            })
            put("nutrition", buildJsonObject {
                put("hasData", loggedDays > 0)
                put("loggedDays", loggedDays)
                if (loggedDays > 0) put("avgDailyCalories", fmt1(kcalSum / loggedDays))
                put("daily", nutritionDaily)
            })
            put("note", "本工具只提供聚合数据与环比；周报叙述、趋势解读与运动建议由会话生成，缺数据的维度不要编造")
        }
    }
}
