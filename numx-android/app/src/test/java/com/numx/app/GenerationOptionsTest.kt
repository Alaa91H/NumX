package com.numx.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationOptionsTest {

    @Test
    fun fileEstimatesMatchPerNumberBudget() {
        assertEquals(180_000_000L, ExportFormat.CSV.estimatedBytes(1_000_000))
        assertEquals(220_000_000L, ExportFormat.VCARD.estimatedBytes(1_000_000))
        assertEquals(0L, ExportFormat.CSV.estimatedBytes(-10))
        assertTrue(formatFileSize(ExportFormat.CSV.estimatedBytes(2_871_000_000L)).contains("GB"))
        assertTrue(formatFileSize(2_000_000_000_000L).contains("TB"))
        assertTrue(formatFileSize(2_000_000_000_000_000L).contains("PB"))
    }

    @Test
    fun appendFunctionsMatchSingleRecordHelpers() {
        val records = listOf(
            ExportRecord(
                name = "N-963944567890",
                phoneValue = "+963944567890",
                phoneType = "Mobile",
                vCardType = "CELL",
                iso2 = "SY",
                callingCode = "963",
                e164 = "+963944567890",
                lineType = "خليوي",
                note = ContactIdentity.noteN2(
                    "+963944567890",
                    "SY",
                    ActualLineType.MOBILE,
                    1,
                    "TEST",
                ),
            ),
            ExportRecord(
                name = "N-12015550123",
                phoneValue = "+12015550123",
                phoneType = "Other",
                vCardType = "VOICE",
                iso2 = "US",
                callingCode = "1",
                e164 = "+12015550123",
                lineType = "أرضي أو خليوي (غير متميز)",
                note = "REF=N2;test",
                vCardLabel = "أرضي أو خليوي (غير متميز)",
            ),
        )
        for (record in records) {
            val csv = StringBuilder().also { ContactFormats.appendCsvRow(it, record) }.toString()
            val vcf = StringBuilder().also { ContactFormats.appendVCard(it, record) }.toString()
            assertEquals(ContactFormats.csvRow(record), csv)
            assertEquals(ContactFormats.vCard(record), vcf)
        }
    }

    @Test
    fun formatsExposeTheirSupportedDeviceAndMimeType() {
        assertEquals("text/csv", ExportFormat.CSV.mimeType)
        assertEquals("text/x-vcard", ExportFormat.VCARD.mimeType)
        assertTrue(ExportFormat.CSV.suitableDevice.contains("حاسوب"))
        assertTrue(ExportFormat.VCARD.suitableDevice.contains("هاتف"))
        assertEquals(1_000_000L, ExportManager.splitRows)
        assertTrue(ExportManager.requiresSplit(1_000_001))
    }
}
