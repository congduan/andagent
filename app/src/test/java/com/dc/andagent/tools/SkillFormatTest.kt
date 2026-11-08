package com.dc.andagent.tools

import org.junit.Assert.*
import org.junit.Test

class SkillFormatTest {
    @Test fun parsesPromptAndAllowlist() {
        val skill = SkillFormat.parse("""---
name: weather-pro
description: 天气
 tools: ignored
tools: [web_read, web_search]
prompt: |
  你是天气助手
  回答简短
---
## 用法
请搜索
""".trimIndent())
        assertEquals("weather-pro", skill.name)
        assertEquals(setOf("web_read", "web_search"), skill.allow)
        assertTrue(skill.prompt.contains("回答简短"))
        assertTrue(skill.body.contains("请搜索"))
    }
    @Test fun parsesVersionAndAuthor() {
        val skill = SkillFormat.parse("""---
name: weather-pro
version: 1.2.0
author: andagent
---
## 用法
""".trimIndent() + "\n")
        assertEquals("1.2.0", skill.version)
        assertEquals("andagent", skill.author)
    }

    @Test fun ignoresUnsafeVersion() {
        val skill = SkillFormat.parse("---\nname: weather-pro\nversion: ../1.0\n---\n")
        assertEquals("", skill.version)
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsTraversalName() {
        SkillFormat.parse("---\nname: ../bad\n---\n")
    }
}
