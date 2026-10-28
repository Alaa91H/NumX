package com.numx.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NationalInputTest {

    private fun country(
        iso2: String,
        callingCode: String,
        nationalPrefix: String?,
    ) = Country(
        iso2 = iso2,
        nameAr = "دولة",
        nameEn = "Country",
        callingCode = callingCode,
        nationalPrefix = nationalPrefix,
        flag = "",
        flagFallback = false,
        support = SupportTier.FULL,
        hasMobile = true,
        hasFixed = true,
        mobileLengths = listOf(9),
        fixedLengths = listOf(9),
        mobileCount = 1,
        fixedCount = 1,
        bothCount = 2,
        overlapCount = 0,
        exampleMobile = null,
        exampleFixed = null,
        planVersion = 1,
        mainCountry = null,
        sharedWith = emptyList(),
    )

    @Test
    fun syrianInputAcceptsLocalNsnE164And00Forms() {
        val syria = country("SY", "963", "0")
        val mobile: (String) -> Boolean = { it.length == 9 && it.startsWith("94") }
        for (input in listOf(
            "0944567890",
            "944567890",
            "+963 944 567 890",
            "00963944567890",
            "963944567890",
            "٠٩٤٤٥٦٧٨٩٠",
        )) {
            assertEquals(input, "944567890", normalizeNationalInput(syria, LineType.MOBILE, input, mobile))
        }
        assertNull(normalizeNationalInput(syria, LineType.MOBILE, "0901234567", mobile))
        assertNull(normalizeNationalInput(syria, LineType.MOBILE, "", mobile))
        assertEquals("0944567890", syria.toLocalDisplay("944567890"))
    }

    @Test
    fun northAmericanInputStripsCountryCodeAndNationalPrefix() {
        val us = country("US", "1", "1")
        val mobile: (String) -> Boolean = { it.length == 10 && it.startsWith("2") }
        for (input in listOf(
            "2015550123",
            "1 (201) 555-0123",
            "+1 201 555 0123",
            "0012015550123",
        )) {
            assertEquals(input, "2015550123", normalizeNationalInput(us, LineType.MOBILE, input, mobile))
        }
        assertEquals("2015550123", us.toLocalDisplay("2015550123"))
    }

    @Test
    fun italianLeadingZeroIsPartOfTheNsn() {
        val italy = country("IT", "39", null)
        val fixed: (String) -> Boolean = { it.length == 10 && it.startsWith("06") }
        assertEquals(
            "0612345678",
            normalizeNationalInput(italy, LineType.FIXED, "06 1234 5678", fixed),
        )
        assertEquals(
            "0612345678",
            normalizeNationalInput(italy, LineType.FIXED, "+39 06 1234 5678", fixed),
        )
        assertEquals("0612345678", italy.toLocalDisplay("0612345678"))
    }

    @Test
    fun countrySupportAndCountsAreConsistent() {
        val both = country("AA", "999", "0")
        assertTrue(both.supports(LineType.MOBILE))
        assertTrue(both.supports(LineType.FIXED))
        assertTrue(both.supports(LineType.BOTH))
        assertEquals(1L, both.countFor(LineType.MOBILE))
        assertEquals(2L, both.countFor(LineType.BOTH))
    }
}
