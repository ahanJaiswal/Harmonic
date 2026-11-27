package com.harmonic.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompareReadingsTest {
    private val day = 86_400_000L
    private fun reading(mmS: Double?, zone: String = "B", daysAgo: Int = 0) =
        Reading(System.currentTimeMillis() - daysAgo * day, zone, "imbalance", mmS, 0.8, "cloud")

    @Test
    fun firstCheckHasNoComparison() {
        assertNull(compareReadings(reading(2.0), null))
    }

    @Test
    fun smallChangeReadsAsTheSame() {
        val c = compareReadings(reading(2.2), reading(2.0, daysAgo = 1))!!
        assertEquals("About the same shaking as last time (yesterday).", c.text)
        assertFalse(c.worse)
    }

    @Test
    fun increaseIsWorseWithPercent() {
        val c = compareReadings(reading(3.0), reading(2.0, daysAgo = 3))!!
        assertEquals("50% more shaking than last time (3 days ago).", c.text)
        assertTrue(c.worse)
    }

    @Test
    fun doublingSaysTimes() {
        val c = compareReadings(reading(6.0), reading(2.0))!!
        assertEquals("Shaking is 3 times what it was last time (today).", c.text)
        assertTrue(c.worse)
    }

    @Test
    fun decreaseIsBetter() {
        val c = compareReadings(reading(1.0), reading(2.0))!!
        assertEquals("50% less shaking than last time (today).", c.text)
        assertFalse(c.worse)
    }

    @Test
    fun withoutShakeFallsBackToZone() {
        val c = compareReadings(reading(null, zone = "C"), reading(null, zone = "A"))!!
        assertEquals("Worse than last time (today).", c.text)
        assertTrue(c.worse)
    }
}
