package com.numx.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationOptionsTest {

    @Test
    fun fileEstimatesMatchFullCountrySizes() {
        val full = SyrianNumbers.totalAllocatedNumbers.toLong()
        assertEquals(4_160_000_000L, ExportFormat.CSV.estimatedBytes(full))
        assertEquals(8_160_000_000L, ExportFormat.VCARD.estimatedBytes(full))
        assertTrue(formatFileSize(ExportFormat.CSV.estimatedBytes(full)).contains("GB"))
    }

    @Test
    fun appendFunctionsMatchSingleRowHelpers() {
        for (number in listOf("0910000000", "0999999999")) {
            val csv = StringBuilder().also { ContactFormats.appendCsvRow(it, number) }.toString()
            val vcf = StringBuilder().also { ContactFormats.appendVCard(it, number) }.toString()
            assertEquals(ContactFormats.csvRow(number), csv)
            assertEquals(ContactFormats.vCard(number), vcf)
        }
    }

    @Test
    fun formatsExposeTheirSupportedDeviceAndMimeType() {
        assertEquals("text/csv", ExportFormat.CSV.mimeType)
        assertEquals("text/x-vcard", ExportFormat.VCARD.mimeType)
        assertTrue(ExportFormat.CSV.suitableDevice.contains("حاسوب"))
        assertTrue(ExportFormat.VCARD.suitableDevice.contains("هاتف"))
    }
}
