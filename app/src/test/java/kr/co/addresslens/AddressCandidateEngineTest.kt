package kr.co.addresslens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressCandidateEngineTest {
    private val dictionary = AddressDictionary.fromBuckets(
        mapOf(
            "경기도" to mapOf(
                "안산시 상록구" to AddressDictionary.Bucket(
                    localities = setOf("본오동", "사동"),
                    roads = mapOf("샘골로12길" to setOf("본오동"))
                ),
                "안산시 단원구" to AddressDictionary.Bucket(
                    localities = setOf("고잔동", "초지동"),
                    roads = mapOf("광덕동로" to setOf("고잔동"))
                )
            ),
            "부산광역시" to mapOf(
                "동구" to AddressDictionary.Bucket(
                    localities = setOf("좌천동"),
                    roads = emptyMap()
                )
            ),
            "전라남도" to mapOf(
                "해남군" to AddressDictionary.Bucket(
                    localities = setOf("산이면", "덕송리"),
                    roads = emptyMap()
                )
            )
        )
    )
    private val engine = AddressCandidateEngine(dictionary)

    @Test fun fuzzyMatchingNeverChangesInsertsOrDeletesRoadNumbers() {
        val region = RegionSelection("경기도", "안산시 상록구")
        // This fixture has 샘골로12길 only. Similar numbered/unnumbered roads must stay unverified.
        for (road in listOf("샘골로1길", "샘골로13길", "샘골로길")) {
            assertTrue(dictionary.match(road, AddressKind.ROAD, region).isEmpty())
            val candidate = engine.candidate("$road 123-4", region)!!
            assertEquals("경기도 안산시 상록구 $road 123-4", candidate.text)
            assertTrue(candidate.confidence < 88)
            assertFalse(candidate.verified)
        }
        val corrected = engine.candidate("샘굴로12길 123-4", region)!!
        assertEquals("경기도 안산시 상록구 샘골로12길 123-4", corrected.text)
    }

    @Test fun provinceOnlyPreferenceAndCityWithoutGuAreSupported() {
        assertTrue("안산시" in dictionary.districts("경기도"))
        assertTrue("고잔동" in dictionary.localities("경기도", "안산시"))
        assertTrue("본오동" in dictionary.localities("경기도", "안산시"))
        assertEquals("경기도 안산시 단원구 광덕동로 25",
            engine.candidate("광덕동로 25", RegionSelection("경기도", "안산시"))!!.text)
        assertEquals("경기도 안산시 상록구 본오동 123-4",
            engine.candidate("본오동 123-4", RegionSelection("경기도"))!!.text)
        assertEquals("안산시", dictionary.correctDistrict("안산시", "경기도"))
    }

    @Test
    fun correctsOcrDistrictUsingConfiguredContextAndPreservesNumber() {
        val candidate = engine.candidate(
            "상콕구 본오동 123-4",
            RegionSelection("경기도", "안산시 상록구")
        )!!
        assertEquals("경기도 안산시 상록구 본오동 123-4", candidate.text)
        assertTrue(candidate.dictionaryCorrected)
        assertFalse(candidate.verified)
    }

    @Test fun countyPreferenceWorksWithoutChoosingTownshipOrVillage() {
        val region = RegionSelection("전라남도", "해남군")
        assertTrue(region.localities.isEmpty())
        assertEquals("전라남도 해남군", region.displayName())
        assertEquals("전라남도 해남군 덕송리 123", engine.candidate("덕송리 123", region)!!.text)
    }

    @Test
    fun fillsOmittedRegionFromPreference() {
        val candidate = engine.candidate(
            "샘골로12길 123",
            RegionSelection("경기도", "안산시 상록구", setOf("본오동"))
        )!!
        assertEquals("경기도 안산시 상록구 샘골로12길 123", candidate.text)
    }

    @Test
    fun explicitOtherRegionWinsOverPreference() {
        val candidate = engine.candidate(
            "부산광역시 동구 좌천동 849-25",
            RegionSelection("경기도", "안산시 상록구")
        )!!
        assertEquals("부산광역시 동구 좌천동 849-25", candidate.text)
    }

    @Test
    fun preservesExplicitTownshipBetweenDistrictAndVillage() {
        val candidate = engine.candidate(
            "해남군 산이면 덕송리 123",
            RegionSelection("경기도", "안산시 상록구")
        )!!
        assertEquals("전라남도 해남군 산이면 덕송리 123", candidate.text)
    }
}
