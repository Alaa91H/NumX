package com.numx.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyrianNumbersTest {

    @Test
    fun acceptsOnlyAllocatedMobilePrefixes() {
        val valid = listOf(
            "0911234567", "0921234567", "0931234567", "0941234567",
            "0951234567", "0961234567", "0981234567", "0991234567",
        )
        for (number in valid) {
            assertTrue(number, SyrianNumbers.isValid(number))
        }
        val invalid = listOf(
            "0901234567", "0971234567", "0900000000", "0970000000",
            "0111234567", "093123456", "09312345678", "09312345a7",
        )
        for (number in invalid) {
            assertFalse(number, SyrianNumbers.isValid(number))
        }
    }

    @Test
    fun normalizesInternationalAndShortForms() {
        assertEquals("0931234567", SyrianNumbers.normalize("+963 931 234 567"))
        assertEquals("0931234567", SyrianNumbers.normalize("00963931234567"))
        assertEquals("0931234567", SyrianNumbers.normalize("963931234567"))
        assertEquals("0931234567", SyrianNumbers.normalize("9630931234567"))
        assertEquals("0931234567", SyrianNumbers.normalize("+963 (0) 931 234 567"))
        assertEquals("0931234567", SyrianNumbers.normalize("931234567"))
        assertEquals("0931234567", SyrianNumbers.normalize("093-123-4567"))
        assertEquals("0931234567", SyrianNumbers.normalize("٠٩٣١٢٣٤٥٦٧"))
        assertNull(SyrianNumbers.normalize("0901234567"))
        assertNull(SyrianNumbers.normalize(""))
    }

    @Test
    fun nextAfterSkipsDisallowedPrefixesAndStopsAtTheEnd() {
        assertEquals("0910000001", SyrianNumbers.nextAfter("0910000000"))
        assertEquals("0940000000", SyrianNumbers.nextAfter("0939999999"))
        assertEquals("0980000000", SyrianNumbers.nextAfter("0969999999"))
        assertNull(SyrianNumbers.nextAfter("0999999999"))
        assertNull(SyrianNumbers.nextAfter("0900000000"))
    }

    @Test
    fun cursorCrossesToNextAllowedPrefix() {
        val cursor = SyrianNumberCursor("0939999998")
        val batch = cursor.nextBatch(5)!!
        assertEquals(
            listOf("0939999998", "0939999999", "0940000000", "0940000001", "0940000002"),
            batch,
        )
    }

    @Test
    fun cursorSkipsUnallocated97Prefix() {
        val cursor = SyrianNumberCursor("0969999999")
        val batch = cursor.nextBatch(3)!!
        assertEquals(
            listOf("0969999999", "0980000000", "0980000001"),
            batch,
        )
    }

    @Test
    fun generatesOneMillionValidNumbersInOrder() {
        val cursor = SyrianNumberCursor("0930000000")
        var produced = 0
        var previous: String? = null
        while (produced < 1_000_000) {
            val batch = cursor.nextBatch(500) ?: break
            for (number in batch) {
                assertTrue(number, SyrianNumbers.isValid(number))
                if (previous != null) assertTrue("$previous < $number", previous < number)
                previous = number
                produced++
            }
        }
        assertEquals(1_000_000, produced)
    }

    @Test
    fun cursorProducesRequestedCountAcrossPrefixes() {
        val cursor = SyrianNumberCursor("0999999998")
        val batch = cursor.nextBatch(10)!!
        assertEquals(2, batch.size)
        assertEquals("0999999998", batch[0])
        assertEquals("0999999999", batch[1])
        assertNull(cursor.nextBatch(1))
    }

    @Test
    fun fullCountryRangeIsEightNetworksTimesTenMillion() {
        assertEquals(80_000_000, SyrianNumbers.totalAllocatedNumbers)
        assertEquals(80_000_000, SyrianNumbers.availableCount("0910000000"))
        assertEquals(60_000_000, SyrianNumbers.availableCount("0930000000"))
        assertEquals(20_000_001, SyrianNumbers.availableCount("0969999999"))
        assertEquals(1, SyrianNumbers.availableCount("0999999999"))
        assertNull(SyrianNumbers.availableCount("0900000000"))
    }

    @Test
    fun fullCountryCursorStartsAtFirstAllocatedNumber() {
        val cursor = SyrianNumberCursor(SyrianNumbers.FIRST_ALLOCATED_NUMBER)
        assertEquals(
            listOf("0910000000", "0910000001", "0910000002"),
            cursor.nextBatch(3),
        )
    }
}