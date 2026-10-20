package com.numx.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContactIdentityTest {

    @Test
    fun displayNameUsesNeutralPrefix() {
        assertEquals("N-0931234567", ContactIdentity.displayName("0931234567"))
    }

    @Test
    fun noteRoundTripsAndRejectsForeignValues() {
        val note = ContactIdentity.note("0931234567")
        assertEquals("REF=N1;N=0931234567", note)
        assertEquals("0931234567", ContactIdentity.numberFromNote(note))
        assertNull(ContactIdentity.numberFromNote(null))
        assertNull(ContactIdentity.numberFromNote("some other note"))
        assertNull(ContactIdentity.numberFromNote("REF=N1;N=0901234567"))
    }
}
