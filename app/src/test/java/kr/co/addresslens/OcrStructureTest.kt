package kr.co.addresslens

import org.junit.Assert.*
import org.junit.Test

class OcrStructureTest {
    private fun line(text: String, x: Float, y: Float, w: Float, h: Float) = OcrLine(text,FloatBox(x,y,x+w,y+h))

    @Test fun joinsDifferentFontSizesInOneSpatialAddress() {
        val groups = OcrLineAssembler.assemble(listOf(line("경기도 안산시 단원구 광덕",10f,10f,220f,14f),
            line("동로 25",10f,30f,100f,28f)))
        assertEquals(1, groups.size)
        assertEquals("경기도 안산시 단원구 광덕동로 25", AddressTextParser.extract(groups.single().text))
    }
    @Test fun smallBaseBelowLargeBuildingNameStillExtractsWithoutCompanyOrPhone() {
        val groups = OcrLineAssembler.assemble(listOf(line("안산레이크타운 푸르지오",10f,10f,350f,32f),
            line("경기도 안산시 단원구 광덕동로 25",10f,50f,250f,12f),
            line("전화: 010-1234-5678",10f,70f,150f,12f)))
        val found = groups.mapNotNull { AddressTextParser.extract(it.text) }
        assertEquals(listOf("경기도 안산시 단원구 광덕동로 25"), found)
        assertTrue(groups.mapNotNull { AddressTextParser.extractCandidate(it.text) }.single().details.contains("푸르지오"))
    }
    @Test fun sideBySideAndVerticallyAdjacentCompleteAddressesStaySeparate() {
        val groups = OcrLineAssembler.assemble(listOf(line("응암동 118-36",10f,10f,150f,24f),
            line("원곡동 814",250f,10f,140f,24f), line("좌천동 849-25",10f,42f,170f,24f)))
        assertEquals(3, groups.size)
    }
    @Test fun distantNameAndNumberCannotBeCombined() {
        val groups = OcrLineAssembler.assemble(listOf(line("응암동",10f,10f,80f,20f),
            line("118-36",260f,60f,100f,20f)))
        assertEquals(2, groups.size)
        assertTrue(groups.all { AddressTextParser.extract(it.text) == null })
    }
    @Test fun restoresObservedAdministrativeFragmentsButDoesNotInventMissingLetters() {
        assertEquals("경기도 안산시 상록구 본오동 123-4",
            AddressTextParser.extract("경기도 안산시 상록\n구 본오\n동 123-4"))
        assertEquals("광덕동로 25", AddressTextParser.extract("광덕\n동로 25"))
        assertFalse(AddressTextParser.extract("광\n로 25").orEmpty().contains("광덕동로"))
    }
    @Test fun phoneNumberNeverBecomesParcelNumber() {
        assertNull(AddressTextParser.extract("홍길동 010-1234-5678"))
        assertEquals("본오동 123-4", AddressTextParser.extract("본오동 123-4\n성명: 홍길동\n전화: 010-1234-5678"))
    }
    @Test fun detailsRestoreAndAffectIdentityButNotBaseQuery() {
        val text = "경기도 안산시 단원구 광덕\n동로 25(고잔동. 안산레이\n크타운 푸르지오) 102동 2\n901호"
        val candidate = AddressTextParser.extractCandidate(text)!!
        assertEquals("경기도 안산시 단원구 광덕동로 25", candidate.text)
        assertTrue(candidate.details, candidate.details.contains("2901호"))
        assertTrue(candidate.details, candidate.details.contains("안산레이크타운"))
        val changed = candidate.copy(details = "102동 2902호")
        assertNotEquals(CandidateTracker.identity(candidate),CandidateTracker.identity(changed))
        assertFalse(CandidateTracker.isSameAddressFamily(candidate,changed))
        assertEquals(candidate.text, changed.text)
    }
    @Test fun incompleteNumberIsNeverInventedFromRoomDetail() {
        val candidate = AddressTextParser.extractCandidate("광덕동로\n102동 2901호")
        assertNull(candidate)
    }
    @Test fun baseNumberCannotMergeWithRoomNumberOnNextLine() {
        val candidate = AddressTextParser.extractCandidate("광덕동로 25\n901호")!!
        assertEquals("광덕동로 25", candidate.text)
        assertEquals("901호", candidate.details)
    }
    @Test fun regionLineDoesNotBecomePartOfRoadName() {
        assertEquals("경기도 안산시 단원구 광덕동로 25", AddressTextParser.extract("경기도 안산시 단원구\n광덕동로 25"))
        assertEquals("샘골로12길 123-4", AddressTextParser.extract("샘골로12길123-4"))
    }
}
