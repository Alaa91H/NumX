package com.numx.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactFormatsTest {

    private val n2Note = ContactIdentity.noteN2(
        e164 = "+963944567890",
        iso2 = "SY",
        lineType = ActualLineType.MOBILE,
        planVersion = 1,
        batch = "EXPORT",
    )

    private fun record(
        name: String = "N-963944567890",
        phoneValue: String = "+963944567890",
        phoneType: String = "Mobile",
        vCardType: String = "CELL",
        iso2: String = "SY",
        callingCode: String = "963",
        e164: String = "+963944567890",
        lineType: String = "خليوي",
        note: String = n2Note,
        label: String? = null,
    ) = ExportRecord(
        name = name,
        phoneValue = phoneValue,
        phoneType = phoneType,
        vCardType = vCardType,
        iso2 = iso2,
        callingCode = callingCode,
        e164 = e164,
        lineType = lineType,
        note = note,
        vCardLabel = label,
    )

    @Test
    fun csvHasTenColumnsAndNeutralN2Name() {
        assertEquals(
            "Name,Given Name,Family Name,Phone 1 - Type,Phone 1 - Value," +
                "Country ISO,Calling Code,E.164,Line Type,Note\n",
            ContactFormats.CSV_HEADER,
        )
        assertEquals(
            "\"N-963944567890\",\"N-963944567890\",,\"Mobile\",\"+963944567890\"," +
                "\"SY\",\"963\",\"+963944567890\",\"خليوي\",\"$n2Note\"\n",
            ContactFormats.csvRow(record()),
        )
    }

    @Test
    fun csvEscapesQuotesAndKeepsAllMetadataColumns() {
        assertEquals("\"a\"\"b\"", ContactFormats.csv("a\"b"))
        val row = ContactFormats.csvRow(
            record(
                iso2 = "US",
                callingCode = "1",
                e164 = "+12015550123",
                phoneValue = "+12015550123",
                name = "N-12015550123",
                note = "REF=N2;test",
            )
        )
        assertTrue(row.contains("\"US\",\"1\",\"+12015550123\""))
        assertTrue(row.contains("\"REF=N2;test\""))
    }

    @Test
    fun vCardMobileUsesCellAndCarriesTheOwnershipNote() {
        val card = ContactFormats.vCard(record())
        assertTrue(card.startsWith("BEGIN:VCARD\r\nVERSION:3.0\r\n"))
        assertTrue(card.contains("FN:N-963944567890\r\n"))
        assertTrue(card.contains("TEL;TYPE=CELL:+963944567890\r\n"))
        assertTrue(
            card.lineSequence().any { line ->
                line.replace("\\;", ";").replace("\\,", ",").replace("\\\\", "\\") ==
                    "NOTE:$n2Note"
            }
        )
        assertTrue(card.endsWith("END:VCARD\r\n"))
        assertFalse(card.contains("PRODID"))
    }

    @Test
    fun vCardFixedAndAmbiguousUseVoiceAndKeepALabel() {
        val ambiguous = "أرضي أو خليوي (غير متميز)"
        val card = ContactFormats.vCard(
            record(
                phoneType = "Other",
                vCardType = "VOICE",
                lineType = ambiguous,
                label = ambiguous,
            )
        )
        assertTrue(card.contains("TEL;TYPE=VOICE;X-ABLabel=$ambiguous:+963944567890\r\n"))
    }

    @Test
    fun vCardEmergencyIsMarkedOtherWithEmergencyLabel() {
        val card = ContactFormats.vCard(
            record(
                name = "الشرطة سوريا — 112",
                phoneValue = "112",
                phoneType = "Other",
                vCardType = "OTHER",
                e164 = "",
                lineType = "Emergency",
                note = ContactIdentity.noteE1("SY", "112", "POLICE", "الشرطة"),
                label = "طوارئ",
            )
        )
        assertTrue(card.contains("FN:الشرطة سوريا — 112\r\n"))
        assertTrue(card.contains("TEL;TYPE=OTHER;X-ABLabel=طوارئ:112\r\n"))
        assertTrue(
            card.lineSequence().any { line ->
                line.replace("\\;", ";").replace("\\,", ",").replace("\\\\", "\\")
                    .startsWith("NOTE:REF=E1;ISO=SY;N=112;S=POLICE;L=الشرطة;C=")
            }
        )
    }

    @Test
    fun vCardEscapesTextValues() {
        val card = ContactFormats.vCard(
            record(name = "a,b;c\\d", note = "line1\nline2")
        )
        assertTrue(card.contains("FN:a\\,b\\;c\\\\d\r\n"))
        assertTrue(card.contains("NOTE:line1\\nline2\r\n"))
    }
}
