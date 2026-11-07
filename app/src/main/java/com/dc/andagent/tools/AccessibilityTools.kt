package com.dc.andagent.tools

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.dc.andagent.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal const val A11Y_DEFAULT_MAX_NODES = 80
internal const val A11Y_HARD_MAX_NODES = 400
internal const val A11Y_MAX_TEXT_CHARS = 200
internal const val A11Y_MAX_DEPTH = 12
internal const val A11Y_MAX_INPUT_CHARS = 10_000

/**
 * Immutable, Android-free snapshot of one accessibility node. Keeping the
 * matching and serialisation logic on this type makes both testable on the
 * JVM (AccessibilityNodeInfo cannot be instantiated outside a device).
 */
internal data class A11yNodeSnapshot(
    val packageName: String? = null,
    val viewId: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val className: String? = null,
    val bounds: String = "",
    val clickable: Boolean = false,
    val longClickable: Boolean = false,
    val scrollable: Boolean = false,
    val editable: Boolean = false,
    val enabled: Boolean = true,
    val focused: Boolean = false,
    val selected: Boolean = false,
    val checked: Boolean = false,
    val password: Boolean = false,
    val children: List<A11yNodeSnapshot> = emptyList(),
)

/**
 * Selector for finding a node. All provided fields must match (AND).
 * `contains` only affects text/contentDescription, not viewId.
 */
internal data class A11ySelector(
    val viewId: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val contains: Boolean = true,
) {
    val isEmpty: Boolean get() = viewId == null && text == null && contentDescription == null
}

/** Budget shared by tree collection and serialisation: nodes, not bytes. */
internal class A11yBudget(val max: Int) {
    var used: Int = 0
        private set
    var truncated: Boolean = false
    var depthTruncated: Boolean = false

    fun take(): Boolean {
        if (used >= max) {
            truncated = true
            return false
        }
        used++
        return true
    }
}

/* ------------------------------ selectors ------------------------------ */

internal fun parseSelector(
    viewId: String? = null,
    text: String? = null,
    description: String? = null,
    match: String? = null,
): A11ySelector? {
    val id = viewId?.trim()?.takeIf { it.isNotEmpty() }
    val rawText = text?.trim()?.takeIf { it.isNotEmpty() }
    val desc = description?.trim()?.takeIf { it.isNotEmpty() }
    if (id == null && rawText == null && desc == null) return null
    return A11ySelector(
        viewId = id,
        text = rawText,
        contentDescription = desc,
        contains = !match.equals("exact", ignoreCase = true),
    )
}

internal fun matchesSelector(node: A11yNodeSnapshot, selector: A11ySelector): Boolean {
    if (selector.isEmpty) return false
    selector.viewId?.let { wanted ->
        if (!matchesViewId(node.viewId, wanted)) return false
    }
    selector.text?.let { wanted ->
        if (!matchesText(node.text, wanted, selector.contains)) return false
    }
    selector.contentDescription?.let { wanted ->
        if (!matchesText(node.contentDescription, wanted, selector.contains)) return false
    }
    return true
}

/**
 * Accepts a full resource name ("com.app:id/login"), a bare resource id
 * ("login"), or the suffix form (":id/login" / "id/login").
 */
internal fun matchesViewId(nodeId: String?, wanted: String): Boolean {
    if (nodeId.isNullOrBlank()) return false
    if (nodeId == wanted) return true
    val shortWanted = when {
        wanted.startsWith(":id/") -> wanted.removePrefix(":id/")
        wanted.startsWith("id/") -> wanted.removePrefix("id/")
        wanted.contains(":id/") -> return false // full name mismatch is final
        else -> wanted
    }
    if (!nodeId.contains(":id/")) return false
    return nodeId.substringAfterLast(":id/") == shortWanted
}

internal fun matchesText(haystack: String?, needle: String, contains: Boolean): Boolean {
    if (haystack.isNullOrEmpty()) return false
    val value = haystack.trim()
    return if (contains) value.contains(needle, ignoreCase = true)
    else value.equals(needle, ignoreCase = true)
}

internal fun findFirstMatch(root: A11yNodeSnapshot, selector: A11ySelector): A11yNodeSnapshot? {
    if (matchesSelector(root, selector)) return root
    for (child in root.children) {
        findFirstMatch(child, selector)?.let { return it }
    }
    return null
}

internal fun collectMatches(
    root: A11yNodeSnapshot,
    selector: A11ySelector,
    limit: Int,
): List<A11yNodeSnapshot> {
    val out = ArrayList<A11yNodeSnapshot>(limit.coerceAtMost(8))
    fun walk(node: A11yNodeSnapshot) {
        if (out.size >= limit) return
        if (matchesSelector(node, selector)) out += node
        for (child in node.children) {
            if (out.size >= limit) return
            walk(child)
        }
    }
    walk(root)
    return out
}

/* ---------------------------- serialisation ---------------------------- */

internal fun truncateText(value: String, maxChars: Int): String {
    val max = maxChars.coerceAtLeast(1)
    if (value.length <= max) return value
    if (max == 1) return "…"
    return value.take(max - 1) + "…"
}

/** Password nodes never leak their text; whitespace is collapsed for compact output. */
internal fun sanitizeNodeText(
    raw: String?,
    password: Boolean,
    maxChars: Int = A11Y_MAX_TEXT_CHARS,
): String? {
    if (raw.isNullOrEmpty()) return null
    if (password) return null
    val normalized = raw
        .replace('\n', ' ')
        .replace('\r', ' ')
        .replace('\t', ' ')
        .trim()
    if (normalized.isEmpty()) return null
    return truncateText(normalized, maxChars)
}

internal fun formatBounds(rect: Rect): String =
    "${rect.left},${rect.top},${rect.right},${rect.bottom}"

internal fun simpleClassName(name: String?): String? =
    name?.takeIf { it.isNotBlank() }?.substringAfterLast('.')

/**
 * Compact JSON for the visible window. Bounds are "l,t,r,b"; false/absent
 * flags are omitted except the four interaction flags, which the model uses
 * to pick a target. `truncated` is true when node, depth or text caps hit.
 */
internal fun serializeScreen(
    root: A11yNodeSnapshot,
    packageName: String? = root.packageName,
    windowClass: String? = root.className,
    maxNodes: Int = A11Y_DEFAULT_MAX_NODES,
    maxTextChars: Int = A11Y_MAX_TEXT_CHARS,
    maxDepth: Int = A11Y_MAX_DEPTH,
    sourceTruncated: Boolean = false,
): String {
    val budget = A11yBudget(maxNodes.coerceAtLeast(1))
    val rootJson = nodeToJson(root, 0, budget, maxTextChars, maxDepth)
    return buildJsonObject {
        put("package", packageName ?: "")
        put("windowClass", windowClass ?: "")
        put("nodeCount", budget.used)
        put("truncated", sourceTruncated || budget.truncated || budget.depthTruncated)
        put("maxNodes", maxNodes)
        put("maxTextChars", maxTextChars)
        put("root", rootJson ?: JsonNull)
    }.toString()
}

internal fun nodeToJson(
    node: A11yNodeSnapshot,
    depth: Int,
    budget: A11yBudget,
    maxTextChars: Int,
    maxDepth: Int,
): JsonObject? {
    if (!budget.take()) return null
    return buildJsonObject {
        node.viewId?.takeIf { it.isNotBlank() }?.let { put("viewId", it) }
        sanitizeNodeText(node.text, node.password, maxTextChars)?.let { put("text", it) }
        sanitizeNodeText(node.contentDescription, node.password, maxTextChars)
            ?.let { put("description", it) }
        simpleClassName(node.className)?.let { put("className", it) }
        if (node.bounds.isNotBlank()) put("bounds", node.bounds)
        put("clickable", node.clickable)
        put("longClickable", node.longClickable)
        put("scrollable", node.scrollable)
        put("editable", node.editable)
        if (!node.enabled) put("enabled", false)
        if (node.focused) put("focused", true)
        if (node.selected) put("selected", true)
        if (node.checked) put("checked", true)
        if (node.password) put("password", true)
        if (node.children.isNotEmpty()) {
            if (depth >= maxDepth) {
                budget.depthTruncated = true
                put("children", buildJsonArray { })
            } else {
                put("children", buildJsonArray {
                    for (child in node.children) {
                        nodeToJson(child, depth + 1, budget, maxTextChars, maxDepth)?.let { add(it) }
                    }
                })
            }
        }
    }
}

internal fun describeNode(node: A11yNodeSnapshot, maxChars: Int = 60): String = buildString {
    val visibleText = sanitizeNodeText(node.text, node.password, maxChars)
    val visibleDesc = sanitizeNodeText(node.contentDescription, node.password, maxChars)
    append("text=\"").append(visibleText ?: "-").append('"')
    visibleDesc?.let {
        append(" desc=\"").append(truncateText(it, maxChars)).append('"')
    }
    node.viewId?.let { append(" id=").append(it) }
    append(" bounds=").append(node.bounds.ifBlank { "-" })
}

/* ------------------------------- intents ------------------------------- */

internal fun normalizeGlobalAction(raw: String?): String? = when (raw?.trim()?.lowercase()) {
    "back", "home", "recents" -> raw.trim().lowercase()
    else -> null
}

/** true = forward, false = backward, null = unsupported. */
internal fun normalizeScrollDirection(raw: String?): Boolean? = when (raw?.trim()?.lowercase() ?: "forward") {
    "forward", "down", "next" -> true
    "backward", "back", "up", "prev", "previous" -> false
    else -> null
}

/**
 * Android stores enabled accessibility services as a colon-separated list of
 * flattened ComponentNames. Tolerant of spaces and case differences.
 */
internal fun isServiceListed(enabledServices: String?, component: String): Boolean {
    if (enabledServices.isNullOrBlank() || component.isBlank()) return false
    return enabledServices.split(':').any { it.trim().equals(component, ignoreCase = true) }
}

/* --------------------------- Android bridge ---------------------------- */

internal fun AccessibilityNodeInfo.toBareSnapshot(): A11yNodeSnapshot {
    val rect = Rect()
    getBoundsInScreen(rect)
    return A11yNodeSnapshot(
        packageName = packageName?.toString(),
        viewId = viewIdResourceName,
        text = text?.toString(),
        contentDescription = contentDescription?.toString(),
        className = className?.toString(),
        bounds = formatBounds(rect),
        clickable = isClickable,
        longClickable = isLongClickable,
        scrollable = isScrollable,
        editable = isEditable,
        enabled = isEnabled,
        focused = isFocused,
        selected = isSelected,
        checked = isChecked,
        password = isPassword,
    )
}

/**
 * Converts one node and its children with hard caps so a huge screen cannot
 * stall the UI thread. Any cap hit is reported through [budget].
 */
internal fun snapshotFromNode(
    node: AccessibilityNodeInfo,
    depth: Int,
    budget: A11yBudget,
    maxDepth: Int = A11Y_MAX_DEPTH,
): A11yNodeSnapshot? {
    if (!budget.take()) return null
    val rect = Rect()
    runCatching { node.getBoundsInScreen(rect) }
    val childCount = runCatching { node.childCount }.getOrDefault(0)
    val children = ArrayList<A11yNodeSnapshot>(childCount.coerceAtMost(16))
    if (childCount > 0) {
        if (depth >= maxDepth) {
            budget.depthTruncated = true
        } else {
            for (index in 0 until childCount) {
                val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
                snapshotFromNode(child, depth + 1, budget, maxDepth)?.let { children += it }
            }
        }
    }
    return A11yNodeSnapshot(
        packageName = runCatching { node.packageName?.toString() }.getOrNull(),
        viewId = runCatching { node.viewIdResourceName }.getOrNull(),
        text = runCatching { node.text?.toString() }.getOrNull(),
        contentDescription = runCatching { node.contentDescription?.toString() }.getOrNull(),
        className = runCatching { node.className?.toString() }.getOrNull(),
        bounds = formatBounds(rect),
        clickable = runCatching { node.isClickable }.getOrDefault(false),
        longClickable = runCatching { node.isLongClickable }.getOrDefault(false),
        scrollable = runCatching { node.isScrollable }.getOrDefault(false),
        editable = runCatching { node.isEditable }.getOrDefault(false),
        enabled = runCatching { node.isEnabled }.getOrDefault(true),
        focused = runCatching { node.isFocused }.getOrDefault(false),
        selected = runCatching { node.isSelected }.getOrDefault(false),
        checked = runCatching { node.isChecked }.getOrDefault(false),
        password = runCatching { node.isPassword }.getOrDefault(false),
        children = children,
    )
}

private fun findRealMatches(
    root: AccessibilityNodeInfo,
    selector: A11ySelector,
    limit: Int,
): List<AccessibilityNodeInfo> {
    val out = ArrayList<AccessibilityNodeInfo>(limit.coerceAtMost(8))
    val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
    queue.add(root to 0)
    var visited = 0
    while (queue.isNotEmpty() && out.size < limit && visited < A11Y_HARD_MAX_NODES * 5) {
        val (node, depth) = queue.removeFirst()
        visited++
        if (matchesSelector(node.toBareSnapshot(), selector)) out += node
        if (out.size >= limit) break
        if (depth < A11Y_MAX_DEPTH) {
            val count = runCatching { node.childCount }.getOrDefault(0)
            for (index in 0 until count) {
                val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
                queue.add(child to depth + 1)
            }
        }
    }
    return out
}

private fun clickableAncestor(node: AccessibilityNodeInfo, maxHops: Int = 6): AccessibilityNodeInfo? {
    var current: AccessibilityNodeInfo? = node
    var hops = 0
    while (hops <= maxHops) {
        val nodeOrNull = current ?: return null
        if (nodeOrNull.isClickable && nodeOrNull.isEnabled) return nodeOrNull
        current = runCatching { nodeOrNull.parent }.getOrNull()
        hops++
    }
    return null
}

private fun scrollableAncestor(node: AccessibilityNodeInfo, maxHops: Int = 8): AccessibilityNodeInfo? {
    var current: AccessibilityNodeInfo? = node
    var hops = 0
    while (hops <= maxHops) {
        val nodeOrNull = current ?: return null
        if (nodeOrNull.isScrollable && nodeOrNull.isEnabled) return nodeOrNull
        current = runCatching { nodeOrNull.parent }.getOrNull()
        hops++
    }
    return null
}

private fun findFirstScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
    val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
    queue.add(root to 0)
    var visited = 0
    while (queue.isNotEmpty() && visited < A11Y_HARD_MAX_NODES * 5) {
        val (node, depth) = queue.removeFirst()
        visited++
        if (node.isScrollable && node.isEnabled) return node
        if (depth < A11Y_MAX_DEPTH) {
            val count = runCatching { node.childCount }.getOrDefault(0)
            for (index in 0 until count) {
                val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
                queue.add(child to depth + 1)
            }
        }
    }
    return null
}

private fun editableTarget(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
    if (node.isEditable && node.isEnabled) return node
    val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
    queue.add(node to 0)
    var visited = 0
    while (queue.isNotEmpty() && visited < 64) {
        val (current, depth) = queue.removeFirst()
        visited++
        if (current !== node && current.isEditable && current.isEnabled) return current
        if (depth < 4) {
            val count = runCatching { current.childCount }.getOrDefault(0)
            for (index in 0 until count) {
                val child = runCatching { current.getChild(index) }.getOrNull() ?: continue
                queue.add(child to depth + 1)
            }
        }
    }
    return null
}

private fun findFirstEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
    editableTarget(root)

/* -------------------------------- tools -------------------------------- */

class AccessibilityTool(
    private val context: Context,
    override val name: String,
) : Tool {
    override val description: String = when (name) {
        "a11y_status" ->
            "查询无障碍服务状态：是否已启用/已连接、当前前台包名与窗口、可用能力；未启用时返回开启路径。只读。"
        "a11y_screen" ->
            "读取当前前台窗口的无障碍节点树（viewId/text/contentDescription/class/bounds/可点击性），" +
                "默认最多 80 节点、单条文本 200 字符；密码框内容不输出。只读；使用前先把目标应用切到前台。"
        "a11y_click" ->
            "按 view_id/text/content_description 查找控件并点击（匹配到不可点击节点时自动向上找可点击父节点）；" +
                "找不到时返回候选控件。"
        "a11y_long_click" ->
            "按 view_id/text/content_description 查找控件并长按（自动向上找可长按/可点击父节点）；" +
                "找不到时返回候选控件。"
        "a11y_scroll" ->
            "滚动当前窗口的可滚动控件：direction=forward/backward，可用选择器指定列表容器；省略则滚动第一个可滚动节点。"
        "a11y_text" ->
            "向输入框写入文本（先 ACTION_FOCUS 再 ACTION_SET_TEXT）；可用 view_id/content_description 指定输入框，" +
                "省略则写当前焦点输入框。text 可为空字符串以清空。"
        "a11y_action" ->
            "执行全局无障碍动作：back（返回）/ home（桌面）/ recents（最近任务）。"
        else -> "无障碍自动化工具"
    }

    override val parameters: JsonElement = when (name) {
        "a11y_status" -> schemaOf()
        "a11y_screen" -> schemaOf(
            "max_nodes" to intParam("最多返回节点数，默认 80，范围 1-400"),
        )
        "a11y_click", "a11y_long_click" -> jsonSchema(
            selectorProps() + mapOf("index" to intParam("同一选择器匹配到多个控件时的序号，从 0 开始，默认 0")),
        )
        "a11y_scroll" -> jsonSchema(
            mapOf(
                "direction" to strParam("滚动方向：forward（默认，向下/向前）或 backward（向上/向后）"),
            ) + selectorProps(),
        )
        "a11y_text" -> jsonSchema(
            mapOf(
                "text" to strParam("要写入的文本，最长 10000 字符；空字符串表示清空输入框"),
                "view_id" to strParam("输入框资源 id，完整或 :id/xxx 后缀"),
                "content_description" to strParam("输入框内容描述"),
                "match" to strParam("content_description 匹配方式：contains（默认）或 exact"),
            ),
            required = listOf("text"),
        )
        "a11y_action" -> schemaOf(
            "action" to strParam("动作：back | home | recents"),
            required = listOf("action"),
        )
        else -> schemaOf()
    }

    override val destructive: Boolean
        get() = name == "a11y_click" || name == "a11y_long_click" ||
            name == "a11y_text" || name == "a11y_action"

    override suspend fun execute(args: JsonObject): String = runCatching {
        val service = AndagentAccessibilityService.instance()
        if (name == "a11y_status") {
            status(service)
        } else if (service == null) {
            missingServiceError()
        } else {
            withContext(Dispatchers.Main.immediate) {
                when (name) {
                    "a11y_screen" -> screen(service, args)
                    "a11y_click" -> click(service, args, long = false)
                    "a11y_long_click" -> click(service, args, long = true)
                    "a11y_scroll" -> scroll(service, args)
                    "a11y_text" -> setText(service, args)
                    "a11y_action" -> globalAction(service, args)
                    else -> "ERROR: " + tr("未知无障碍工具 $name", "unknown accessibility tool $name")
                }
            }
        }
    }.getOrElse { "ERROR: ${it.message ?: it.javaClass.simpleName}" }

    private suspend fun status(service: AndagentAccessibilityService?): String {
        val connected = service != null && AndagentAccessibilityService.isConnected()
        val enabled = AndagentAccessibilityService.isEnabled(context) || connected
        val root = if (connected) {
            withContext(Dispatchers.Main.immediate) {
                runCatching { service?.rootInActiveWindow }.getOrNull()
            }
        } else {
            null
        }
        val packageName = root?.packageName?.toString()?.takeIf { it.isNotBlank() }
            ?: service?.lastPackage
        val windowClass = root?.className?.toString()?.takeIf { it.isNotBlank() }
            ?: service?.lastEventClass
        return buildJsonObject {
            put("enabled", enabled)
            put("connected", connected)
            put("package", packageName ?: "")
            put("windowClass", windowClass ?: "")
            put("eventCount", service?.eventCount ?: 0)
            put("lastEventMs", service?.lastEventMs ?: 0L)
            put("capabilities", buildJsonObject {
                put("readScreen", connected)
                put("click", connected)
                put("longClick", connected)
                put("scroll", connected)
                put("setText", connected)
                put("globalActions", if (connected) "back,home,recents" else "")
            })
            if (!connected) put("guide", serviceGuide(enabled))
        }.toString()
    }

    private fun screen(service: AndagentAccessibilityService, args: JsonObject): String {
        val root = service.rootInActiveWindow
            ?: return "ERROR: " + tr(
                "当前活动窗口为空（锁屏、系统弹窗或应用尚未绘制）。请稍后重试，或先确认目标应用在前台。",
                "No active window (lock screen, system dialog, or nothing drawn yet). Retry after the target app is in front.",
            )
        val maxNodes = (args.intArg("max_nodes") ?: A11Y_DEFAULT_MAX_NODES)
            .coerceIn(1, A11Y_HARD_MAX_NODES)
        val budget = A11yBudget(A11Y_HARD_MAX_NODES)
        val snapshot = snapshotFromNode(root, 0, budget)
            ?: return "ERROR: " + tr("节点树读取失败", "failed to read the node tree")
        return serializeScreen(
            root = snapshot,
            packageName = root.packageName?.toString(),
            windowClass = root.className?.toString(),
            maxNodes = maxNodes,
            sourceTruncated = budget.truncated || budget.depthTruncated,
        )
    }

    private fun click(
        service: AndagentAccessibilityService,
        args: JsonObject,
        long: Boolean,
    ): String {
        val selector = selectorFrom(args) ?: return selectorMissing()
        val index = (args.intArg("index") ?: 0).coerceAtLeast(0)
        val root = service.rootInActiveWindow ?: return windowMissing()
        val matches = findRealMatches(root, selector, maxOf(index + 1, 8))
        val matched = matches.getOrNull(index)
            ?: return noMatchError(selector, matches)
        val target = clickableAncestor(matched)
            ?: return "ERROR: " + tr(
                "找到控件但自身与最多 6 层父节点都不可点击：${describeNode(matched.toBareSnapshot())}",
                "Found the node, but neither it nor six ancestors are clickable: ${describeNode(matched.toBareSnapshot())}",
            )
        val action = if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
        val ok = target.performAction(action)
        if (!ok) {
            return "ERROR: " + tr(
                "系统拒绝${if (long) "长按" else "点击"}动作（控件可能不可见或被遮挡）：${describeNode(target.toBareSnapshot())}",
                "The system rejected the ${if (long) "long-click" else "click"} action (node hidden or covered): ${describeNode(target.toBareSnapshot())}",
            )
        }
        return buildJsonObject {
            put("ok", true)
            put("action", if (long) "long_click" else "click")
            put("package", target.packageName?.toString() ?: "")
            put("clicked", describeNode(target.toBareSnapshot()))
        }.toString()
    }

    private fun scroll(service: AndagentAccessibilityService, args: JsonObject): String {
        val directionRaw = args.strArg("direction") ?: "forward"
        val forward = normalizeScrollDirection(directionRaw)
            ?: return "ERROR: " + tr(
                "direction 仅支持 forward 或 backward，收到 $directionRaw",
                "direction must be forward or backward, got $directionRaw",
            )
        val selector = selectorFrom(args)
        val root = service.rootInActiveWindow ?: return windowMissing()
        val target = if (selector != null) {
            val matches = findRealMatches(root, selector, 8)
            val matched = matches.firstOrNull() ?: return noMatchError(selector, matches)
            scrollableAncestor(matched)
                ?: return "ERROR: " + tr(
                    "匹配控件及其父节点都不可滚动：${describeNode(matched.toBareSnapshot())}",
                    "Neither the matched node nor its ancestors are scrollable: ${describeNode(matched.toBareSnapshot())}",
                )
        } else {
            findFirstScrollable(root)
                ?: return "ERROR: " + tr(
                    "当前窗口没有可滚动控件",
                    "No scrollable control in the current window",
                )
        }
        val action = if (forward) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        val ok = target.performAction(action)
        if (!ok) {
            return "ERROR: " + tr(
                "系统拒绝滚动动作（可能已到边界）：${describeNode(target.toBareSnapshot())}",
                "The system rejected the scroll action (possibly at the edge): ${describeNode(target.toBareSnapshot())}",
            )
        }
        return buildJsonObject {
            put("ok", true)
            put("action", if (forward) "scroll_forward" else "scroll_backward")
            put("package", target.packageName?.toString() ?: "")
            put("scrolled", describeNode(target.toBareSnapshot()))
        }.toString()
    }

    private fun setText(service: AndagentAccessibilityService, args: JsonObject): String {
        val value = args.rawStringArg("text")
            ?: return "ERROR: " + tr("缺少 text", "missing text")
        if (value.length > A11Y_MAX_INPUT_CHARS) {
            return "ERROR: " + tr(
                "text 超过 $A11Y_MAX_INPUT_CHARS 字符上限",
                "text exceeds the $A11Y_MAX_INPUT_CHARS character limit",
            )
        }
        val selector = parseSelector(
            viewId = args.strArg("view_id"),
            text = null,
            description = args.strArg("content_description"),
            match = args.strArg("match"),
        )
        val root = service.rootInActiveWindow ?: return windowMissing()
        val matched = if (selector != null) {
            val matches = findRealMatches(root, selector, 8)
            matches.firstOrNull() ?: return noMatchError(selector, matches)
        } else {
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: findFirstEditable(root)
                ?: return "ERROR: " + tr(
                    "没有找到输入框：当前窗口无焦点输入框，请用 view_id/content_description 指定",
                    "No input found: no focused editor in this window; specify view_id/content_description",
                )
        }
        val target = editableTarget(matched)
            ?: return "ERROR: " + tr(
                "匹配控件不是可编辑输入框：${describeNode(matched.toBareSnapshot())}",
                "The matched node is not an editable field: ${describeNode(matched.toBareSnapshot())}",
            )
        val focused = target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val bundle = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        val ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
        if (!ok) {
            return "ERROR: " + tr(
                "ACTION_SET_TEXT 失败（focus=${if (focused) "ok" else "no"}）：${describeNode(target.toBareSnapshot())}",
                "ACTION_SET_TEXT failed (focus=${if (focused) "ok" else "no"}): ${describeNode(target.toBareSnapshot())}",
            )
        }
        return buildJsonObject {
            put("ok", true)
            put("action", "set_text")
            put("focused", focused)
            put("chars", value.length)
            put("package", target.packageName?.toString() ?: "")
            put("target", describeNode(target.toBareSnapshot()))
        }.toString()
    }

    private fun globalAction(service: AndagentAccessibilityService, args: JsonObject): String {
        val action = normalizeGlobalAction(args.strArg("action"))
            ?: return "ERROR: " + tr(
                "action 仅支持 back / home / recents",
                "action must be back / home / recents",
            )
        val code = when (action) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            else -> AccessibilityService.GLOBAL_ACTION_RECENTS
        }
        if (!service.performGlobalAction(code)) {
            return "ERROR: " + tr("系统拒绝全局动作 $action", "the system rejected global action $action")
        }
        return buildJsonObject {
            put("ok", true)
            put("action", action)
        }.toString()
    }

    private fun selectorFrom(args: JsonObject): A11ySelector? = parseSelector(
        viewId = args.strArg("view_id"),
        text = args.strArg("text"),
        description = args.strArg("content_description"),
        match = args.strArg("match"),
    )

    private fun selectorMissing(): String = "ERROR: " + tr(
        "至少提供 view_id / text / content_description 之一",
        "provide at least one of view_id / text / content_description",
    )

    private fun windowMissing(): String = "ERROR: " + tr(
        "当前活动窗口为空；请先确认目标应用在前台并重试 a11y_screen",
        "No active window; bring the target app to the front and retry a11y_screen",
    )

    private fun noMatchError(selector: A11ySelector, matches: List<AccessibilityNodeInfo>): String {
        val candidates = matches.take(5).joinToString("\n") { "- " + describeNode(it.toBareSnapshot()) }
        val selectorText = listOfNotNull(
            selector.viewId?.let { "view_id=$it" },
            selector.text?.let { "text=$it" },
            selector.contentDescription?.let { "content_description=$it" },
        ).joinToString(", ")
        val header = tr("未找到匹配控件（$selectorText）", "No matching node ($selectorText)")
        return if (candidates.isBlank()) {
            "ERROR: $header"
        } else {
            "ERROR: $header\n" + tr("候选控件：", "Candidates:") + "\n" + candidates
        }
    }

    private fun missingServiceError(): String = "ERROR: " + serviceGuide(
        AndagentAccessibilityService.isEnabled(context),
    )

    private fun serviceGuide(enabled: Boolean): String = if (enabled) {
        tr(
            "无障碍服务已启用但尚未连接：请到 系统设置 → 无障碍 → AndAgent 关闭后重新开启，然后重试。",
            "The accessibility service is enabled but not connected: turn AndAgent off and on again in Settings → Accessibility, then retry.",
        )
    } else {
        tr(
            "无障碍服务未启用：请打开 系统设置 → 无障碍 → AndAgent → 开启；也可先调用 open_settings（panel=accessibility）跳转。" +
                "Android 13+ 若提示受限设置，请先在应用信息页允许受限设置。",
            "Accessibility service is off: enable AndAgent in Settings → Accessibility (or call open_settings with panel=accessibility). " +
                "On Android 13+, if restricted settings are mentioned, allow them from the app info page first.",
        )
    }
}

private fun selectorProps(): Map<String, JsonElement> = mapOf(
    "view_id" to strParam("资源 id，如 com.app:id/login，或只写 login / :id/login 后缀匹配"),
    "text" to strParam("控件文本"),
    "content_description" to strParam("控件内容描述"),
    "match" to strParam("text/content_description 匹配方式：contains（默认）或 exact"),
)

private fun JsonObject.rawStringArg(key: String): String? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    if (primitive is JsonNull) return null
    return primitive.content
}
