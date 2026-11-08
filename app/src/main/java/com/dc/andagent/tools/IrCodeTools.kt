package com.dc.andagent.tools

import android.content.Context
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** R113：内置红外码表工具（离线查询 + 按品牌按键发射 + 用户码增删）。 */
val IR_CODE_TOOLS = listOf("ir_code_search", "ir_code_send", "ir_code_save", "ir_code_delete")

/**
 * 码表检索/发射/维护。
 *
 * 内置表随 APK 打包（assets/ir/ir-codes.json，NEC/NECx/Sony/RC5 参数口径），
 * 用户码走 KV 命名空间 `ir_codes`，同名品牌+设备+按键覆盖内置值。
 */
class IrCodeTool(
    private val context: Context,
    private val kv: KvStore,
    override val name: String,
) : Tool {
    private val store = KvIrCodeStore(kv)

    override val description = when (name) {
        "ir_code_search" ->
            "在内置红外码表（随 APK 打包，离线）中按品牌/设备/按键检索遥控码。" +
                "指定 command 时直接返回该按键的 Pronto Hex 与波形参数，可交给 ir_send_pronto 或 ir_code_send。"
        "ir_code_send" ->
            "按品牌+设备+按键从内置红外码表取码并真实发射（NEC/NECx/Sony/RC5）。" +
                "先 ir_available 确认有发射器；码表没收录的设备用 ir_code_save 存入 Pronto Hex 后用本工具发射。"
        "ir_code_save" ->
            "把遥控码保存为用户码表条目：可用 pronto（Pronto Hex）、carrier_hz+pattern（裸 on/off 微秒数组）" +
                "或 protocol+code（协议参数，protocol 支持 NEC1/NECx2/Sony12/RC5 等）。同名品牌+设备+按键会覆盖内置码。"
        else ->
            "删除用户保存的红外码（按 key，或 brand+device+command）。不删除内置码表条目。"
    }

    override val parameters = when (name) {
        "ir_code_search" -> schemaOf(
            "brand" to strParam("品牌，如 LG/三星/Sony/飞利浦；可省略"),
            "device" to strParam("设备类型，如 tv/ac（默认不限）"),
            "query" to strParam("自由关键词：品牌、设备或按键名（支持中英文，如 音量/静音/电源）"),
            "command" to strParam("按键规范名，如 power/vol_up/mute/ch_up/ok/input；给出时返回可发射的码"),
            "limit" to intParam("返回条数，默认 8，上限 20"),
        )
        "ir_code_send" -> schemaOf(
            "brand" to strParam("品牌，如 LG/三星/Sony/飞利浦"),
            "device" to strParam("设备类型，默认 tv"),
            "command" to strParam("按键，如 power/vol_up/mute/ok/频道+/音量-"),
            "frames" to intParam("连发帧数 1-5；省略用协议默认（Sony 3，其余 2）"),
            required = listOf("brand", "command"),
        )
        "ir_code_save" -> schemaOf(
            "brand" to strParam("品牌名（用户自定义也可，如 客厅空调）"),
            "device" to strParam("设备类型，默认 tv"),
            "command" to strParam("按键名，如 power/vol_up，或自定义英文名"),
            "model" to strParam("可选型号备注"),
            "pronto" to strParam("Pronto Hex 0000 码（4 位十六进制字空格分隔）"),
            "carrier_hz" to intParam("载波频率 Hz，与 pattern 一起使用，默认 38000"),
            "pattern" to buildJsonObject {
                put("type", "array")
                put("description", "交替 on/off 的正整数微秒数组（与 carrier_hz 搭配，最多 2048 项）")
                put("items", buildJsonObject { put("type", "integer") })
            },
            "protocol" to strParam("协议名：nec1/nec2/necx1/necx2/sony12/sony15/sony20/rc5"),
            "code" to intParam("协议功能值（与 protocol 搭配，0-255）"),
            "device_no" to intParam("协议设备号（省略则沿用同品牌既有条目）"),
            "subdevice_no" to intParam("协议子设备号 / NECx 高字节"),
            required = listOf("brand", "command"),
        )
        else -> schemaOf(
            "key" to strParam("码表键名（ir_code_save 返回的 key，或搜索结果里的 key）"),
            "brand" to strParam("或给品牌"),
            "device" to strParam("设备类型，默认 tv"),
            "command" to strParam("按键名"),
        )
    }

    override val destructive: Boolean
        get() = name == "ir_code_send" || name == "ir_code_delete"

    override suspend fun execute(args: JsonObject): String = try {
        when (name) {
            "ir_code_search" -> search(args)
            "ir_code_send" -> send(args)
            "ir_code_save" -> save(args)
            "ir_code_delete" -> delete(args)
            else -> "ERROR: 未知红外码表工具"
        }
    } catch (e: Exception) {
        "ERROR: ${e.message ?: e.javaClass.simpleName}"
    }

    /* ------------------------------ search ------------------------------ */

    private fun search(args: JsonObject): String {
        val brand = args.strArg("brand")
        val device = args.strArg("device")
        val query = args.strArg("query")
        val commandArg = args.strArg("command")
        val limit = (args.intArg("limit") ?: 8).coerceIn(1, 20)
        val entryLimit = if (brand == null && device == null && query == null) 20 else limit
        val book = book()

        val candidates = if (brand == null && device == null && query == null) {
            book.allEntries()
        } else {
            book.search(brand, device, query, entryLimit)
        }
        val matches = if (commandArg == null) {
            candidates.take(limit)
        } else {
            candidates.filter { it.command(commandArg) != null }.take(limit)
        }

        val result = buildJsonObject {
            put("count", matches.size)
            put("available", IrEmitter.available(context))
            put("totalEntries", book.allEntries().size)
            if (matches.isEmpty()) {
                put("hint", hint(book, commandArg))
            }
            putJsonArray("matches") {
                for (entry in matches) {
                    val code = commandArg?.let { entry.command(it) }
                    add(entryJson(entry, code, 0))
                }
            }
        }
        return result.toString()
    }

    private fun entryJson(entry: IrCodeEntry, code: IrCommandCode?, frames: Int): JsonObject = buildJsonObject {
        put("codeId", entry.codeId)
        put("brand", entry.brand)
        put("device", entry.device)
        entry.model?.let { put("model", it) }
        entry.protocol?.let { put("protocol", it) }
        put("carrierHz", entry.carrierHz)
        put("commands", JsonArray(entry.commands.keys.sortedBy { availabilityOrder(it) }.map { JsonPrimitive(it) }))
        val userCommands = entry.commands.count { it.value.source == "user" }
        if (userCommands > 0) put("userCommands", userCommands)
        if (code != null) {
            put("command", buildJsonObject {
                put("name", code.name)
                put("source", code.source)
                code.note?.let { put("note", it) }
                val compiled = runCatching { code.signal(entry, frames) }
                compiled.getOrNull()?.let { signal ->
                    put("carrierHz", signal.carrierHz)
                    put("pulses", signal.pattern.size)
                    put("pronto", signal.toProntoHex())
                    put("key", irCodeKey(entry.brand, entry.device, code.name))
                } ?: put("error", compiled.exceptionOrNull()?.message ?: "该按键无法编译成波形")
            })
        }
    }

    private fun hint(book: IrCodeBook, command: String?): String {
        val brands = book.allEntries().map { "${it.brand}(${it.device})" }.distinct()
        val available = if (command != null) {
            val known = book.allEntries().flatMap { it.commands.keys }.distinct().sortedBy { availabilityOrder(it) }
            "码表里没有「$command」，已收录按键：${known.joinToString("/")}"
        } else {
            "码表里没有匹配的品牌/设备，已收录：${brands.joinToString("、")}"
        }
        return "$available。内置码表未收录的设备可联网查到 Pronto Hex 后用 ir_code_save 保存，或先用 ir_available 确认硬件。"
    }

    private fun availabilityOrder(command: String): Int =
        IrCommands.CANONICAL.indexOf(command).let { if (it < 0) IrCommands.CANONICAL.size else it }

    /* ------------------------------- send ------------------------------- */

    private fun send(args: JsonObject): String {
        val brand = args.strArg("brand") ?: return "ERROR: 缺少 brand"
        val device = args.strArg("device") ?: "tv"
        val command = args.strArg("command") ?: return "ERROR: 缺少 command"
        val frames = args.intArg("frames") ?: 0
        val book = book()
        val entry = book.entry(brand, device)
            ?: return "ERROR: 码表里没有「$brand $device」，已收录：${
                book.allEntries().map { "${it.brand}(${it.device})" }.distinct().joinToString("、")
            }"
        val code = entry.command(command)
            ?: return "ERROR: 「${entry.brand} ${entry.device}」没有按键「$command」，可用：${
                entry.commands.keys.sortedBy { availabilityOrder(it) }.joinToString("/")
            }"
        val signal = code.signal(entry, frames)
        IrEmitter.send(context, signal)?.let { return it }
        return buildJsonObject {
            put("sent", true)
            put("brand", entry.brand)
            put("device", entry.device)
            put("command", code.name)
            put("source", code.source)
            put("carrierHz", signal.carrierHz)
            put("pulses", signal.pattern.size)
            put("frames", if (frames > 0) frames else if (code.pattern != null || code.pronto != null) 1 else {
                IrProtocols.defaultFrames(code.protocol ?: entry.protocol ?: "nec1")
            })
        }.toString()
    }

    /* ------------------------------- save ------------------------------- */

    private fun save(args: JsonObject): String {
        val brand = args.strArg("brand") ?: return "ERROR: 缺少 brand"
        val device = args.strArg("device") ?: "tv"
        val command = args.strArg("command") ?: return "ERROR: 缺少 command"
        val model = args.strArg("model")
        val pronto = args.strArg("pronto")
        val protocol = args.strArg("protocol")
        val function = args.intArg("code")
        val carrierHz = args.intArg("carrier_hz")
        val pattern = (args["pattern"] as? JsonArray)?.map { element ->
            (element as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
                ?: return "ERROR: pattern 必须只含整数"
        }?.toIntArray()
        if (pattern != null) {
            validatePattern(pattern)
            carrierHz?.let { require(it in 20_000..60_000) { "carrier_hz 必须在 20–60 kHz" } }
        }
        if (pronto != null) ProntoHex.parse(pronto) // 提前校验，坏码不入库
        val book = book()
        val existing = book.entry(brand, device)
        var deviceNo = args.intArg("device_no")
        var subdeviceNo = args.intArg("subdevice_no")
        if (protocol != null) {
            require(IrProtocols.isSupported(protocol)) {
                "不支持的协议 $protocol（支持 ${IrProtocols.SUPPORTED.joinToString("/")}）"
            }
            require(function != null) { "给 protocol 时必须同时给 code（功能值）" }
            deviceNo = deviceNo ?: existing?.deviceNo ?: -1
            subdeviceNo = subdeviceNo ?: existing?.subdeviceNo ?: -1
            IrProtocols.encode(protocol, deviceNo, subdeviceNo, function) // 提前校验参数可编译
        }
        require(pattern != null || pronto != null || protocol != null) {
            "pronto、carrier_hz+pattern、protocol+code 三选一"
        }
        val record = IrUserCode(
            brand = brand,
            device = device,
            command = command,
            model = model,
            protocol = protocol,
            deviceNo = deviceNo,
            subdeviceNo = subdeviceNo,
            function = function,
            pronto = pronto,
            carrierHz = carrierHz,
            pattern = pattern,
            savedAt = System.currentTimeMillis(),
        )
        val key = book.save(record)
        return buildJsonObject {
            put("saved", true)
            put("key", key)
            put("brand", brand)
            put("device", device)
            put("command", IrCommands.canonical(command) ?: IrCommands.normalizeCommand(command))
            put("note", "已写入 ir_codes KV；ir_code_send 与 ir_code_search 立即生效")
        }.toString()
    }

    /* ------------------------------ delete ------------------------------ */

    private fun delete(args: JsonObject): String {
        val book = book()
        val key = args.strArg("key") ?: run {
            val brand = args.strArg("brand") ?: return "ERROR: 需要 key，或 brand+device+command"
            val device = args.strArg("device") ?: "tv"
            val command = args.strArg("command") ?: return "ERROR: 需要 key，或 brand+device+command"
            irCodeKey(brand, device, command)
        }
        if (!book.delete(key)) return "ERROR: 没有找到用户码 $key（内置码表条目不能删除）"
        return buildJsonObject {
            put("deleted", true)
            put("key", key)
        }.toString()
    }

    /* ------------------------------ plumbing ---------------------------- */

    private fun book(): IrCodeBook = IrCodeBook.load(builtinJson(), store)

    private fun builtinJson(): String = context.assets.open(ASSET).bufferedReader().use { it.readText() }

    private companion object {
        const val ASSET = "ir/ir-codes.json"
    }
}
