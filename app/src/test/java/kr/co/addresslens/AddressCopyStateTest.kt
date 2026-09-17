package kr.co.addresslens

import org.junit.Assert.*
import org.junit.Test

class AddressCopyStateTest {
    @Test fun offlineOrUnverifiedInputCanBeCopiedWithoutParsing() {
        val state = AddressCopyState()
        state.updateInput("  공사장 임시 주소\n출입구 옆  ")
        assertEquals("공사장 임시 주소\n출입구 옆", state.inputAddress)
        assertNull(state.convertedAddress)
    }

    @Test fun editingOrReplacingInputInvalidatesPreviousResultEvenWhenEditedBack() {
        val state = AddressCopyState()
        state.updateInput("본오동 123-4")
        state.convertedAddress = "도로명 12"
        state.updateInput("본오동 123-5")
        assertNull(state.convertedAddress)
        state.updateInput("본오동 123-4")
        assertNull(state.convertedAddress)
    }

    @Test fun emptyInputAndClearedResultsNeverCopyStatusText() {
        val state = AddressCopyState()
        state.updateInput("원문")
        state.convertedAddress = "도로명 12"
        state.convertedAddress = null // new request, failure, offline, or reset
        assertEquals("원문", state.inputAddress)
        assertNull(state.convertedAddress)
        state.updateInput(" \n ")
        state.convertedAddress = "  "
        assertNull(state.inputAddress)
        assertNull(state.convertedAddress)
    }
}
