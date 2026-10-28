package com.numx.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NeutralDefaultsTest {

    @Test
    fun noCountryIsSelectedAtStartup() {
        val state = UiState()
        assertEquals("", state.selectedIso)
        assertEquals("", state.emergencyIso)
        assertEquals("", state.lookupIso)
        assertNull(state.selectedCountry)
        assertNull(state.emergencyCountry)
        assertNull(state.lookupCountry)
    }

    @Test
    fun startupDoesNotPrefillASyrianNumber() {
        val state = UiState()
        assertEquals("", state.startInput)
        assertNull(state.startNsn)
        assertEquals(false, state.startValid)
        assertNull(state.availableFromStart)
    }

    @Test
    fun startupHasNoTransientMessageOrOperation() {
        val state = UiState()
        assertEquals("", state.message)
        assertEquals(false, state.operation.isRunning)
        assertEquals(0, state.emergencyEntries.size)
    }
}
