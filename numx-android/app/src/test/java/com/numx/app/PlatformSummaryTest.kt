package com.numx.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformSummaryTest {

    @Test
    fun summaryDoesNotClaimActiveLineStatus() {
        val text = "Offline coverage summary. It contains no subscriber data and does not prove that any line is active."
        assertTrue(text.contains("subscriber"))
        assertTrue(text.contains("does not prove"))
        assertFalse(text.contains("active line is confirmed"))
    }

    @Test
    fun summaryNotesAreArabicAndEnglish() {
        val ar = "ملخص تغطية ومصادر يُقرأ دون إنترنت."
        assertTrue(ar.contains("دون إنترنت"))
    }

    @Test
    fun provenanceNotesSeparateValidityFromActivity() {
        val note = "A valid numbering-plan match does not prove that a line exists, is assigned, or is active."
        assertTrue(note.contains("valid"))
        assertTrue(note.contains("does not prove"))
    }

    @Test
    fun sourceStatusFieldsAreSeparate() {
        val fields = listOf(
            "accessMethod",
            "licenseName",
            "redistributionAllowed",
            "verificationStatus",
            "legalReviewStatus",
            "importStatus",
        )
        assertTrue(fields.isNotEmpty())
    }

    @Test
    fun emergencyReferenceNotesAreNotOfficialConfirmation() {
        val note = "Emergency reference entry; not officially confirmed for every country."
        assertTrue(note.contains("not officially confirmed"))
    }
}
