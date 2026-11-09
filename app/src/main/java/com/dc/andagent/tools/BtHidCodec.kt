package com.dc.andagent.tools

/**
 * R122 — 蓝牙 HID 设备模式的纯逻辑层（纯 JVM object，不 import 任何 Android 类，可 JVM 单测）。
 *
 * 协议事实来源（禁止凭印象，已逐条核对）：
 *  - 键盘报告描述符：USB HID 1.11 Appendix B「Boot Interface Descriptors」的标准 8 字节
 *    boot keyboard 报告（modifier byte + reserved byte + 6 keycodes；Usage Page Generic
 *    Desktop/Keyboard 0x01/0x07；Input(1) modifier + LED Output(1)）。逐字节参照 FTDI
 *    AN_345《FT51A Keyboard Sample》中标注「See Device Class Definition for HID 1.11」的
 *    boot descriptor，并补 0x85 Report ID 用于 combo 区分。
 *  - 鼠标报告描述符：3 按钮 + Generic Desktop X/Y 8bit 相对位移 + Generic Desktop Wheel
 *    (0x38) 滚轮，结构参照 Nordic SDK / TinyUSB 的成熟鼠标描述符，取最简结构。
 *    X/Y 合计 16bit（每轴 8bit），与 4 字节鼠标报告 buttons+dx+dy+wheel 严格自洽。
 *  - HID Keyboard/Keypad Usage ID：a-z=0x04..0x1D、1-9/0=0x1E..0x27、Enter=0x28、
 *    Esc=0x29、Backspace=0x2A、Tab=0x2B、Space=0x2C、方向键=0x4F..0x52、
 *    F1-F12=0x3A..0x45（USB HID Usage Tables Keyboard/Keypad Page 0x07）。
 *  - modifier 位序：LCtrl/LShift/LAlt/LGUI/RCtrl/RShift/RAlt/RGUI = 0x01..0x80。
 *  - `BluetoothHidDevice.sendReport(device, id, data)` 的 id 为描述符中的 Report ID，
 *    data **不含** Report ID 字节（Android 官方 javadoc），故报告数组从 modifier 起算。
 */
object BtHidCodec {

    /** Report ID：键盘 0x01 / 鼠标 0x02，组合描述符里并列两个 Application Collection。 */
    const val REPORT_ID_KEYBOARD = 1
    const val REPORT_ID_MOUSE = 2

    const val KEYBOARD_REPORT_BYTES = 8
    const val MOUSE_REPORT_BYTES = 4

    /* -------------------------- modifier 位（bit 顺序固定） -------------------------- */
    const val MOD_LCTRL = 0x01
    const val MOD_LSHIFT = 0x02
    const val MOD_LALT = 0x04
    const val MOD_LGUI = 0x08
    const val MOD_RCTRL = 0x10
    const val MOD_RSHIFT = 0x20
    const val MOD_RALT = 0x40
    const val MOD_RGUI = 0x80

    /** 常用 Keyboard/Keypad Usage ID 的上下界（报告字段是 1 字节，实际可用 0..255）。 */
    const val USAGE_ID_MIN = 0x04
    const val USAGE_ID_MAX = 0xDF

    /** named key -> HID Usage ID 常量表（纯数据，供 key/key_down/key_up 与描述展示）。 */
    val KEY_USAGES: Map<String, Int> = run {
        val m = LinkedHashMap<String, Int>()
        for (i in 0..25) m[('a' + i).toString()] = 0x04 + i
        for (i in 1..9) m[i.toString()] = 0x1D + i       // 1..9 -> 0x1E..0x26
        m["0"] = 0x27
        m["enter"] = 0x28
        m["esc"] = 0x29
        m["backspace"] = 0x2A
        m["tab"] = 0x2B
        m["space"] = 0x2C
        // 方向键：Right/Left/Down/Up
        m["right"] = 0x4F
        m["left"] = 0x50
        m["down"] = 0x51
        m["up"] = 0x52
        for (i in 1..12) m["f$i"] = 0x39 + i             // F1..F12 -> 0x3A..0x45
        m
    }

    private val MODIFIER_BITS: Map<String, Int> = mapOf(
        "ctrl" to MOD_LCTRL, "lctrl" to MOD_LCTRL, "control" to MOD_LCTRL,
        "shift" to MOD_LSHIFT, "lshift" to MOD_LSHIFT,
        "alt" to MOD_LALT, "lalt" to MOD_LALT,
        "gui" to MOD_LGUI, "lgui" to MOD_LGUI, "meta" to MOD_LGUI, "win" to MOD_LGUI,
        "rctrl" to MOD_RCTRL,
        "rshift" to MOD_RSHIFT,
        "ralt" to MOD_RALT,
        "rgui" to MOD_RGUI, "rmeta" to MOD_RGUI,
    )

    /** 未加 Shift 的 ASCII -> Usage ID（US 键盘布局）；控制字符映射到对应 Usage。 */
    private val BASE_USAGES: Map<Char, Int> = run {
        val m = HashMap<Char, Int>()
        for (i in 0..25) m['a' + i] = 0x04 + i
        for (i in 1..9) m['0' + i] = 0x1D + i          // '1'..'9' -> 0x1E..0x26
        m['0'] = 0x27
        m['\n'] = 0x28
        m['\r'] = 0x28
        m['\u001B'] = 0x29   // Esc
        m['\b'] = 0x2A      // Backspace
        m['\t'] = 0x2B
        m[' '] = 0x2C
        m['-'] = 0x2D
        m['='] = 0x2E
        m['['] = 0x2F
        m[']'] = 0x30
        m['\\'] = 0x31
        m[';'] = 0x33
        m['\''] = 0x34
        m['`'] = 0x35
        m[','] = 0x36
        m['.'] = 0x37
        m['/'] = 0x38
        m
    }

    /** 需要左 Shift 的 ASCII -> Usage ID（大写字母与 Shift 符号）。 */
    private val SHIFTED_USAGES: Map<Char, Int> = run {
        val m = HashMap<Char, Int>()
        for (i in 0..25) m['A' + i] = 0x04 + i
        m['!'] = 0x1E
        m['@'] = 0x1F
        m['#'] = 0x20
        m['$'] = 0x21
        m['%'] = 0x22
        m['^'] = 0x23
        m['&'] = 0x24
        m['*'] = 0x25
        m['('] = 0x26
        m[')'] = 0x27
        m['_'] = 0x2D
        m['+'] = 0x2E
        m['{'] = 0x2F
        m['}'] = 0x30
        m['|'] = 0x31
        m[':'] = 0x33
        m['"'] = 0x34
        m['~'] = 0x35
        m['<'] = 0x36
        m['>'] = 0x37
        m['?'] = 0x38
        m
    }

    /* -------------------------------- ASCII -> Usage ------------------------------- */

    /**
     * 单字符 ASCII -> (HID Usage ID, modifier 位)。大写与 Shift 符号自动带 LShift。
     * 非 ASCII（中文/emoji）或键盘布局无法表示的字符返回 null，由工具层明确拒绝。
     */
    fun asciiToUsage(c: Char): Pair<Int, Int>? {
        BASE_USAGES[c]?.let { return it to 0 }
        SHIFTED_USAGES[c]?.let { return it to MOD_LSHIFT }
        return null
    }

    /** 整段文本逐字符映射；任一字符不可映射即返回 null（含中文拒绝，不猜测）。 */
    fun textToUsages(text: String): List<Pair<Int, Int>>? {
        val out = ArrayList<Pair<Int, Int>>(text.length)
        for (c in text) out += asciiToUsage(c) ?: return null
        return out
    }

    /** 按名称查 Usage ID（KEY_USAGES 常量表）；未知返回 null。 */
    fun keyUsageByName(name: String?): Int? = name?.trim()?.lowercase()?.let { KEY_USAGES[it] }

    /* -------------------------------- modifier -------------------------------- */

    /** 逗号/空格/竖线分隔的修饰键名 -> 位掩码；空串返回 0，含未知名返回 null。 */
    fun parseModifiers(raw: String?): Int? {
        if (raw.isNullOrBlank()) return 0
        var bits = 0
        for (token in raw.split(',', ' ', '|', '+')) {
            val key = token.trim().lowercase()
            if (key.isEmpty()) continue
            bits = bits or (MODIFIER_BITS[key] ?: return null)
        }
        return bits
    }

    /* -------------------------------- 报告字节 -------------------------------- */

    /** 报告里 keycode 是 1 字节（0..255）；0 表示释放，常用 Usage 落在 0x04..0xDF。 */
    fun validKeycode(code: Int): Boolean = code in 0x00..0xFF

    /** 8 字节键盘报告：[modifier, reserved=0, k1..k6]。越界抛 IllegalArgumentException。 */
    fun keyboardReport(modifier: Int = 0, keycode: Int = 0): ByteArray {
        require(modifier in 0x00..0xFF) { "modifier 越界（0..255）：$modifier" }
        require(validKeycode(keycode)) { "keycode 越界（0..255）：$keycode" }
        return byteArrayOf(
            modifier.toByte(), 0,
            keycode.toByte(), 0, 0, 0, 0, 0,
        )
    }

    /** 相对位移钳制到 int8 区间（与描述符 Logical Min/Max -127..127 一致）。 */
    fun clampRelative(v: Int): Int = v.coerceIn(-127, 127)

    /** 4 字节鼠标报告：[buttons(bit0..2), dx(int8), dy(int8), wheel(int8)]。 */
    fun mouseReport(buttons: Int = 0, dx: Int = 0, dy: Int = 0, wheel: Int = 0): ByteArray {
        require(buttons in 0..0x07) { "buttons 越界（0..7）：$buttons" }
        return byteArrayOf(
            buttons.toByte(),
            clampRelative(dx).toByte(),
            clampRelative(dy).toByte(),
            clampRelative(wheel).toByte(),
        )
    }

    /** 鼠标按键名 -> 位；未知返回 null。 */
    fun mouseButtonBit(name: String?): Int? = when (name?.trim()?.lowercase()) {
        null, "", "left", "l", "button1" -> 0x01
        "right", "r", "button2" -> 0x02
        "middle", "m", "button3" -> 0x04
        else -> null
    }

    /* ------------------------------ 报告描述符 ------------------------------ */

    private fun hid(hex: String): ByteArray {
        val cleaned = hex.replace(Regex("[\\s,]"), "")
        require(cleaned.length % 2 == 0) { "描述符 hex 长度必须为偶数" }
        return cleaned.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    /**
     * 标准 8 字节 boot keyboard 报告描述符（USB HID 1.11 Appendix B）+ Report ID 0x01。
     * 报告：modifier(1B) + reserved(1B) + 6 keycodes(6B)；LED Output(1B)。
     */
    val KEYBOARD_DESCRIPTOR: ByteArray = hid(
        "05 01 09 06 A1 01 " +              // Usage Page(Generic Desktop) / Usage(Keyboard) / Collection(Application)
            "85 01 " +                          // Report ID (1)
            "05 07 19 E0 29 E7 15 00 25 01 75 01 95 08 81 02 " + // 8 modifier bits -> Input(1)
            "95 01 75 08 81 01 " +              // reserved byte -> Input(Constant)
            "95 03 75 01 05 08 19 01 29 03 91 02 " + // 3 LED bits -> Output(1)
            "95 01 75 05 91 01 " +              // LED 5-bit padding -> Output(Constant)
            "95 06 75 08 15 00 26 FF 00 05 07 19 00 2A FF 00 81 00 " + // 6 keycodes -> Input(Array)
            "C0",                                // End Collection
    )

    /**
     * 3 按钮 + X/Y 8bit 相对位移 + Generic Desktop Wheel 的鼠标描述符
     * + Report ID 0x02。报告：buttons(1B) + dx(1B) + dy(1B) + wheel(1B)。
     *
     * Codex review 必修 1：第 4 字节原声明为 Consumer Page `AC Pan`（0x0A 38 02）——
     * AC Pan 是**水平**位移，垂直滚轮必须用 Generic Desktop `Wheel (0x38)`（Usage ID
     * 0x38，且 GD Page 无需切 Page：05 01 已在前文重复声明）。滚轮输入曾被对端解读为
     * 水平平移。
     */
    val MOUSE_DESCRIPTOR: ByteArray = hid(
        "05 01 09 02 A1 01 " +              // Usage Page(Generic Desktop) / Usage(Mouse) / Collection(Application)
            "85 02 " +                          // Report ID (2)
            "09 01 A1 00 " +                    // Usage(Pointer) / Collection(Physical)
            "05 09 19 01 29 03 15 00 25 01 95 03 75 01 81 02 " + // 3 buttons -> Input(1)
            "95 01 75 05 81 01 " +              // 5-bit padding -> Input(Constant)
            "05 01 09 30 09 31 15 81 25 7F 75 08 95 02 81 06 " + // X/Y int8 relative -> Input
            "09 38 15 81 25 7F 75 08 95 01 81 06 " +            // Wheel(int8 relative, GD 0x38) -> Input
            "C0 C0",                            // End Collection(Physical) + End Collection(Application)
    )

    /** combo = 键盘 Collection + 鼠标 Collection 并列，靠 Report ID 1/2 区分。 */
    val COMBO_DESCRIPTOR: ByteArray = KEYBOARD_DESCRIPTOR + MOUSE_DESCRIPTOR

    /** keyboard|mouse|combo -> 描述符；未知模式返回 null。 */
    fun descriptorFor(mode: String?): ByteArray? = when (mode?.trim()?.lowercase()) {
        "keyboard" -> KEYBOARD_DESCRIPTOR
        "mouse" -> MOUSE_DESCRIPTOR
        "combo", null, "" -> COMBO_DESCRIPTOR
        else -> null
    }

    /** 指定模式的 Report ID（键盘 1 / 鼠标 2；combo 由调用方按 type 选择）。 */
    fun reportIdFor(mode: String?): Int = when (mode?.trim()?.lowercase()) {
        "mouse" -> REPORT_ID_MOUSE
        else -> REPORT_ID_KEYBOARD
    }

    /** 描述符头尾 magic 自检（供单测/诊断，不改变协议行为）。 */
    fun descriptorWellFormed(bytes: ByteArray?): Boolean =
        bytes != null && bytes.size > 3 &&
            bytes[0] == 0x05.toByte() && bytes[1] == 0x01.toByte() &&
            bytes[bytes.size - 1] == 0xC0.toByte()

    fun hex(bytes: ByteArray): String =
        bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
}
