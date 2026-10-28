package com.numx.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmergencyCatalogTest {

    private fun entry(
        number: String = "112",
        category: String = "POLICE",
        labelAr: String = "الشرطة",
        labelEn: String = "Police",
    ) = EmergencyEntry(
        iso2 = "SY",
        number = number,
        category = category,
        labelAr = labelAr,
        labelEn = labelEn,
        countryAr = "سوريا",
        countryEn = "Syria",
        verification = "REFERENCE",
        source = "test",
        sourceUrl = "https://example.invalid",
        checkedAt = null,
        noteAr = "",
    )

    @Test
    fun emergencyCategoriesAreSeparatedFromServiceCategories() {
        assertTrue(entry(category = "POLICE").isEmergency)
        assertTrue(entry(category = "AMBULANCE").isEmergency)
        assertTrue(entry(category = "FIRE").isEmergency)
        assertTrue(entry(category = "MULTI").isEmergency)
        assertTrue(entry(category = "EMERGENCY").isEmergency)

        assertFalse(entry(category = "TOLL_FREE").isEmergency)
        assertFalse(entry(category = "STANDARD_RATE").isEmergency)
        assertFalse(entry(category = "CARRIER").isEmergency)
        assertFalse(entry(category = "SHORT_CODE").isEmergency)
    }

    @Test
    fun everyCategoryHasArabicLabelAndOrder() {
        val categories = setOf(
            "GENERAL", "POLICE", "AMBULANCE", "FIRE", "DISPATCH", "TRAFFIC",
            "FIRE_AMBULANCE", "GENDARMERIE", "MARINE", "MOUNTAIN", "MULTI",
            "EMERGENCY", "TOLL_FREE", "STANDARD_RATE", "CARRIER", "SHORT_CODE",
        )
        assertEquals(categories, EmergencyEntry.CATEGORY_ORDER.toSet())
        assertTrue(EmergencyEntry.EMERGENCY_CATEGORIES.containsAll(
            setOf("GENERAL", "POLICE", "AMBULANCE", "FIRE", "MULTI", "EMERGENCY")
        ))
        categories.forEach { category ->
            val label = entry(category = category).categoryLabelAr
            assertTrue("missing Arabic label for $category", label.isNotBlank())
        }
    }

    @Test
    fun emergencyCategoriesSortBeforeServiceCategories() {
        val order = EmergencyEntry.CATEGORY_ORDER
        val lastEmergency = order.indexOf("EMERGENCY")
        val firstService = order.indexOf("TOLL_FREE")
        assertTrue(lastEmergency < firstService)
        assertTrue(order.indexOf("POLICE") < order.indexOf("TOLL_FREE"))
    }

    @Test
    fun displayNameUsesIndefiniteArabicLabel() {
        assertEquals(
            "شرطة سوريا — 112",
            entry(category = "POLICE", labelAr = "الشرطة").displayNameAr,
        )
        assertEquals(
            "طوارئ سوريا — 911",
            entry(number = "911", category = "GENERAL", labelAr = "الطوارئ")
                .displayNameAr,
        )
        assertEquals(
            "خفر سواحل سوريا — 155",
            entry(number = "155", category = "MARINE", labelAr = "خفر السواحل")
                .displayNameAr,
        )
        assertEquals(
            "إطفاء وإسعاف سوريا — 113",
            entry(
                number = "113",
                category = "FIRE_AMBULANCE",
                labelAr = "الإطفاء والإسعاف",
            ).displayNameAr,
        )
    }
}
