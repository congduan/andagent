package com.dc.andagent.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-function tests for R71 accessibility automation. The Android service
 * and live node tree need a real device (and a manually enabled service), so
 * only selector matching, tree serialisation and config parsing are covered.
 */
class AccessibilityToolsTest {

    private fun node(
        viewId: String? = null,
        text: String? = null,
        description: String? = null,
        className: String = "android.widget.TextView",
        bounds: String = "0,0,100,100",
        clickable: Boolean = false,
        longClickable: Boolean = false,
        scrollable: Boolean = false,
        editable: Boolean = false,
        enabled: Boolean = true,
        focused: Boolean = false,
        password: Boolean = false,
        children: List<A11yNodeSnapshot> = emptyList(),
    ) = A11yNodeSnapshot(
        packageName = "com.example",
        viewId = viewId,
        text = text,
        contentDescription = description,
        className = className,
        bounds = bounds,
        clickable = clickable,
        longClickable = longClickable,
        scrollable = scrollable,
        editable = editable,
        enabled = enabled,
        focused = focused,
        password = password,
        children = children,
    )

    private fun rootJson(serialized: String) = Json.parseToJsonElement(serialized).jsonObject

    @Test
    fun selectorRequiresAtLeastOneFieldAndDefaultsToContains() {
        assertNull(parseSelector(null, null, null, null))
        assertNull(parseSelector("  ", "\n", null, "exact"))

        val byId = parseSelector(" login ", null, null, null)!!
        assertEquals("login", byId.viewId)
        assertTrue(byId.contains)

        val exact = parseSelector(null, " Sign in ", null, "EXACT")!!
        assertEquals("Sign in", exact.text)
        assertFalse(exact.contains)

        // Unknown mode falls back to contains instead of rejecting the call.
        assertTrue(parseSelector(null, "ok", null, "starts-with")!!.contains)
    }

    @Test
    fun textMatchingSupportsContainsAndExactIgnoringCase() {
        val button = node(text = "Sign in now")

        assertTrue(matchesSelector(button, parseSelector(null, "sign in", null, "contains")!!))
        assertTrue(matchesSelector(button, parseSelector(null, "SIGN IN NOW", null, "exact")!!))
        assertFalse(matchesSelector(button, parseSelector(null, "sign in", null, "exact")!!))
        assertFalse(matchesSelector(node(text = "  "), parseSelector(null, "x", null)!!))
        assertFalse(matchesSelector(node(text = null), parseSelector(null, "x", null)!!))
    }

    @Test
    fun viewIdMatchesFullBareAndSuffixForms() {
        val button = node(viewId = "com.example:id/btn_login")

        assertTrue(matchesSelector(button, parseSelector("com.example:id/btn_login", null, null)!!))
        assertTrue(matchesSelector(button, parseSelector("btn_login", null, null)!!))
        assertTrue(matchesSelector(button, parseSelector(":id/btn_login", null, null)!!))
        assertTrue(matchesSelector(button, parseSelector("id/btn_login", null, null)!!))
        assertFalse(matchesSelector(button, parseSelector("com.other:id/btn_login", null, null)!!))
        assertFalse(matchesSelector(button, parseSelector("btn", null, null)!!))
        assertFalse(matchesSelector(node(viewId = null), parseSelector("btn_login", null, null)!!))
    }

    @Test
    fun contentDescriptionAndAllFieldsAreCombinedWithAnd() {
        val cell = node(viewId = "com.example:id/ok", text = "确定", description = "确认按钮")

        assertTrue(matchesSelector(cell, A11ySelector(contentDescription = "确认", contains = true)))
        assertTrue(matchesSelector(cell, A11ySelector(contentDescription = "确认按钮", contains = false)))
        assertFalse(matchesSelector(cell, A11ySelector(contentDescription = "取消", contains = true)))
        // Both fields must match: matching id but wrong text is not a hit.
        assertFalse(matchesSelector(cell, A11ySelector(viewId = "ok", text = "取消")))
        assertTrue(matchesSelector(cell, A11ySelector(viewId = "ok", text = "确定")))
    }

    @Test
    fun findAndCollectTraverseDepthFirstInTreeOrder() {
        val first = node(text = "target")
        val second = node(text = "target")
        val tree = node(
            text = "root",
            children = listOf(
                node(text = "a", children = listOf(first)),
                node(text = "b", children = listOf(second)),
            ),
        )

        assertTrue(findFirstMatch(tree, parseSelector(null, "target", null)!!) === first)
        assertNull(findFirstMatch(tree, parseSelector(null, "missing", null)!!))

        val found = collectMatches(tree, parseSelector(null, "target", null)!!, 8)
        assertEquals(2, found.size)
        assertTrue(found[0] === first)
        assertTrue(found[1] === second)
        assertEquals(1, collectMatches(tree, parseSelector(null, "target", null)!!, 1).size)
    }

    @Test
    fun serializationCapsNodeCountAndMarksTruncation() {
        val tree = node(children = (1..5).map { node(text = "n$it") })
        val json = rootJson(serializeScreen(tree, maxNodes = 3))

        assertEquals(3, json["nodeCount"]!!.jsonPrimitive.int)
        assertTrue(json["truncated"]!!.jsonPrimitive.boolean)
        assertEquals(3, json["maxNodes"]!!.jsonPrimitive.int)
        val root = json["root"]!!.jsonObject
        assertEquals(2, root["children"]!!.jsonArray.size)
        assertEquals("TextView", root["className"]!!.jsonPrimitive.content)
    }

    @Test
    fun serializationMarksDepthTruncation() {
        val tree = node(children = listOf(node(text = "child")))
        val json = rootJson(serializeScreen(tree, maxDepth = 0))

        assertTrue(json["truncated"]!!.jsonPrimitive.boolean)
        assertTrue(json["root"]!!.jsonObject["children"]!!.jsonArray.isEmpty())
    }

    @Test
    fun passwordTextIsRemovedAndLongTextIsTruncated() {
        val secret = rootJson(serializeScreen(node(text = "hunter2", description = "pwd", password = true)))
            .let { it["root"]!!.jsonObject }
        assertNull(secret["text"])
        assertNull(secret["description"])
        assertTrue(secret["password"]!!.jsonPrimitive.boolean)

        // Error/candidate strings must not leak the value either.
        val described = describeNode(node(text = "hunter2", description = "pwd", password = true))
        assertFalse(described.contains("hunter2"))
        assertFalse(described.contains("pwd"))

        val longText = "a".repeat(500)
        val truncated = rootJson(serializeScreen(node(text = longText)))
            .let { it["root"]!!.jsonObject["text"]!!.jsonPrimitive.content }
        assertEquals(A11Y_MAX_TEXT_CHARS, truncated.length)
        assertTrue(truncated.endsWith("…"))

        val collapsed = rootJson(serializeScreen(node(text = "  line1\nline2\t")))
            .let { it["root"]!!.jsonObject["text"]!!.jsonPrimitive.content }
        assertEquals("line1 line2", collapsed)
    }

    @Test
    fun enabledServiceListAndActionNamesAreParsedTolerantly() {
        val component = "com.dc.andagent/com.dc.andagent.tools.AndagentAccessibilityService"
        assertTrue(isServiceListed("com.a/com.a.Svc:$component", component))
        assertTrue(isServiceListed(" $component ", component))
        assertFalse(isServiceListed("com.a/com.a.Svc", component))
        assertFalse(isServiceListed(null, component))
        assertFalse(isServiceListed("", component))
        assertFalse(isServiceListed(component, ""))

        assertEquals("back", normalizeGlobalAction(" BACK "))
        assertNull(normalizeGlobalAction("menu"))
        assertNull(normalizeGlobalAction(null))

        assertTrue(normalizeScrollDirection(null)!!)
        assertTrue(normalizeScrollDirection("forward")!!)
        assertFalse(normalizeScrollDirection("backward")!!)
        assertFalse(normalizeScrollDirection("up")!!)
        assertNull(normalizeScrollDirection("left"))
    }
}
