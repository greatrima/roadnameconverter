package kr.co.addresslens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressTextParserTest {
    @Test
    fun extractsSimpleParcelAddress() {
        assertEquals("초지동 716-7", AddressTextParser.extract("초지동 716-7"))
    }

    @Test
    fun extractsAddressFromNoisyMultilineOcr() {
        val ocr = "대한민국\n지번 주소: 경기도 안산시 단원구 초지동 716－7\n우편번호"
        assertEquals(
            "경기도 안산시 단원구 초지동 716-7",
            AddressTextParser.extract(ocr)
        )
    }

    @Test
    fun joinsAddressSplitAcrossLines() {
        assertEquals("초지동 716-7", AddressTextParser.extract("초지동\n716-7"))
    }

    @Test
    fun joinsParcelNumberThatStartsOnNextLine() {
        assertEquals("응암동 118-36", AddressTextParser.extract("응암동\n118-36"))
        assertEquals("수정동 1011-375", AddressTextParser.extract("수정동 1011\n-375"))
        assertEquals("좌천동 849-25", AddressTextParser.extract("좌천동 849\n-25"))
    }

    @Test
    fun joinsDongAndNumberSeenInDifferentOcrFrames() {
        val candidate = AddressTextParser.extractFromOcrSamples(
            listOf("응암동", "118-36")
        )
        assertEquals("응암동 118-36", candidate?.text)
        assertEquals(AddressKind.PARCEL, candidate?.kind)
    }

    @Test
    fun appendsSubNumberSeenInDifferentOcrFrame() {
        assertEquals(
            "좌천동 849-25",
            AddressTextParser.extractFromOcrSamples(listOf("좌천동 849", "-25"))?.text
        )
    }

    @Test
    fun joinsRoadNameSplitAcrossAdjacentOcrSamples() {
        assertEquals(
            "광덕동로 25",
            AddressTextParser.extractFromOcrSamples(listOf("광덕", "동로 25"))?.text
        )
        assertEquals(
            "경기도 안산시 단원구 광덕동로 25",
            AddressTextParser.extractFromOcrSamples(
                listOf("경기도 안산시 단원구 광덕", "동로 25")
            )?.text
        )
    }

    @Test
    fun doesNotJoinRoadFragmentsAcrossNonAdjacentOcrSamples() {
        val candidates = AddressTextParser.extractCandidatesFromOcrSamples(
            listOf("광덕", "수신인 홍길동", "동로 25")
        )
        assertTrue(candidates.none { it.text == "광덕동로 25" })
    }

    @Test
    fun returnsAllDistinctAddressesWhenOcrIsAmbiguous() {
        val candidates = AddressTextParser.extractCandidates(
            "응암동 118-36\n원곡동 814"
        ).map { it.text }.toSet()

        assertEquals(setOf("응암동 118-36", "원곡동 814"), candidates)
    }

    @Test
    fun removesIncompletePrefixWhenFullTwoLineAddressExists() {
        val candidates = AddressTextParser.extractCandidatesFromOcrSamples(
            listOf("좌천동 849", "-25")
        ).map { it.text }

        assertEquals(listOf("좌천동 849-25"), candidates)
        assertTrue("좌천동 849" !in candidates)
    }

    @Test
    fun rejectsTextWithoutParcelNumber() {
        assertNull(AddressTextParser.extract("안녕하세요 초지동입니다"))
    }

    @Test
    fun extractsRoadAddress() {
        assertEquals("단원구 신촌1길 4-1", AddressTextParser.extract("단원구 신촌1길 4-1"))
        assertEquals(AddressKind.ROAD, AddressTextParser.extractCandidate("단원구 신촌1길 4-1")?.kind)
    }

    @Test
    fun classifiesParcelAddress() {
        assertEquals(AddressKind.PARCEL, AddressTextParser.classify("초지동 716-7"))
    }

    @Test
    fun excludesFloorUnitAndParentheticalDongFromRoadAddress() {
        assertEquals(
            "경기도 안산시 단원구 신촌1길 4-1",
            AddressTextParser.extract("경기도 안산시 단원구 신촌1길 4-1 1층 2호 (초지동)")
        )
    }

    @Test
    fun restoresRoadNameSplitAcrossLinesAndExcludesDetailAddress() {
        val ocr = """경기도 안산시 단원구 광덕
            |동로 25(고잔동. 안산레이
            |크타운 푸르지오) 102동 2
            |901호""".trimMargin()
        assertEquals(
            "경기도 안산시 단원구 광덕동로 25",
            AddressTextParser.extract(ocr)
        )
        assertEquals(
            "경기도 안산시 단원구 광덕동로 25(고잔동. 안산레이크타운 푸르지오) 102동 2901호",
            AddressTextParser.restoreStructuredText(ocr).replace("\n", " ")
        )
    }

    @Test
    fun preservesNumberedRoadAndAllBaseNumbers() {
        assertEquals("샘골로12길 123-4", AddressTextParser.extract("샘골로12길 123-4, 101동 1203호"))
        assertEquals("본오동 산 123-4", AddressTextParser.extract("본오동 산123 - 4번지 2층"))
    }

    @Test
    fun stripsSeveralDetailAddressForms() {
        assertEquals("광덕동로 25", AddressTextParser.extract("광덕동로 25, 101동 1203호"))
        assertEquals("광덕동로 25", AddressTextParser.extract("광덕동로 25, 2층"))
        assertEquals("광덕동로 25", AddressTextParser.extract("광덕동로 25, 지하 1호"))
    }

    @Test
    fun doesNotJoinSeparateVisualBlocks() {
        assertNull(AddressTextParser.extract("응암동\n\n118-36"))
    }

    @Test
    fun ignoresPhoneAndPostalCodeWithoutAddressStructure() {
        assertNull(AddressTextParser.extract("010-1234-5678\n12345\n홍길동"))
    }

}
