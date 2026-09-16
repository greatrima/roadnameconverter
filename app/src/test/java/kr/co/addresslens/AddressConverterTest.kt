package kr.co.addresslens

import org.junit.Assert.assertEquals
import org.junit.Test

class AddressConverterTest {
    @Test
    fun qualifiesShortVworldRoadAddress() {
        val converter = AddressConverter("")
        val converted = converter.qualifyAddress(
            "신촌1길 4-1",
            "경기도 안산시 단원구 초지동 716-7"
        )
        converter.close()

        assertEquals("경기도 안산시 단원구 신촌1길 4-1", converted)
    }

    @Test
    fun qualifiesShortVworldParcelAddress() {
        val converter = AddressConverter("")
        val converted = converter.qualifyAddress(
            "초지동 716-7",
            "경기도 안산시 단원구 신촌1길 4-1 (초지동)"
        )
        converter.close()

        assertEquals("경기도 안산시 단원구 초지동 716-7", converted)
    }
}
