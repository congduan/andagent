package com.dc.andagent.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SkillSourceTest {
    @Test fun parsesPlainRepoUrl() {
        val repo = SkillSource.parseGitHub("https://github.com/user/skill-repo")!!
        assertEquals("user", repo.owner)
        assertEquals("skill-repo", repo.name)
        assertNull(repo.branch)
        assertNull(repo.path)
        assertNull(repo.file)
        assertEquals("https://github.com/user/skill-repo", repo.url)
    }

    @Test fun normalizesGitSuffixTrailingSlashAndQuery() {
        val repo = SkillSource.parseGitHub("  https://github.com/user/skill-repo.git/?tab=readme#top ")!!
        assertEquals("skill-repo", repo.name)
        assertNull(repo.branch)
    }

    @Test fun parsesTreeSubPath() {
        val repo = SkillSource.parseGitHub("https://github.com/user/monorepo/tree/main/skills/weather")!!
        assertEquals("main", repo.branch)
        assertEquals("skills/weather", repo.path)
        assertNull(repo.file)
    }

    @Test fun parsesBlobAsSingleFile() {
        val repo = SkillSource.parseGitHub("https://github.com/user/skill-repo/blob/master/SKILL.md")!!
        assertEquals("https://raw.githubusercontent.com/user/skill-repo/master/SKILL.md", repo.file)
    }

    @Test fun ignoresNonRepoUrls() {
        assertNull(SkillSource.parseGitHub("https://example.com/a/b"))
        assertNull(SkillSource.parseGitHub("https://raw.githubusercontent.com/user/skill-repo/main/SKILL.md"))
        // .zip 直链必须落到通用下载分支，不能被当成仓库
        assertNull(SkillSource.parseGitHub("https://github.com/user/skill-repo/archive/refs/heads/main.zip"))
    }

    @Test fun mergesKeysOfSameRepo() {
        val plain = SkillSource.key("https://github.com/User/Skill-Repo")
        assertEquals("github:user/skill-repo", plain)
        assertEquals(plain, SkillSource.key("https://github.com/user/skill-repo.git"))
        assertEquals(plain, SkillSource.key("https://github.com/User/Skill-Repo/tree/main/skills/x"))
    }

    @Test fun keepsLocalPathAsKey() {
        assertEquals("/sdcard/download/andagent/skills/weather", SkillSource.key("/sdcard/Download/andagent/skills/weather/"))
    }
}
