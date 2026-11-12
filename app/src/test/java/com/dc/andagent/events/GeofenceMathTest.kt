package com.dc.andagent.events

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class GeofenceMathTest {
    @Test fun haversineIsZeroForSamePoint() {
        assertEquals(0.0, GeofenceMath.distanceMeters(39.9042, 116.4074, 39.9042, 116.4074), 0.001)
    }

    @Test fun haversineMatchesBeijingShanghai() {
        // Reference great-circle distance ≈ 1067.3 km; allow 1% for radius/model drift.
        val distance = GeofenceMath.distanceMeters(39.9042, 116.4074, 31.2304, 121.4737)
        assertTrue("distance=$distance", abs(distance - 1_067_311.6) / 1_067_311.6 < 0.01)
    }

    @Test fun haversineOneDegreeLatitude() {
        // One degree of latitude is ~111.2 km on the mean-radius sphere.
        val distance = GeofenceMath.distanceMeters(0.0, 0.0, 1.0, 0.0)
        assertTrue("distance=$distance", abs(distance - 111_195.1) / 111_195.1 < 0.01)
    }

    @Test fun haversineIsSymmetric() {
        val ab = GeofenceMath.distanceMeters(39.9, 116.4, 31.2, 121.5)
        val ba = GeofenceMath.distanceMeters(31.2, 121.5, 39.9, 116.4)
        assertEquals(ab, ba, 0.001)
    }

    @Test fun classifyInsideAndOutside() {
        val fence = FenceSpec("home", 39.9042, 116.4074, 500)
        assertEquals("inside", GeofenceMath.classify(39.9042, 116.4074, fence))
        // 0.01° latitude ≈ 1.11 km, clearly outside a 500 m fence.
        assertEquals("outside", GeofenceMath.classify(39.9142, 116.4074, fence))
    }

    @Test fun shouldFireHonoursEventFilterMatrix() {
        assertEquals("enter", GeofenceMath.shouldFire(false, true, "enter"))
        assertEquals("enter", GeofenceMath.shouldFire(false, true, "both"))
        assertNull(GeofenceMath.shouldFire(false, true, "exit"))
        assertEquals("exit", GeofenceMath.shouldFire(true, false, "exit"))
        assertEquals("exit", GeofenceMath.shouldFire(true, false, "both"))
        assertNull(GeofenceMath.shouldFire(true, false, "enter"))
        assertNull(GeofenceMath.shouldFire(true, true, "both"))
        assertNull(GeofenceMath.shouldFire(false, false, "both"))
        assertNull(GeofenceMath.shouldFire(null, true, "both"))
    }

    @Test fun singleDriftDoesNotFireOrChangeState() {
        var transition = GeofenceMath.observe(previousInside = false, candidateInside = null, nowInside = true, events = "both")
        assertNull(transition.fire)
        assertEquals(false, transition.lastInside)
        assertEquals(true, transition.candidateInside)

        // GPS drifts back before a second identical reading: candidate resets, state stays outside.
        transition = GeofenceMath.observe(false, transition.candidateInside, false, "both")
        assertNull(transition.fire)
        assertEquals(false, transition.lastInside)
        assertEquals(false, transition.candidateInside)

        transition = GeofenceMath.observe(false, transition.candidateInside, false, "both")
        assertNull(transition.fire)
        assertEquals(false, transition.lastInside)
    }

    @Test fun twoConsecutiveEnterReadingsFireOnce() {
        var transition = GeofenceMath.observe(false, null, true, "both")
        assertNull(transition.fire)
        transition = GeofenceMath.observe(transition.lastInside, transition.candidateInside, true, "both")
        assertEquals("enter", transition.fire)
        assertEquals(true, transition.lastInside)
        assertNull(transition.candidateInside)

        // Third reading must not duplicate the enter event.
        transition = GeofenceMath.observe(transition.lastInside, transition.candidateInside, true, "both")
        assertNull(transition.fire)
    }

    @Test fun twoConsecutiveExitReadingsFireOnce() {
        var transition = GeofenceMath.observe(true, null, false, "both")
        assertNull(transition.fire)
        transition = GeofenceMath.observe(transition.lastInside, transition.candidateInside, false, "both")
        assertEquals("exit", transition.fire)
        assertEquals(false, transition.lastInside)
        transition = GeofenceMath.observe(transition.lastInside, transition.candidateInside, false, "both")
        assertNull(transition.fire)
    }

    @Test fun eventFilterSuppressesFireButStillUpdatesState() {
        var transition = GeofenceMath.observe(false, null, true, "exit")
        assertNull(transition.fire)
        transition = GeofenceMath.observe(transition.lastInside, transition.candidateInside, true, "exit")
        assertNull(transition.fire)
        assertEquals(true, transition.lastInside)
    }

    @Test fun sampleIntervalGuardSkipsReadingsUnderTenSeconds() {
        assertTrue(GeofenceMath.shouldEvaluate(null, 5_000))
        assertFalse(GeofenceMath.shouldEvaluate(1_000, 10_999))
        assertTrue(GeofenceMath.shouldEvaluate(1_000, 11_000))
    }

    @Test fun normalizeEventsAcceptsOnlyKnownValues() {
        assertEquals("both", GeofenceMath.normalizeEvents(null))
        assertEquals("both", GeofenceMath.normalizeEvents("BOTH"))
        assertEquals("enter", GeofenceMath.normalizeEvents(" ENTER "))
        assertEquals("exit", GeofenceMath.normalizeEvents("exit"))
        assertNull(GeofenceMath.normalizeEvents("sometimes"))
    }

    @Test fun freshAccurateBaselineIsAccepted() {
        val now = 1_700_000_000_000L
        assertTrue(GeofenceMath.acceptableBaseline(now, now - 60_000, 50f))
        // Exactly at both limits is still acceptable.
        assertTrue(GeofenceMath.acceptableBaseline(now, now - GeofenceMath.BASELINE_MAX_AGE_MS, GeofenceMath.BASELINE_MAX_ACCURACY_M))
    }

    @Test fun staleBaselineIsRejected() {
        val now = 1_700_000_000_000L
        assertFalse(GeofenceMath.acceptableBaseline(now, now - GeofenceMath.BASELINE_MAX_AGE_MS - 1, 50f))
        // A fix from far in the future is clock skew, not a valid baseline.
        assertFalse(GeofenceMath.acceptableBaseline(now, now + 60_000, 50f))
    }

    @Test fun inaccurateOrUnknownAccuracyBaselineIsRejected() {
        val now = 1_700_000_000_000L
        assertFalse(GeofenceMath.acceptableBaseline(now, now - 60_000, GeofenceMath.BASELINE_MAX_ACCURACY_M + 1f))
        assertFalse(GeofenceMath.acceptableBaseline(now, now - 60_000, null))
    }

    @Test fun fenceSpecJsonRoundTrip() {
        val original = FenceSpec("home", 39.9042, 116.4074, 1200, "enter", true, 1_700_000_000_000L)
        val restored = FenceSpec.fromJson(original.toJson())
        assertEquals(original, restored)

        val unknownState = original.copy(lastInside = null, lastInsideAt = null)
        val restoredUnknown = FenceSpec.fromJson(unknownState.toJson())
        assertEquals(unknownState, restoredUnknown)
        assertNull(restoredUnknown?.lastInside)
    }

    @Test fun corruptFenceJsonDegradesToEmptyList() {
        assertEquals(emptyList<FenceSpec>(), FenceStore.decode(null))
        assertEquals(emptyList<FenceSpec>(), FenceStore.decode(JsonPrimitive("not-a-fence-array")))
        assertEquals(emptyList<FenceSpec>(), FenceStore.decode(JsonObject(emptyMap())))
    }

    @Test fun fenceDecodeSkipsInvalidEntriesAndCapsAtFive() {
        val valid = FenceSpec("home", 0.0, 0.0, 100)
        val mixed = kotlinx.serialization.json.buildJsonArray {
            add(valid.toJson())
            add(JsonPrimitive("junk"))
            add(kotlinx.serialization.json.buildJsonObject { put("name", "missing-coordinates") })
            repeat(6) { index -> add(FenceSpec("f$index", 1.0, 1.0, 200).toJson()) }
        }
        val decoded = FenceStore.decode(mixed)
        assertEquals(FenceStore.MAX_FENCES, decoded.size)
        assertEquals("home", decoded.first().name)
    }

    @Test fun fenceCountLimitRejectsSixthNameButAllowsReplacement() {
        val five = (1..5).map { FenceSpec("f$it", 0.0, 0.0, 100) }
        assertNull(FenceStore.addError(five, "f3"))
        val error = FenceStore.addError(five, "f6")
        assertNotNull(error)
        assertTrue(error!!.startsWith("ERROR:"))
    }

    @Test fun lastInsideJsonIsBooleanNotNull() {
        val json = FenceSpec("office", 31.2304, 121.4737, 300, "both", false, 42L).toJson()
        assertEquals(false, json["lastInside"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(42L, json["lastInsideAt"]?.jsonPrimitive?.content?.toLong())
    }
}
