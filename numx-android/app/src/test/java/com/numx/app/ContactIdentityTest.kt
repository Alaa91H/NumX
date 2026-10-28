package com.numx.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactIdentityTest {

    @Test
    fun n2NameIsNeutralAndStrictlyE164Digits() {
        assertEquals("N-963944567890", ContactIdentity.displayNameE164("+963944567890"))
        assertTrue(ContactIdentity.isValidN2Name("N-963944567890"))
        assertTrue(ContactIdentity.isValidN2Name("N-12015550123"))
        assertTrue(ContactIdentity.isValidN2Name("N-4930000"))
        assertFalse(ContactIdentity.isValidN2Name("N-+963944567890"))
        assertFalse(ContactIdentity.isValidN2Name("N-Syriatel-944"))
        assertFalse(ContactIdentity.isValidN2Name("N-0123"))
        assertFalse(ContactIdentity.isValidN2Name("N-12345"))
    }

    @Test
    fun shortButValidE164NumbersAreAccepted() {
        val note = ContactIdentity.noteN2(
            e164 = "+4930000",
            iso2 = "DE",
            lineType = ActualLineType.FIXED,
            planVersion = 1,
            batch = "S1",
        )
        assertEquals("+4930000", (ContactIdentity.parseNote(note) as OwnershipTag.N2).e164)
    }

    @Test
    fun n2NoteRoundTripsWithAllMetadata() {
        val note = ContactIdentity.noteN2(
            e164 = "+963944567890",
            iso2 = "SY",
            lineType = ActualLineType.MOBILE,
            planVersion = 3,
            batch = "S42",
        )
        assertTrue(note.startsWith("REF=N2;E=+963944567890;ISO=SY;T=MOBILE;P=3;G=S42;C="))
        val parsed = ContactIdentity.parseNote(note) as OwnershipTag.N2
        assertEquals("+963944567890", parsed.e164)
        assertEquals("SY", parsed.iso2)
        assertEquals(ActualLineType.MOBILE, parsed.lineType)
        assertEquals(3, parsed.planVersion)
        assertEquals("S42", parsed.batch)
        assertEquals("+963944567890", ContactIdentity.e164FromNote(note))
    }

    @Test
    fun n2NoteRejectsTamperedMetadataAndBadCrc() {
        val note = ContactIdentity.noteN2(
            e164 = "+963944567890",
            iso2 = "SY",
            lineType = ActualLineType.FIXED,
            planVersion = 1,
            batch = "S1",
        )
        assertNull(ContactIdentity.parseNote(note.replace("ISO=SY", "ISO=US")))
        assertNull(ContactIdentity.parseNote(note.replace("T=FIXED", "T=MOBILE")))
        assertNull(ContactIdentity.parseNote(note.dropLast(1) + "0"))
        assertNull(
            ContactIdentity.parseNote(
                "REF=N2;E=+963944567890;ISO=SY;T=MOBILE;P=1;G=S1;C=deadbeef"
            )
        )
    }

    @Test
    fun legacyN1NoteIsPreservedButRejectsForbiddenPrefixes() {
        val note = ContactIdentity.noteN1("0931234567")
        assertEquals("REF=N1;N=0931234567", note)
        assertEquals("0931234567", ContactIdentity.numberFromNote(note))
        assertEquals("+963931234567", ContactIdentity.e164FromNote(note))
        assertNull(ContactIdentity.numberFromNote("REF=N1;N=0901234567"))
        assertNull(ContactIdentity.numberFromNote("REF=N1;N=0971234567"))
        assertNull(ContactIdentity.numberFromNote("REF=N2;E=+963931234567"))
        assertNull(ContactIdentity.numberFromNote("some other note"))
        assertNull(ContactIdentity.numberFromNote(null))
    }

    @Test
    fun e1NoteRoundTripsAndKeepsOnlyThePhoneShortCode() {
        val note = ContactIdentity.noteE1(
            iso2 = "SY",
            number = "112",
            category = "POLICE",
            label = "الشرطة",
        )
        assertTrue(note.startsWith("REF=E1;ISO=SY;N=112;S=POLICE;L=الشرطة;C="))
        val parsed = ContactIdentity.parseNote(note) as OwnershipTag.E1
        assertEquals("SY", parsed.iso2)
        assertEquals("112", parsed.number)
        assertEquals("POLICE", parsed.category)
        assertEquals("الشرطة", parsed.label)
        assertEquals("112", ContactIdentity.e164FromNote(note))
    }

    @Test
    fun e1NoteSanitizesSemicolonsAndRejectsTampering() {
        val note = ContactIdentity.noteE1("SY", "112", "POLICE", "شرطة; طوارئ")
        assertTrue(note.contains("L=شرطة  طوارئ"))
        assertNull(ContactIdentity.parseNote(note.replace("N=112", "N=911")))
    }
}
