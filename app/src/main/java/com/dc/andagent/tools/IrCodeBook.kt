package com.dc.andagent.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Locale

/**
 * R113 内置红外码表。
 *
 * 数据分两层：
 *  1. 随 APK 打包的 `assets/ir/ir-codes.json`（只读，按品牌 → 设备 → 按键组织参数）；
 *  2. 用户码表（KV 命名空间 `ir_codes`，由 `ir_code_save` 写入），同名按键覆盖内置值。
 *
 * 条目参数沿用 irdb 口径（protocol/device/subdevice/function），由 [IrProtocols] 编译成波形；
 * 也支持直接存 Pronto Hex 或裸 on/off 数组，覆盖 NEC/Sony/RC5 之外的私有协议与空调长帧。
 */
internal data class IrCommandCode(
    val name: String,
    val protocol: String? = null,
    val deviceNo: Int? = null,
    val subdeviceNo: Int? = null,
    val function: Int? = null,
    val pronto: String? = null,
    val carrierHz: Int? = null,
    val pattern: IntArray? = null,
    val source: String = "builtin",
    val note: String? = null,
) {
    /** 编译成可发射信号；frames=0 时用协议默认连发帧数。 */
    fun signal(entry: IrCodeEntry, frames: Int = 0): IrSignal {
        pattern?.takeIf { it.isNotEmpty() }?.let { raw ->
            validatePattern(raw)
            return IrSignal(carrierHz ?: entry.carrierHz, raw.copyOf())
        }
        pronto?.takeIf { it.isNotBlank() }?.let { return ProntoHex.parse(it) }
        val proto = protocol ?: entry.protocol ?: error("按键「$name」缺少协议参数，需要 pronto 或 protocol+function")
        val fn = function ?: error("按键「$name」缺少功能值")
        return IrProtocols.encode(proto, deviceNo ?: entry.deviceNo, subdeviceNo ?: entry.subdeviceNo, fn, frames)
    }
}

internal data class IrCodeEntry(
    val brandId: String,
    val brand: String,
    val device: String,
    val brandAliases: List<String> = emptyList(),
    val deviceAliases: List<String> = emptyList(),
    val model: String? = null,
    val protocol: String? = null,
    val deviceNo: Int = -1,
    val subdeviceNo: Int = -1,
    val carrierHz: Int = 38_000,
    val commands: Map<String, IrCommandCode> = emptyMap(),
) {
    val codeId: String get() = "$brandId/$device"

    fun withCommands(extra: Map<String, IrCommandCode>): IrCodeEntry = copy(commands = commands + extra)

    fun command(name: String): IrCommandCode? {
        IrCommands.canonical(name)?.let { canonical -> commands[canonical]?.let { return it } }
        val key = name.trim().lowercase(Locale.US)
        return commands[key]
    }
}

internal interface IrCodeStore {
    /** 已保存的用户条目（每条是一个 JSON 字符串）。 */
    fun records(): List<String>
    fun put(key: String, json: String)
    fun delete(key: String): Boolean
}

/** 用户码表落 KV（与 `kv_*` 工具共享 andagent_kv 底座）。 */
internal class KvIrCodeStore(private val kv: KvStore) : IrCodeStore {
    override fun records(): List<String> =
        kv.keys(NAMESPACE).mapNotNull { kv.get(NAMESPACE, it.key)?.toString() }

    override fun put(key: String, json: String) {
        kv.set(NAMESPACE, key, IR_JSON.parseToJsonElement(json))
    }

    override fun delete(key: String): Boolean = kv.delete(NAMESPACE, key)

    companion object {
        const val NAMESPACE = "ir_codes"
    }
}

internal val IR_JSON = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/** 一条用户码表记录（KV 值）。 */
internal data class IrUserCode(
    val brand: String,
    val device: String,
    val command: String,
    val model: String? = null,
    val protocol: String? = null,
    val deviceNo: Int? = null,
    val subdeviceNo: Int? = null,
    val function: Int? = null,
    val pronto: String? = null,
    val carrierHz: Int? = null,
    val pattern: IntArray? = null,
    val savedAt: Long = 0L,
) {
    fun toJson(): String = buildJsonObject {
        put("brand", brand)
        put("device", device)
        put("command", command)
        model?.let { put("model", it) }
        protocol?.let { put("protocol", it) }
        deviceNo?.let { put("deviceNo", it) }
        subdeviceNo?.let { put("subdeviceNo", it) }
        function?.let { put("function", it) }
        pronto?.let { put("pronto", it) }
        carrierHz?.let { put("carrierHz", it) }
        pattern?.let { raw ->
            put("pattern", buildJsonArray { raw.forEach { add(it) } })
        }
        put("savedAt", savedAt)
    }.toString()

    fun toCode(): IrCommandCode = IrCommandCode(
        name = IrCommands.canonical(command) ?: command.trim().lowercase(Locale.US),
        protocol = protocol,
        deviceNo = deviceNo,
        subdeviceNo = subdeviceNo,
        function = function,
        pronto = pronto,
        carrierHz = carrierHz,
        pattern = pattern,
        source = "user",
    )

    companion object {
        fun parse(raw: String): IrUserCode? = runCatching {
            val obj = IR_JSON.parseToJsonElement(raw).jsonObject
            val brand = obj.str("brand") ?: return null
            val command = obj.str("command") ?: return null
            IrUserCode(
                brand = brand,
                device = obj.str("device") ?: "tv",
                command = command,
                model = obj.str("model"),
                protocol = obj.str("protocol"),
                deviceNo = obj.int("deviceNo"),
                subdeviceNo = obj.int("subdeviceNo"),
                function = obj.int("function"),
                pronto = obj.str("pronto"),
                carrierHz = obj.int("carrierHz"),
                pattern = obj.arr("pattern")?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }?.toIntArray(),
                savedAt = obj["savedAt"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
            )
        }.getOrNull()
    }
}

internal class IrCodeBook(
    private val entries: List<IrCodeEntry>,
    private val store: IrCodeStore?,
) {
    fun allEntries(): List<IrCodeEntry> = entries

    /** 品牌 + 设备（可选）精确命中，用于发送/追加按键。 */
    fun entry(brand: String, device: String? = null): IrCodeEntry? {
        val wantedBrand = brand.trim().lowercase(Locale.US)
        val wantedDevice = device?.trim()?.lowercase(Locale.US)
        return entries.firstOrNull { entry ->
            matchesBrand(entry, wantedBrand) &&
                (wantedDevice.isNullOrEmpty() || matchesDevice(entry, wantedDevice))
        }
    }

    /** 按品牌/设备/关键词检索，命中越多分数越高。 */
    fun search(brand: String?, device: String?, query: String?, limit: Int): List<IrCodeEntry> {
        val wantedBrand = brand?.trim()?.lowercase(Locale.US).orEmpty()
        val wantedDevice = device?.trim()?.lowercase(Locale.US).orEmpty()
        val needle = query?.trim()?.lowercase(Locale.US).orEmpty()
        val scored = entries.mapNotNull { entry ->
            var score = 0
            if (wantedBrand.isNotEmpty()) {
                val exact = entry.brandId.lowercase(Locale.US) == wantedBrand ||
                    entry.brand.lowercase(Locale.US) == wantedBrand
                val loose = matchesBrand(entry, wantedBrand)
                if (!loose) return@mapNotNull null
                score += if (exact) 100 else 40
            }
            if (wantedDevice.isNotEmpty()) {
                if (!matchesDevice(entry, wantedDevice)) return@mapNotNull null
                score += 20
            }
            if (needle.isNotEmpty()) {
                val commandHit = entry.commands.keys.any { name ->
                    keywordHit(name, needle) || IrCommands.aliases(name).any { keywordHit(it, needle) }
                } || entry.commands.values.any { keywordHit(it.note.orEmpty(), needle) }
                val entryHit = keywordHit(entry.brand, needle) ||
                    entry.brandAliases.any { keywordHit(it, needle) } ||
                    keywordHit(entry.device, needle) ||
                    entry.deviceAliases.any { keywordHit(it, needle) } ||
                    keywordHit(entry.model.orEmpty(), needle)
                if (!commandHit && !entryHit) return@mapNotNull null
                if (entryHit) score += 15
                if (commandHit) score += 5
            }
            entry to score
        }.sortedWith(compareByDescending<Pair<IrCodeEntry, Int>> { it.second }.thenBy { it.first.codeId })
        return scored.take(limit.coerceIn(1, 20)).map { it.first }
    }

    /** 命中优先级：品牌精确 > 品牌别名 > 设备别名。 */
    private fun matchesBrand(entry: IrCodeEntry, needle: String): Boolean {
        if (needle.isEmpty()) return true
        if (entry.brandId.equals(needle, true) || entry.brand.equals(needle, true)) return true
        return entry.brandAliases.any { it.equals(needle, true) || keywordHit(it, needle) }
    }

    private fun matchesDevice(entry: IrCodeEntry, needle: String): Boolean {
        if (entry.device.equals(needle, true)) return true
        return entry.deviceAliases.any { it.equals(needle, true) || keywordHit(it, needle) }
    }

    private fun keywordHit(text: String, needle: String): Boolean {
        if (text.isEmpty()) return false
        val hay = text.lowercase(Locale.US)
        if (needle.isEmpty()) return true
        if (hay.contains(needle)) return true
        // 中文别名常以「音量+」这类带符号写法出现，比较时忽略空格与下划线。
        return IrCommands.normalized(hay).contains(IrCommands.normalized(needle))
    }

    /** 新增/覆盖一条用户按键，返回 KV 键名。 */
    fun save(code: IrUserCode): String {
        requireNotNull(store) { "码表存储不可用" }
        val key = irCodeKey(code.brand, code.device, code.command)
        store.put(key, code.toJson())
        return key
    }

    fun delete(key: String): Boolean = store?.delete(key) == true

    companion object {
        /** 加载内置表 + 用户表（用户条目覆盖同名品牌/设备/按键）。 */
        fun load(builtinJson: String, store: IrCodeStore? = null): IrCodeBook {
            val builtin = parseBuiltin(builtinJson)
            val users = store?.records().orEmpty().mapNotNull { IrUserCode.parse(it) }
            return IrCodeBook(merge(builtin, users), store)
        }

        fun parseBuiltin(json: String): List<IrCodeEntry> {
            val root = IR_JSON.parseToJsonElement(json).jsonObject
            val brands = root["brands"]?.jsonArray ?: JsonArray(emptyList())
            val out = ArrayList<IrCodeEntry>()
            for (brandElement in brands) {
                val brand = brandElement.jsonObject
                val brandId = brand.str("id") ?: continue
                val brandName = brand.str("name") ?: brandId
                val brandAliases = brand.strList("aliases")
                val devices = brand["devices"]?.jsonArray ?: JsonArray(emptyList())
                for (deviceElement in devices) {
                    val device = deviceElement.jsonObject
                    val deviceId = device.str("id") ?: continue
                    val protocol = device.str("protocol")
                    val entry = IrCodeEntry(
                        brandId = brandId,
                        brand = brandName,
                        brandAliases = brandAliases,
                        device = deviceId,
                        deviceAliases = device.strList("aliases"),
                        model = device.str("model"),
                        protocol = protocol,
                        deviceNo = device.int("device") ?: -1,
                        subdeviceNo = device.int("subdevice") ?: -1,
                        carrierHz = device.int("carrier_hz") ?: protocol?.let { IrProtocols.carrierHz(it) } ?: 38_000,
                        commands = emptyMap(),
                    )
                    val commands = device["commands"]?.jsonObject ?: continue
                    val parsed = LinkedHashMap<String, IrCommandCode>()
                    for ((rawName, value) in commands) {
                        val name = IrCommands.canonical(rawName) ?: IrCommands.normalizeCommand(rawName)
                        parsed[name] = parseCommand(name, value)
                    }
                    out += entry.withCommands(parsed)
                }
            }
            return out
        }

        private fun parseCommand(name: String, value: JsonElement): IrCommandCode {
            val primitive = value as? JsonPrimitive
            if (primitive != null && !primitive.isString) {
                val fn = primitive.intOrNull ?: error("按键「$name」的功能值必须是整数")
                return IrCommandCode(name = name, function = fn)
            }
            if (primitive != null && primitive.isString) {
                return IrCommandCode(name = name, pronto = primitive.contentOrNull)
            }
            val obj = value.jsonObject
            return IrCommandCode(
                name = name,
                protocol = obj.str("protocol"),
                deviceNo = obj.int("device"),
                subdeviceNo = obj.int("subdevice"),
                function = obj.int("function"),
                pronto = obj.str("pronto"),
                carrierHz = obj.int("carrier_hz"),
                pattern = obj.arr("pattern")?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }?.toIntArray(),
                note = obj.str("note"),
            )
        }

        private fun merge(builtin: List<IrCodeEntry>, users: List<IrUserCode>): List<IrCodeEntry> {
            val list = builtin.toMutableList()
            for (user in users) {
                val code = user.toCode()
                val index = list.indexOfFirst { entry ->
                    (entry.brandId.equals(user.brand, true) || entry.brand.equals(user.brand, true)) &&
                        entry.device.equals(user.device, true)
                }
                if (index >= 0) {
                    list[index] = list[index].withCommands(mapOf(code.name to code))
                } else {
                    list += IrCodeEntry(
                        brandId = irSlug(user.brand).ifBlank { "user" },
                        brand = user.brand,
                        device = irSlug(user.device).ifBlank { "tv" },
                        model = user.model,
                        protocol = user.protocol,
                        deviceNo = user.deviceNo ?: -1,
                        subdeviceNo = user.subdeviceNo ?: -1,
                        carrierHz = user.carrierHz ?: 38_000,
                        commands = mapOf(code.name to code),
                    )
                }
            }
            return list
        }
    }
}

/** 码表按键的规范名与中英文别名。 */
internal object IrCommands {
    val CANONICAL: List<String> = listOf(
        "power", "power_on", "power_off", "vol_up", "vol_down", "mute",
        "ch_up", "ch_down", "up", "down", "left", "right", "ok", "back", "exit",
        "menu", "home", "input", "info", "guide", "play", "pause", "stop",
        "next", "prev", "rew", "ff", "sleep",
    ) + (0..9).map { it.toString() }

    /** 规范名 → 全部别名（含中文），检索与提示词都用同一份。 */
    private val DEFS: List<Pair<String, List<String>>> = listOf(
        "power" to listOf("pwr", "电源", "开关", "电源键", "遥控开关"),
        "power_on" to listOf("poweron", "开机", "打开"),
        "power_off" to listOf("poweroff", "关机", "关闭"),
        "vol_up" to listOf("volup", "volumeup", "vol+", "volume+", "音量+", "音量加", "音量增", "加音量", "大声"),
        "vol_down" to listOf("voldown", "volumedown", "vol-", "volume-", "音量-", "音量减", "减音量", "小声"),
        "mute" to listOf("静音", "静音键"),
        "ch_up" to listOf("chup", "channelup", "ch+", "channel+", "频道+", "频道加", "节目+", "换台+"),
        "ch_down" to listOf("chdown", "channeldown", "ch-", "channel-", "频道-", "频道减", "节目-", "换台-"),
        "up" to listOf("arrowup", "上", "向上", "上键"),
        "down" to listOf("arrowdown", "下", "向下", "下键"),
        "left" to listOf("arrowleft", "左", "向左", "左键"),
        "right" to listOf("arrowright", "右", "向右", "右键"),
        "ok" to listOf("enter", "select", "确定", "确认", "确定键", "ok键", "中键"),
        "back" to listOf("return", "返回", "返回键"),
        "exit" to listOf("退出", "退出键"),
        "menu" to listOf("菜单", "菜单键", "设置"),
        "home" to listOf("主页", "首页", "主屏幕", "桌面"),
        "input" to listOf("source", "输入", "输入源", "信号源", "切换输入", "切换信号源"),
        "info" to listOf("信息", "显示信息"),
        "guide" to listOf("epg", "节目指南", "电视指南"),
        "play" to listOf("播放"),
        "pause" to listOf("暂停"),
        "stop" to listOf("停止"),
        "next" to listOf("下一曲", "下一个"),
        "prev" to listOf("previous", "上一曲", "上一个"),
        "rew" to listOf("rewind", "快退"),
        "ff" to listOf("fastforward", "快进"),
        "sleep" to listOf("睡眠", "定时关机", "睡眠定时"),
        "0" to listOf("零"),
        "1" to listOf("一"),
        "2" to listOf("二"),
        "3" to listOf("三"),
        "4" to listOf("四"),
        "5" to listOf("五"),
        "6" to listOf("六"),
        "7" to listOf("七"),
        "8" to listOf("八"),
        "9" to listOf("九"),
    )

    private val ALIASES: Map<String, String> = buildMap {
        for ((canonical, names) in DEFS) {
            put(normalized(canonical), canonical)
            for (name in names) put(normalized(name), canonical)
        }
    }

    private val CANONICAL_ALIASES: Map<String, List<String>> = DEFS.associate { (canonical, names) ->
        canonical to (listOf(canonical) + names)
    }

    /** 某按键的全部别名（检索用）。 */
    fun aliases(canonical: String): List<String> = CANONICAL_ALIASES[canonical].orEmpty()

    /** 去空格/下划线的比较形态（保留 +/- 等符号）。 */
    fun normalized(value: String): String =
        value.trim().lowercase(Locale.US).replace(" ", "").replace("_", "")

    /** 已知按键 → 规范名；未知按键返回 null（允许用户自定义按键名）。 */
    fun canonical(raw: String): String? {
        val key = normalized(raw)
        ALIASES[key]?.let { return it }
        return CANONICAL.firstOrNull { it == key }
    }

    /** 未知按键名统一成 ASCII 小写下划线形态，便于持久化与再次检索。 */
    fun normalizeCommand(raw: String): String {
        val cleaned = raw.trim().lowercase(Locale.US).replace(Regex("[\\s_-]+"), "_")
        return cleaned.ifBlank { "custom" }.take(32)
    }
}

/** 用户码表 KV 键：ASCII 安全且稳定（含中文品牌时附加短哈希防撞）。 */
internal fun irCodeKey(brand: String, device: String, command: String): String {
    val raw = "${brand.trim().lowercase(Locale.US)}|${device.trim().lowercase(Locale.US)}|${
        (IrCommands.canonical(command) ?: command).trim().lowercase(Locale.US)
    }"
    val base = raw.map { if (it.code in 32..126 && (it.isLetterOrDigit() || it == '_')) it else '_' }
        .joinToString("")
        .replace(Regex("_+"), "_")
        .trim('_')
        .ifBlank { "code" }
        .take(40)
    val hash = Integer.toHexString(raw.hashCode())
    return "${base}_$hash".take(64)
}

/** 品牌/设备名的 ASCII 形态（用户自定义条目的 codeId 用）。 */
internal fun irSlug(value: String): String = value.trim().lowercase(Locale.US)
    .map { if (it.code in 32..126 && (it.isLetterOrDigit() || it == '_')) it else '_' }
    .joinToString("")
    .replace(Regex("_+"), "_")
    .trim('_')

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

private fun JsonObject.arr(key: String): JsonArray? = (this[key] as? JsonArray)

private fun JsonObject.strList(key: String): List<String> =
    arr(key)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { s -> s.isNotBlank() } }.orEmpty()
