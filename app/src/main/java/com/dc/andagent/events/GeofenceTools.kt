package com.dc.andagent.events

import android.Manifest
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.dc.andagent.bridge.PermissionBroker
import com.dc.andagent.tools.KvStore
import com.dc.andagent.tools.Tool
import com.dc.andagent.tools.intArg
import com.dc.andagent.tools.intParam
import com.dc.andagent.tools.numParam
import com.dc.andagent.tools.schemaOf
import com.dc.andagent.tools.strArg
import com.dc.andagent.tools.strParam
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

val GEOFENCE_TOOLS = listOf("geofence_on", "geofence_off", "geofence_list", "geofence_read")

internal const val GEOFENCE_CHANNEL = "geofence"
internal const val GEOFENCE_NAMESPACE = "geofence"
internal const val GEOFENCE_EVENTS_NAMESPACE = "geofence_events"

/**
 * Pure geofence arithmetic, deliberately free of Android types so it can run
 * on a plain JVM. `GeofenceMathTest` covers the haversine distances, the
 * enter/exit matrix and the two-sample debounce.
 */
internal object GeofenceMath {
    /** Accepted radius range in metres (validated/clamped by the tool). */
    const val RADIUS_MIN_M = 50
    const val RADIUS_MAX_M = 100_000

    /** Two accepted readings must be at least this far apart (monotonic ms). */
    const val MIN_SAMPLE_INTERVAL_MS = 10_000L

    /** A lastKnownLocation fix older than this is too stale to seed inside/outside. */
    const val BASELINE_MAX_AGE_MS = 600_000L
    /** A fix with worse accuracy than this is too noisy to seed inside/outside. */
    const val BASELINE_MAX_ACCURACY_M = 200f
    /** Treat fixes up to this far in the future as clock jitter, not as valid. */
    const val BASELINE_MAX_CLOCK_SKEW_MS = 5_000L

    /** Mean Earth radius used by R85's CellularCodec, kept identical here. */
    private const val EARTH_RADIUS_M = 6_371_008.8

    /** Great-circle distance in metres between two WGS-84 points. */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val s1 = sin(dLat / 2)
        val s2 = sin(dLon / 2)
        val h = s1 * s1 + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * s2 * s2
        return 2 * EARTH_RADIUS_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    fun isInside(lat: Double, lon: Double, fence: FenceSpec): Boolean =
        distanceMeters(lat, lon, fence.lat, fence.lon) <= fence.radiusM

    fun classify(lat: Double, lon: Double, fence: FenceSpec): String =
        if (isInside(lat, lon, fence)) "inside" else "outside"

    /** Returns "enter"/"exit" when a confirmed crossing should be recorded. */
    fun shouldFire(previousInside: Boolean?, nowInside: Boolean, events: String): String? {
        if (previousInside == null || previousInside == nowInside) return null
        val type = if (nowInside) "enter" else "exit"
        return if (events == "both" || events == type) type else null
    }

    /**
     * Two-sample debounce. A state change only fires after two consecutive
     * readings agree; a single GPS drift changes the candidate but never the
     * persisted state.
     */
    fun observe(
        previousInside: Boolean?,
        candidateInside: Boolean?,
        nowInside: Boolean,
        events: String,
    ): FenceTransition {
        if (previousInside == null) return FenceTransition(nowInside, null, null)
        if (candidateInside == null || candidateInside != nowInside) {
            return FenceTransition(previousInside, nowInside, null)
        }
        return FenceTransition(nowInside, null, shouldFire(previousInside, nowInside, events))
    }

    /** Sampling rate guard: ignore readings less than 10 s after the last one. */
    fun shouldEvaluate(lastSampleMonoMs: Long?, nowMonoMs: Long): Boolean =
        lastSampleMonoMs == null || nowMonoMs - lastSampleMonoMs >= MIN_SAMPLE_INTERVAL_MS

    /**
     * Baseline freshness gate for `geofence_on`. A cached fix that is minutes
     * old (or wildly imprecise) would seed a false enter/exit baseline, so it
     * is rejected and the engine falls back to establishing state from its
     * first live sample instead.
     *
     * [nowWallMs] and [locationTimeMs] are both UTC wall-clock millis; passing
     * null [accuracyM] means the fix has no accuracy estimate.
     */
    fun acceptableBaseline(nowWallMs: Long, locationTimeMs: Long, accuracyM: Float?): Boolean {
        if (accuracyM == null || accuracyM.isNaN() || accuracyM > BASELINE_MAX_ACCURACY_M) return false
        val age = nowWallMs - locationTimeMs
        return age in -BASELINE_MAX_CLOCK_SKEW_MS..BASELINE_MAX_AGE_MS
    }

    fun round1(value: Double): Double = round(value * 10.0) / 10.0

    fun normalizeEvents(raw: String?): String? = when (raw?.trim()?.lowercase(Locale.US)) {
        null, "", "both" -> "both"
        "enter", "exit" -> raw.trim().lowercase(Locale.US)
        else -> null
    }
}

internal data class FenceTransition(
    val lastInside: Boolean?,
    val candidateInside: Boolean?,
    val fire: String?,
)

/**
 * One armed fence. Persisted as a JSON object in the `geofence/fences` KV
 * array; `lastInside` is the last confirmed state, initialized by the
 * immediate `getLastKnownLocation` probe in `geofence_on`.
 */
internal data class FenceSpec(
    val name: String,
    val lat: Double,
    val lon: Double,
    val radiusM: Int,
    val events: String = "both",
    val lastInside: Boolean? = null,
    val lastInsideAt: Long? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("name", name)
        put("lat", lat)
        put("lon", lon)
        put("radiusM", radiusM)
        put("events", events)
        put("lastInside", lastInside?.let { JsonPrimitive(it) } ?: JsonNull)
        put("lastInsideAt", lastInsideAt?.let { JsonPrimitive(it) } ?: JsonNull)
    }

    companion object {
        fun fromJson(value: JsonElement?): FenceSpec? {
            val obj = value as? JsonObject ?: return null
            val name = (obj["name"] as? JsonPrimitive)?.content?.trim()
                ?.takeIf { it.isNotEmpty() && it.length <= 64 } ?: return null
            val lat = (obj["lat"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: return null
            val lon = (obj["lon"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: return null
            val radius = (obj["radiusM"] as? JsonPrimitive)?.content?.toIntOrNull() ?: return null
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
            val events = GeofenceMath.normalizeEvents((obj["events"] as? JsonPrimitive)?.content) ?: "both"
            val inside = (obj["lastInside"] as? JsonPrimitive)?.booleanOrNull
            val at = (obj["lastInsideAt"] as? JsonPrimitive)?.longOrNull
            return FenceSpec(
                name = name,
                lat = lat,
                lon = lon,
                radiusM = radius.coerceIn(GeofenceMath.RADIUS_MIN_M, GeofenceMath.RADIUS_MAX_M),
                events = events,
                lastInside = inside,
                lastInsideAt = at,
            )
        }
    }
}

/**
 * JSON codec + KV persistence for the armed fence list (max 5).
 *
 * Every read-modify-write runs under `synchronized(FenceStore)`; the location
 * engine writes confirmed state back from the main thread while `geofence_on`
 * / `geofence_off` mutate the list from a tool thread. Sharing one monitor
 * means the engine can never overwrite a disarmed or re-armed list with its
 * stale in-memory copy.
 */
internal object FenceStore {
    const val KEY = "fences"
    const val MAX_FENCES = 5

    fun load(context: Context): List<FenceSpec> = synchronized(FenceStore) {
        runCatching {
            decode(KvStore(context).get(GEOFENCE_NAMESPACE, KEY))
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, fences: List<FenceSpec>) {
        synchronized(FenceStore) {
            KvStore(context).set(GEOFENCE_NAMESPACE, KEY, encode(fences))
        }
    }

    /** Corrupt JSON (or a non-array) degrades to an empty list, never crashes. */
    fun decode(value: JsonElement?): List<FenceSpec> {
        val array = value as? JsonArray ?: return emptyList()
        return array.mapNotNull { FenceSpec.fromJson(it) }.take(MAX_FENCES)
    }

    fun encode(fences: List<FenceSpec>): JsonArray = buildJsonArray {
        fences.take(MAX_FENCES).forEach { add(it.toJson()) }
    }

    /** Replacing an existing name is always allowed; a sixth name is rejected. */
    fun addError(fences: List<FenceSpec>, name: String): String? = synchronized(FenceStore) {
        if (fences.none { it.name == name } && fences.size >= MAX_FENCES) {
            "ERROR: 围栏数量已达上限 $MAX_FENCES，请先 geofence_off 撤防旧围栏"
        } else {
            null
        }
    }
}

/**
 * Foreground location listener. Lives inside [EventMonitorService] and is
 * restarted whenever the service receives a start command. It uses the same
 * `LocationManager.requestLocationUpdates` pattern as R84's location_stream.
 */
internal class GeofenceEngine(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val locationManager: LocationManager? = context.getSystemService(LocationManager::class.java)
    private val candidates = mutableMapOf<String, Boolean?>()
    private val lastSampleMonoMs = mutableMapOf<String, Long>()
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onSample(location)

        @Deprecated("deprecated in api30")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}

        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    var fences: List<FenceSpec> = emptyList()
        private set

    var listening = false
        private set

    var lastError: String? = null
        private set

    fun start(): Boolean = synchronized(FenceStore) {
        fences = FenceStore.load(context)
        if (fences.isEmpty()) {
            lastError = "没有已布防的围栏，前台服务不会保持定位监听"
            return false
        }
        val lm = locationManager
        if (lm == null) {
            lastError = "设备无 LocationManager 服务"
            return false
        }
        // One registration per provider; with several fences use the smallest
        // radius/3 so the tightest fence still receives enough updates.
        val minDistance = (fences.minOf { it.radiusM } / 3f).coerceAtLeast(0f)
        var registered = 0
        runCatching {
            @Suppress("DEPRECATION")
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, GeofenceMath.MIN_SAMPLE_INTERVAL_MS, minDistance, listener, handler.looper)
            registered++
        }
        runCatching {
            @Suppress("DEPRECATION")
            lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, GeofenceMath.MIN_SAMPLE_INTERVAL_MS, minDistance, listener, handler.looper)
            registered++
        }
        listening = registered > 0
        lastError = if (listening) null else "无法注册定位监听（定位权限被拒或设备不支持）"
        listening
    }

    fun stop() {
        runCatching { locationManager?.removeUpdates(listener) }
        synchronized(FenceStore) {
            listening = false
            candidates.clear()
            lastSampleMonoMs.clear()
        }
    }

    /**
     * Main-thread location callback. The persisted fence list is re-read under
     * the [FenceStore] monitor on every sample rather than trusting the copy
     * captured when the engine started: a tool may have disarmed, replaced or
     * re-armed fences in between, and writing the stale list back would undo
     * that.
     */
    private fun onSample(location: Location) {
        val nowMono = SystemClock.elapsedRealtime()
        val nowWall = System.currentTimeMillis()
        synchronized(FenceStore) {
            FenceStore.load(context).let { current ->
                fences = current
                if (current.isEmpty()) return
                // A fence disarmed since the last sample must not fire or be
                // written back; drop any debounce state it left behind.
                val names = current.map { it.name }.toSet()
                candidates.keys.retainAll(names)
                lastSampleMonoMs.keys.retainAll(names)

                var changed = false
                val updated = current.map { fence ->
                    if (!GeofenceMath.shouldEvaluate(lastSampleMonoMs[fence.name], nowMono)) return@map fence
                    lastSampleMonoMs[fence.name] = nowMono
                    val distance = GeofenceMath.distanceMeters(location.latitude, location.longitude, fence.lat, fence.lon)
                    val nowInside = distance <= fence.radiusM
                    val transition = GeofenceMath.observe(fence.lastInside, candidates[fence.name], nowInside, fence.events)
                    candidates[fence.name] = transition.candidateInside
                    if (transition.fire != null) {
                        EventStore.put(context, GEOFENCE_EVENTS_NAMESPACE, EventStore.event(transition.fire, buildJsonObject {
                            put("name", fence.name)
                            put("lat", location.latitude)
                            put("lon", location.longitude)
                            put("distanceM", GeofenceMath.round1(distance))
                            put("radiusM", fence.radiusM)
                        }))
                    }
                    if (transition.lastInside != fence.lastInside) {
                        changed = true
                        fence.copy(lastInside = transition.lastInside, lastInsideAt = nowWall)
                    } else {
                        fence
                    }
                }
                if (changed) {
                    runCatching { FenceStore.save(context, updated) }
                        .onSuccess { fences = updated }
                        .onFailure { lastError = "围栏状态写入失败：${it.message ?: it.javaClass.simpleName}" }
                }
            }
        }
    }
}

class GeofenceTool(
    private val context: Context,
    private val permissions: PermissionBroker,
    override val name: String,
) : Tool {
    override val description = when (name) {
        "geofence_on" -> "布防地理围栏：进入/离开中心点半径范围时写入 geofence_events KV，供 live 轮询或 cron 消费。前台监听模式：app 退出或前台服务被系统杀死后围栏可能失效（系统可能自动重启服务恢复，不保证）；重开 app 或重新 geofence_on 可确保恢复。不做开机持久化（重启手机后需重新布防）。最多 5 个围栏，同名覆盖。"
        "geofence_off" -> "撤防地理围栏：指定 name 只撤该围栏，省略 name 撤全部；全部撤完停止前台服务。"
        "geofence_list" -> "列出当前布防的围栏：中心、半径、事件类型、启用/监听状态及最近一次已确认的圈内外状态。"
        else -> "读取最近地理围栏的 enter/exit 事件，可按围栏名过滤；事件来自 geofence_events KV 的 history。"
    }
    override val parameters = when (name) {
        "geofence_on" -> schemaOf(
            "lat" to numParam("围栏中心纬度，-90..90"),
            "lon" to numParam("围栏中心经度，-180..180"),
            "radius_m" to intParam("围栏半径米，范围 50-100000（超出会钳制）"),
            "name" to strParam("围栏名称，默认 geofence；同名覆盖旧围栏"),
            "events" to strParam("触发事件：enter、exit 或 both，默认 both"),
            required = listOf("lat", "lon", "radius_m"),
        )
        "geofence_off" -> schemaOf("name" to strParam("要撤防的围栏名；省略撤全部"))
        "geofence_list" -> schemaOf()
        else -> schemaOf(
            "duration_min" to intParam("最近分钟数，默认 10，范围 1-1440"),
            "name" to strParam("可选围栏名过滤"),
        )
    }

    override suspend fun execute(args: JsonObject): String = try {
        when (name) {
            "geofence_on" -> arm(args)
            "geofence_off" -> disarm(args)
            "geofence_list" -> list()
            "geofence_read" -> read(args)
            else -> "ERROR: 未知工具"
        }
    } catch (e: Exception) {
        "ERROR: ${e.message ?: e.javaClass.simpleName}"
    }

    private suspend fun arm(args: JsonObject): String {
        if (!permissions.ensure(listOf(Manifest.permission.ACCESS_FINE_LOCATION))) {
            return "ERROR: 地理围栏需要精确定位权限（ACCESS_FINE_LOCATION），请在系统设置授权后重试"
        }
        val lm = context.getSystemService(LocationManager::class.java)
            ?: return "ERROR: 设备无 LocationManager 服务"
        val providerOn = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).any {
            runCatching { lm.isProviderEnabled(it) }.getOrDefault(false)
        }
        if (!providerOn) return "ERROR: 系统定位开关未开启（GPS 与网络定位均不可用），请先打开定位后重试"

        val lat = doubleArg(args, "lat") ?: return "ERROR: lat 必须是 -90..90 的数字"
        val lon = doubleArg(args, "lon") ?: return "ERROR: lon 必须是 -180..180 的数字"
        if (lat !in -90.0..90.0) return "ERROR: lat 必须在 -90..90 之间"
        if (lon !in -180.0..180.0) return "ERROR: lon 必须在 -180..180 之间"
        val radius = args.intArg("radius_m") ?: return "ERROR: radius_m 为必填整数（米）"
        if (radius <= 0) return "ERROR: radius_m 必须为正整数（米）"
        val clampedRadius = radius.coerceIn(GeofenceMath.RADIUS_MIN_M, GeofenceMath.RADIUS_MAX_M)
        val fenceName = (args.strArg("name") ?: "geofence").trim()
        if (fenceName.isEmpty() || fenceName.length > 64) return "ERROR: name 长度必须为 1-64 个字符"
        val events = GeofenceMath.normalizeEvents(args.strArg("events"))
            ?: return "ERROR: events 仅支持 enter、exit 或 both"

        val last = lastKnownLocation(lm)
        // Only a fresh, precise cached fix may seed the confirmed inside/outside
        // state; a stale one would invent a false enter/exit baseline. Without a
        // usable fix the fence starts with lastInside=null and the live listener
        // establishes the baseline on its first sample.
        val baseline = last?.takeIf {
            GeofenceMath.acceptableBaseline(
                nowWallMs = System.currentTimeMillis(),
                locationTimeMs = it.time,
                accuracyM = if (it.hasAccuracy()) it.accuracy else null,
            )
        }
        val initialInside = baseline?.let { GeofenceMath.distanceMeters(it.latitude, it.longitude, lat, lon) <= clampedRadius }
        val spec = FenceSpec(
            name = fenceName,
            lat = lat,
            lon = lon,
            radiusM = clampedRadius,
            events = events,
            lastInside = initialInside,
            lastInsideAt = if (initialInside != null) System.currentTimeMillis() else null,
        )
        // The read-modify-write shares the engine's monitor so a sample landing
        // mid-arm cannot persist a list that is missing this fence.
        val fenceCount = synchronized(FenceStore) {
            val fences = FenceStore.load(context)
            FenceStore.addError(fences, fenceName)?.let { return it }
            val next = fences.filterNot { it.name == fenceName } + spec
            FenceStore.save(context, next)
            next.size
        }
        EventFlags.set(context, GEOFENCE_CHANNEL, true)
        ContextCompat.startForegroundService(context, android.content.Intent(context, EventMonitorService::class.java))

        return buildJsonObject {
            put("ok", true)
            put("name", fenceName)
            put("lat", lat)
            put("lon", lon)
            put("radiusM", clampedRadius)
            if (clampedRadius != radius) put("radiusClampedFrom", radius)
            put("events", events)
            put("initialInside", initialInside?.let { JsonPrimitive(it) } ?: JsonNull)
            put("lastKnownAt", baseline?.time?.let { JsonPrimitive(it) } ?: JsonNull)
            put("fenceCount", fenceCount)
            put("mode", "foreground")
            put("kvNamespace", GEOFENCE_EVENTS_NAMESPACE)
            put("note", "前台监听模式：app 退出或前台服务被系统杀死后围栏可能失效（系统可能自动重启服务恢复，不保证）；重开 app 或重新 geofence_on 可确保恢复。不做开机持久化，重启手机后需重新布防。事件写入 ${GEOFENCE_EVENTS_NAMESPACE}/latest 与 history，可用 geofence_read 或 live 轮询。")
        }.toString()
    }

    private fun disarm(args: JsonObject): String {
        val target = args.strArg("name")?.trim()
        var removedNames = emptyList<String>()
        var remaining: List<FenceSpec> = emptyList()
        // Same monitor as the engine: a sample can neither fire for a fence that
        // is being removed nor write the removed fence back.
        synchronized(FenceStore) {
            val fences = FenceStore.load(context)
            if (target != null && fences.none { it.name == target }) {
                return "ERROR: 未找到名为 $target 的围栏"
            }
            val removed = if (target == null) fences else fences.filter { it.name == target }
            remaining = if (target == null) emptyList() else fences.filterNot { it.name == target }
            FenceStore.save(context, remaining)
            removedNames = removed.map { it.name }
        }
        if (remaining.isEmpty()) {
            EventFlags.set(context, GEOFENCE_CHANNEL, false)
        }
        // R117②：sys 触发器也占用同一前台服务，撤防后不能误停掉仍在监听的通道。
        val othersEnabled = EVENT_CHANNELS.filter { it != GEOFENCE_CHANNEL }.any { EventFlags.enabled(context, it) }
        val geofenceEnabled = remaining.isNotEmpty()
        if (othersEnabled || geofenceEnabled) {
            if (EventMonitorService.instance != null || geofenceEnabled) {
                ContextCompat.startForegroundService(context, android.content.Intent(context, EventMonitorService::class.java))
            }
        } else {
            context.stopService(android.content.Intent(context, EventMonitorService::class.java))
        }
        return buildJsonObject {
            put("ok", true)
            put("removed", buildJsonArray { removedNames.forEach { add(JsonPrimitive(it)) } })
            put("remaining", remaining.size)
            put("foregroundStopped", remaining.isEmpty() && !othersEnabled)
        }.toString()
    }

    private fun list(): String {
        val fences = FenceStore.load(context)
        val enabled = EventFlags.enabled(context, GEOFENCE_CHANNEL)
        val service = EventMonitorService.instance
        val listening = service?.geofenceListening() ?: false
        return buildJsonObject {
            put("enabled", enabled)
            put("serviceAlive", service != null)
            put("listening", listening)
            put("count", fences.size)
            service?.geofenceError()?.let { put("lastError", it) }
            put("fences", buildJsonArray {
                fences.forEach { fence ->
                    add(buildJsonObject {
                        put("name", fence.name)
                        put("lat", fence.lat)
                        put("lon", fence.lon)
                        put("radiusM", fence.radiusM)
                        put("events", fence.events)
                        put("lastInside", fence.lastInside?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("lastInsideAt", fence.lastInsideAt?.let { JsonPrimitive(it) } ?: JsonNull)
                    })
                }
            })
            if (enabled && !listening) {
                put("note", "围栏已布防但当前未在监听：前台服务可能已被系统杀死，重新打开 app 或再次 geofence_on 可恢复")
            }
        }.toString()
    }

    private fun read(args: JsonObject): String {
        val minutes = (args.intArg("duration_min") ?: 10).coerceIn(1, 1440)
        val raw = EventStore.read(context, GEOFENCE_EVENTS_NAMESPACE, minutes)
        val filter = args.strArg("name") ?: return raw
        val root = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            ?: return raw
        val events = root["events"] as? JsonArray ?: return raw
        return buildJsonObject {
            put("name", filter)
            put("events", buildJsonArray {
                events.filter { item ->
                    (item as? JsonObject)?.get("name")?.jsonPrimitive?.content == filter
                }.forEach { add(it) }
            })
        }.toString()
    }

    @Suppress("DEPRECATION")
    private fun lastKnownLocation(lm: LocationManager): Location? =
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { provider -> runCatching { lm.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull { it.time }

    private fun doubleArg(args: JsonObject, key: String): Double? =
        (args[key] as? JsonPrimitive)?.content?.toDoubleOrNull()
}
