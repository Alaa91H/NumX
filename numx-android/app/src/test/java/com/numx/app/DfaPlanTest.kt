package com.numx.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DfaPlanTest {

    /**
     * Plan A: exactly two digits, the first digit is 1.
     * States: 0 = start, 1 = read "1", 2 = accepted.
     */
    private fun planA(): DfaPlan {
        val transitions = Array(3) { IntArray(10) { -1 } }
        transitions[0][1] = 1
        for (digit in 0..9) transitions[1][digit] = 2
        val counts = LongArray(16)
        counts[2] = 10
        return DfaPlan(
            iso2 = "AA",
            lineType = LineType.MOBILE,
            planVersion = 1,
            start = 0,
            accept = booleanArrayOf(false, false, true),
            transitions = transitions,
            declaredLengthCounts = counts,
        )
    }

    /**
     * Plan B: exactly two digits, the first digit is 1 and the second is 0..5.
     */
    private fun planB(): DfaPlan {
        val transitions = Array(3) { IntArray(10) { -1 } }
        transitions[0][1] = 1
        for (digit in 0..5) transitions[1][digit] = 2
        val counts = LongArray(16)
        counts[2] = 6
        return DfaPlan(
            iso2 = "AA",
            lineType = LineType.FIXED,
            planVersion = 2,
            start = 0,
            accept = booleanArrayOf(false, false, true),
            transitions = transitions,
            declaredLengthCounts = counts,
        )
    }

    @Test
    fun countsPathsAndLowerBoundsExactly() {
        val plan = planA()
        assertEquals(10L, plan.total)
        assertEquals(10L, plan.countExact(0, 2))
        assertEquals(10L, plan.countFrom("1"))
        assertEquals(8L, plan.countFrom("12"))
        assertEquals(10L, plan.countFrom(""))
        assertEquals(0L, plan.countFrom("2"))
        assertEquals(0L, plan.countFrom("190"))
        assertTrue(plan.isFeasiblePrefix("1"))
        assertTrue(plan.isFeasiblePrefix("19"))
        assertFalse(plan.isFeasiblePrefix("2"))
        assertFalse(plan.isFeasiblePrefix("190"))
    }

    @Test
    fun enumeratesInOrderAndHonoursTheLowerBound() {
        val values = ArrayList<String>()
        planA().collect("", 20, values)
        assertEquals((10..19).map { it.toString() }, values)

        val from15 = ArrayList<String>()
        planA().collect("15", 3, from15)
        assertEquals(listOf("15", "16", "17"), from15)

        val shortPrefix = ArrayList<String>()
        planA().collect("1", 20, shortPrefix)
        assertEquals((10..19).map { it.toString() }, shortPrefix)
    }

    @Test
    fun intersectionDeduplicatesOverlappingPlans() {
        val intersection = DfaPlan.intersect(planA(), planB(), "AA")
        assertEquals(6L, intersection.total)
        assertEquals(6L, intersection.countFrom(""))
        assertEquals(2L, intersection.countFrom("14"))
        val values = ArrayList<String>()
        intersection.collect("", 20, values)
        assertEquals(listOf("10", "11", "12", "13", "14", "15"), values)
    }

    @Test
    fun incrementDecimalCarriesAndGrows() {
        assertEquals("1", incrementDecimal(""))
        assertEquals("124", incrementDecimal("123"))
        assertEquals("130", incrementDecimal("129"))
        assertEquals("1000", incrementDecimal("999"))
    }

    @Test
    fun planSupportsAnEmptyIntersection() {
        val transitions = Array(2) { IntArray(10) { -1 } }
        transitions[0][9] = 1
        val counts = LongArray(16)
        counts[2] = 1
        val other = DfaPlan(
            iso2 = "AA",
            lineType = LineType.FIXED,
            planVersion = 1,
            start = 0,
            accept = booleanArrayOf(false, true),
            transitions = transitions,
            declaredLengthCounts = counts,
        )
        val intersection = DfaPlan.intersect(planA(), other, "AA")
        assertEquals(0L, intersection.total)
        val values = ArrayList<String>()
        intersection.collect("", 10, values)
        assertTrue(values.isEmpty())
    }
}
