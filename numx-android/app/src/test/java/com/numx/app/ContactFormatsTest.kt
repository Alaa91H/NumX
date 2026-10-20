package com.numx.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactFormatsTest {

    @Test
    fun csvHasExpectedColumnsAndNeutralName() {
        assertEquals(
            "Name,Given Name,Family Name,Phone 1 - Type,Phone 1 - Value\n",
            ContactFormats.CSV_HEADER,
        )
        assertEquals(
            "\"N-0931234567\",\"N-0931234567\",,Mobile,\"0931234567\"\n",
            ContactFormats.csvRow("0931234567"),
        )
    }

    @Test
    fun csvEscapesQuotes() {
        assertEquals("\"a\"\"b\"", ContactFormats.csv("a\"b"))
    }

    @Test
    fun vCardContainsNeutralNameAndMobileTelephone() {
        val card = ContactFormats.vCard("0931234567")
        assertTrue(card.startsWith("BEGIN:VCARD\r\nVERSION:3.0\r\n"))
        assertTrue(card.contains("FN:N-0931234567\r\n"))
        assertTrue(card.contains("TEL;TYPE=CELL:0931234567\r\n"))
        assertTrue(card.endsWith("END:VCARD\r\n"))
        assertFalse(card.contains("PRODID"))
    }
}
