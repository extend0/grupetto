package com.spop.poverlay.zone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoneMathTest {

    // Z1 <120, Z2 120-139, Z3 140-159, Z4 160-179, Z5 180+
    private val boundaries = listOf(120, 140, 160, 180)

    @Test
    fun `assigns each zone from the four transition points`() {
        assertEquals(1, zoneFor(90, boundaries))
        assertEquals(2, zoneFor(130, boundaries))
        assertEquals(3, zoneFor(150, boundaries))
        assertEquals(4, zoneFor(170, boundaries))
        assertEquals(5, zoneFor(200, boundaries))
    }

    @Test
    fun `a bpm sitting exactly on a transition belongs to the higher zone`() {
        assertEquals(2, zoneFor(120, boundaries))
        assertEquals(3, zoneFor(140, boundaries))
        assertEquals(5, zoneFor(180, boundaries))
    }

    @Test
    fun `rejects unusable boundaries instead of throwing`() {
        assertNull(zoneFor(130, null))
        assertNull(zoneFor(130, listOf(120, 140, 160)))
        assertNull(zoneFor(130, listOf(120, 140, 139, 180)))
        assertNull(zoneFor(130, listOf(0, 140, 160, 180)))
        assertNull(zoneBounds(2, listOf(120, 120, 160, 180)))
    }

    @Test
    fun `zone one has no floor and zone five has no ceiling`() {
        assertEquals(ZoneBounds(null, 120), zoneBounds(1, boundaries))
        assertEquals(ZoneBounds(180, null), zoneBounds(5, boundaries))
        assertEquals(ZoneBounds(120, 140), zoneBounds(2, boundaries))
    }

    @Test
    fun `rejects zones outside one through five`() {
        assertNull(zoneBounds(0, boundaries))
        assertNull(zoneBounds(6, boundaries))
    }

    @Test
    fun `band is closed at the floor and open at the ceiling`() {
        val band = ZoneBounds(120, 140)
        assertTrue(isInBand(120, band))
        assertTrue(isInBand(139, band))
        assertFalse(isInBand(119, band))
        assertFalse(isInBand(140, band))
    }

    @Test
    fun `open ended bands only constrain the side they have`() {
        assertTrue(isInBand(0, ZoneBounds(null, 120)))
        assertTrue(isInBand(999, ZoneBounds(180, null)))
    }

    @Test
    fun `strict mode insets the band on both sides`() {
        val strict = effectiveBand(ZoneBounds(120, 140), hysteresisBpm = 4, strict = true)
        assertEquals(ZoneBounds(124, 136), strict)
        assertFalse(isInBand(123, strict))
        assertTrue(isInBand(124, strict))
        assertFalse(isInBand(136, strict))
    }

    @Test
    fun `non strict mode leaves the band alone`() {
        val raw = ZoneBounds(120, 140)
        assertEquals(raw, effectiveBand(raw, hysteresisBpm = 4, strict = false))
    }

    @Test
    fun `inset is dropped when it would swallow a narrow band`() {
        // A 6 bpm band inset by 4 on each side would be empty and impossible to re-enter.
        val narrow = ZoneBounds(120, 126)
        assertEquals(narrow, effectiveBand(narrow, hysteresisBpm = 4, strict = true))
    }

    @Test
    fun `open ended bands are inset only on the side they have`() {
        assertEquals(ZoneBounds(184, null), effectiveBand(ZoneBounds(180, null), 4, strict = true))
        assertEquals(ZoneBounds(null, 116), effectiveBand(ZoneBounds(null, 120), 4, strict = true))
    }
}
