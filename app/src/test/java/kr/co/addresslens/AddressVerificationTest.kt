package kr.co.addresslens

import org.junit.Assert.*
import org.junit.Test

class AddressVerificationTest {
    @Test fun provinceAbbreviationSurvivesExtractionBeforeApiLookup() {
        val cleaned = AddressTextParser.extract("서울 중구\n신당동 5")!!
        assertEquals("서울특별시 중구 신당동 5", cleaned)
        assertFalse(AddressMatchValidator.exact(cleaned, "부산광역시 중구 신당동 5"))
    }
    @Test fun comparesEveryParcelComponentWithoutFuzzyNumbers() {
        val query = "경기도 안산시 상록구 본오동 5"
        assertTrue(AddressMatchValidator.exact(query, "$query (본오빌라)"))
        for (wrong in listOf("경기도 안산시 상록구 본오동 5-1", "경기도 안산시 상록구 본오동 산 5",
            "경기도 안산시 상록구 사동 5", "경기도 안산시 단원구 본오동 5", "경기도 수원시 상록구 본오동 5")) {
            assertFalse(wrong, AddressMatchValidator.exact(query, wrong))
        }
        assertFalse(AddressMatchValidator.exact("본오동 5-1", "본오동 5"))
        assertFalse(AddressMatchValidator.exact("본오동 산 5-1", "본오동 5-1"))
        assertTrue(AddressMatchValidator.exact("경기 안산시 본오동 5-1", "경기도 안산시 상록구 본오동 5-1"))
        assertFalse(AddressMatchValidator.exact("충남 예산군 삽교읍 신리 5", "충청남도 예산군 덕산면 신리 5"))
    }

    @Test fun relatedParcelsMustBeExplicitAndUnspecifiedLandCannotBecomeMountainLand() {
        val related = AddressMatchValidator.relatedParcels("경기도 안산시 상록구 본오동 5-1", "5-1, 5-2")
        assertTrue(related.any { AddressMatchValidator.exact("본오동 5-2", it) })
        assertFalse(related.any { AddressMatchValidator.exact("본오동 5", it) })
        assertTrue(AddressMatchValidator.relatedParcels("본오동 5-1", "5-1 외 2필지").isEmpty())
        assertTrue(AddressMatchValidator.relatedParcels("본오동 산 5", "6").isEmpty())
        assertEquals(listOf("본오동 산 6"), AddressMatchValidator.relatedParcels("본오동 산 5", "산 6"))
    }

    @Test fun eachProviderUsesExactGateRatherThanFirstSimilarHit() {
        val converter = AddressConverter("")
        try { for (source in AddressResult.Source.entries) {
            val rows = listOf(AddressConverter.ProviderAddress("경기도 안산시 상록구 본오동 5-1", "경기도 안산시 상록구 샘골로 11"))
            val miss = converter.matchRows("경기도 안산시 상록구 본오동 5", AddressKind.PARCEL, rows, source)
            assertTrue(miss is ConversionOutcome.NoExactMatch)
            assertEquals("경기도 안산시 상록구 본오동 5-1", (miss as ConversionOutcome.NoExactMatch).suggestions.single().recognizedAddress)
            val exact = converter.matchRows("본오동 5-1", AddressKind.PARCEL, rows, source)
            assertTrue(exact is ConversionOutcome.Success)
            val related = converter.matchRows("본오동 5", AddressKind.PARCEL,
                listOf(rows.single().copy(related = listOf("경기도 안산시 상록구 본오동 5"))), source)
            assertTrue(related is ConversionOutcome.Success)
        } } finally { converter.close() }
    }

    @Test fun omittedRegionDoesNotPickFirstOfMultipleExactParcels() {
        val converter = AddressConverter("")
        try {
            val rows = listOf(AddressConverter.ProviderAddress("경기도 안산시 상록구 본오동 5", "경기도 안산시 상록구 샘골로 10"),
                AddressConverter.ProviderAddress("경기도 가상시 본오동 5", "경기도 가상시 새길 20"))
            assertTrue(converter.matchRows("본오동 5", AddressKind.PARCEL, rows, AddressResult.Source.VWORLD) is ConversionOutcome.NoExactMatch)
            assertTrue(converter.matchRows("경기도 안산시 상록구 본오동 5", AddressKind.PARCEL, rows, AddressResult.Source.VWORLD) is ConversionOutcome.Success)
        } finally { converter.close() }
    }

    @Test fun manualOnlySuggestionsNeverAutoConfirmEvenWithPerfectConfidence() {
        val policy = AutoConversionPolicy()
        val candidate = AddressCandidate("본오동 5-1", AddressKind.PARCEL, manualOnly = true)
        repeat(5) { assertNull(policy.update(listOf(candidate), null, it * 300L)) }
    }

    @Test fun editAndOldRequestCannotReplaceNewSelection() {
        val gate = ConversionRequestGate()
        val old = gate.begin()
        gate.invalidate() // Input gains focus.
        assertFalse(gate.finish(old))
        val edited = gate.begin()
        assertTrue(gate.finish(edited))
        assertFalse(gate.finish(old))
    }
}
