package com.dc.andagent.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillIndexTest {
    @Test fun parsesOfficialObjectShape() {
        val entries = SkillIndex.parse(
            """
            {"schema":1,"skills":[
              {"name":"code-review","description":"审查","source":"https://github.com/u/r/tree/main/skills/code-review",
               "version":"1.0.0","author":"andagent","tags":["dev"],"rating":4.5,"ratingCount":12}
            ]}
            """.trimIndent()
        )
        assertEquals(1, entries.size)
        val entry = entries.first()
        assertEquals("code-review", entry.name)
        assertEquals("1.0.0", entry.version)
        assertEquals(4.5, entry.rating, 0.0)
        assertEquals(12, entry.ratingCount)
        assertTrue(entry.matches("审查"))
        assertTrue(entry.matches("dev"))
    }

    @Test fun parsesPlainArrayShape() {
        val entries = SkillIndex.parse(
            """[{"name":"a","source":"https://github.com/u/r","version":"2.0.0"}]"""
        )
        assertEquals("github:u/r", entries.first().key)
        assertEquals("2.0.0", entries.first().version)
    }

    @Test fun clampsRatingAndSkipsInvalidRows() {
        val entries = SkillIndex.parse(
            """[
              {"name":"bad"},
              {"name":"a","source":"https://example.com/a","rating":9,"ratingCount":-3}
            ]"""
        )
        assertEquals(1, entries.size)
        assertEquals(5.0, entries.first().rating, 0.0)
        assertEquals(0, entries.first().ratingCount)
        assertNull(entries.first().version)
    }

    @Test fun keepsDifferentSubPathsOfSameRepo() {
        val entries = SkillIndex.parse(
            """[
              {"name":"a","source":"https://github.com/u/r/tree/main/skills/a"},
              {"name":"b","source":"https://github.com/u/r/tree/main/skills/b"},
              {"name":"a2","source":"https://github.com/u/r/tree/main/skills/a/"}
            ]"""
        )
        assertEquals(2, entries.size)
        assertEquals(entries[0].key, entries[1].key)
    }

    @Test fun toleratesGarbage() {
        assertEquals(0, SkillIndex.parse("not json").size)
        assertEquals(0, SkillIndex.parse("""{"skills":"nope"}""").size)
    }
}
