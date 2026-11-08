package com.dc.andagent.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for the hosts rule parser/matcher (no Android runtime). */
class HostsRulesTest {

    @Test
    fun normalizeLowercasesAndStripsWildcardPrefix() {
        assertEquals("ads.example.com", HostsRules.normalize("  *.ADS.Example.COM.  "))
        assertEquals("example.com", HostsRules.normalize(".example.com"))
    }

    @Test
    fun normalizeRejectsCommentsBareIpsAndSingleLabels() {
        assertNull(HostsRules.normalize("# comment"))
        assertNull(HostsRules.normalize("! adblock comment"))
        assertNull(HostsRules.normalize("0.0.0.0"))
        assertNull(HostsRules.normalize("localhost"))
        assertNull(HostsRules.normalize(""))
    }

    @Test
    fun parseHostsFormatKeepsSinkholledDomains() {
        val report = HostsRules.parse(
            """
            # header
            0.0.0.0 ads.example.com
            127.0.0.1 tracker.example.net
            1.2.3.4 keep.example.com
            """.trimIndent(),
        )
        assertEquals(setOf("ads.example.com", "tracker.example.net"), report.domains)
        assertEquals(1, report.invalid)
    }

    @Test
    fun parseAcceptsPlainDomainsAndDeduplicates() {
        val report = HostsRules.parse("a.example.com\na.example.com\nb.example.net")
        assertEquals(setOf("a.example.com", "b.example.net"), report.domains)
        assertEquals(3, report.lines)
    }

    @Test
    fun matchesExactDomainAndSubdomains() {
        val rules = setOf("example.com")
        assertTrue(HostsRules.matches("example.com", rules))
        assertTrue(HostsRules.matches("a.b.example.com", rules))
        assertFalse(HostsRules.matches("notexample.com", rules))
        assertFalse(HostsRules.matches("example.com.evil.net", rules))
    }

    @Test
    fun subdomainRuleDoesNotBlockParentDomain() {
        val rules = setOf("ads.example.com")
        assertTrue(HostsRules.matches("ads.example.com", rules))
        assertTrue(HostsRules.matches("cdn.ads.example.com", rules))
        assertFalse(HostsRules.matches("example.com", rules))
    }

    @Test
    fun matchedRuleReportsTheEffectiveRule() {
        val rules = setOf("doubleclick.net", "ads.example.com")
        assertEquals("doubleclick.net", HostsRules.matchedRule("pagead.doubleclick.net", rules))
        assertNull(HostsRules.matchedRule("example.com", rules))
    }

    @Test
    fun builtInRulesAreNormalizedDomains() {
        val builtIn = HostsRules.builtIn()
        assertTrue(builtIn.size >= 40)
        assertTrue(builtIn.all { HostsRules.normalize(it) == it })
        assertTrue(HostsRules.matches("ad.doubleclick.net", builtIn))
    }

    @Test
    fun emptyRuleSetNeverMatches() {
        assertFalse(HostsRules.matches("ads.example.com", emptySet()))
    }
}
