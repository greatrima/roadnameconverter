package kr.co.addresslens

import org.junit.Assert.*
import org.junit.Test

class RawSearchFlowTest {
    private val region = RegionSelection("경기도", "안산시 상록구")
    private fun raw(text: String, selected: RegionSelection = region) = RawAddressCandidates.fromBlocks(listOf(text), selected)
    private fun first(text: String, selected: RegionSelection = region) = raw(text, selected).first { it.completeness == CandidateCompleteness.COMPLETE }

    @Test fun firstFrameStartsWithoutDictionaryOrCorrectingObservedTypo() {
        val candidate = first("경기도 안산시 상콕구 샘골로 123")
        assertEquals("경기도 안산시 상콕구 샘골로 123", candidate.text)
        assertFalse(candidate.dictionaryCorrected)
        assertEquals(candidate, AutoConversionPolicy().update(listOf(candidate), null, 0))
    }

    @Test fun namePhonePostalBuildingAndUnitsDoNotEnterBaseQuery() {
        val candidate = first("홍길동\n010-1234-5678\n12345\n경기도 안산시 상록구 샘골로 123, 101동 1203호\n배송관리부")
        assertEquals("경기도 안산시 상록구 샘골로 123", candidate.text)
        assertTrue(candidate.details.contains("1203호"))
    }

    @Test fun missingRegionIsSupplementedWithoutPickingOneOfSeveralLocalities() {
        assertEquals("경기도 안산시 상록구 본오동 123-4", first("본오동 123-4").text)
        assertEquals("경기도 안산시 상록구 샘골로 123", first("샘골로 123").text)
        assertEquals("경기도 안산시 상록구 본오동 5", first("상록구 본오동 5").text)
        assertEquals("경기도 본오동 5", first("본오동 5", RegionSelection("경기도")).text)
    }

    @Test fun explicitOtherRegionsAlwaysWinOverDefault() {
        assertEquals("서울특별시 중구 신당동 5", first("서울 중구 신당동 5").text)
        assertEquals("부산광역시 중구 중앙동 5", first("부산광역시 중구 중앙동 5").text)
        assertEquals("수원시 영통구 매탄동 5", first("수원시 영통구 매탄동 5").text)
        assertEquals("경기도 안산시 단원구 초지동 5", first("경기도 안산시 단원구 초지동 5").text)
    }

    @Test fun observedMultilineRoadIsRestoredBeforeDictionary() {
        val candidate = first("경기도 안산시 단원구 광덕\n동로 25(고잔동. 안산레이\n크타운 푸르지오) 102동 2\n901호")
        assertEquals("경기도 안산시 단원구 광덕동로 25", candidate.text)
        assertTrue(candidate.details.contains("2901호"))
        assertEquals(candidate, AutoConversionPolicy().update(listOf(candidate), null, 0))
    }

    @Test fun roadDigitsAndMountainParcelNumbersArePreserved() {
        assertEquals("경기도 안산시 상록구 샘골로12길 123-4", first("샘골로12길123-4").text)
        assertEquals("경기도 안산시 상록구 본오동 산 5-1", first("본오동 산5-1번지").text)
        assertEquals("경기도 안산시 상록구 본오동 5", first("본오동 5").text)
    }

    @Test fun partialPhoneOrUnitOnlyNeverStartsApi() {
        for (text in listOf("본오동", "샘골로", "본오동 010-1234-5678", "샘골로 101동 1203호", "홍길동\n12345")) {
            assertNull(text, AutoConversionPolicy().update(raw(text), null, 0))
        }
    }

    @Test fun independentBlocksRemainSeparateAndAmbiguousAddressesNeedSelection() {
        val candidates = RawAddressCandidates.fromBlocks(listOf("본오동 5", "초지동 7"), RegionSelection())
        assertEquals(2, candidates.size)
        assertNull(AutoConversionPolicy().update(candidates, null, 0))
        assertTrue(RawAddressCandidates.fromBlocks(listOf("본오동", "123"), region).none { it.completeness == CandidateCompleteness.COMPLETE })
    }

    @Test fun ocrFramesCannotOverwriteSearchInProgressOrCompletedState() {
        val diagnostics = ScanDiagnostics()
        diagnostics.begin("본오동 5")
        diagnostics.observe(true, false)
        assertEquals("검색 중", diagnostics.searchStage)
        assertEquals("본오동 5", diagnostics.request)
        diagnostics.finish("완료", "샘골로 10")
        diagnostics.observe(false, false)
        assertEquals("완료", diagnostics.searchStage)
        assertEquals("샘골로 10", diagnostics.result)
        diagnostics.reset()
        assertEquals("", diagnostics.request)
    }

    @Test fun providerErrorsAreNotDisguisedByAnotherProvidersMiss() {
        val converter = AddressConverter("")
        try {
            val error = ConversionOutcome.NetworkError("401")
            assertEquals(error, converter.selectOutcome(listOf(error, ConversionOutcome.NotFound)))
            assertEquals(error, converter.selectOutcome(listOf(ConversionOutcome.NoExactMatch(emptyList()), error)))
            assertEquals(ConversionOutcome.Offline, converter.selectOutcome(listOf(error, ConversionOutcome.Offline)))
        } finally { converter.close() }
    }
}
