package kr.co.addresslens

import org.junit.Assert.*
import org.junit.Test

class OcrStructureTest {
    @Test fun reportedWadongHyphenWithUnlabeledDetailNeverSearchesMainNumberAlone() {
        val candidates = pipeline(line("부평물",10f,0f,100f,15f),
            line("경기도 안산시 단원구 와동 695-",10f,30f,500f,20f),
            line("1 303",10f,58f,100f,20f))
        val selected = AutoConversionPolicy().update(candidates,null,0)
        assertEquals("경기도 안산시 단원구 와동 695-1",selected?.text)
        assertEquals("",selected?.details)
        assertFalse(candidates.any { it.text.endsWith("와동 695") })
    }

    @Test fun unfinishedHyphenIncludingElementSpacesNeverProducesCompleteBase() {
        for (text in listOf("와동 695-", "와동 695 -", "와동 695 - ", "샘골로 123 -", "와동 산 5 -")) {
            assertNull(text,AddressTextParser.extract(text))
            assertNull(text,AutoConversionPolicy().update(RawAddressCandidates.fromBlocks(listOf(text),RegionSelection()),null,0))
        }
    }

    @Test fun spacedHyphenElementsRestoreOnlyNearbyObservedSubnumber() {
        val rows = listOf(listOf(line("와동",10f,10f,70f,20f),line("695",85f,10f,50f,20f),
            line("-",140f,10f,10f,20f)),listOf(line("1",10f,38f,15f,20f),line("303",30f,38f,55f,20f)))
        val blocks = OcrLineAssembler.assembleElements(rows)
        val candidates = RawAddressCandidates.fromSpatialBlocks(blocks,RegionSelection())
        assertEquals("와동 695-1",AutoConversionPolicy().update(candidates,null,0)?.text)
        assertEquals("",candidates.single().details)
        for (next in listOf(line("1 303",600f,38f,100f,20f),line("1 303",10f,200f,100f,20f),
                line("203호",10f,38f,100f,20f),line("010-1234-5678",10f,38f,200f,20f))) {
            assertNull(AutoConversionPolicy().update(pipeline(line("와동 695 -",10f,10f,200f,20f),next),null,0))
        }
        assertNull(AddressTextParser.extract("와동 695-1 -"))
        assertEquals("와동 695-1",AddressTextParser.extract("와동 695 - 1 303"))
    }

    private fun line(text: String, x: Float, y: Float, w: Float, h: Float) = OcrLine(text,FloatBox(x,y,x+w,y+h))

    @Test fun reportedParcelWithIndentedNumberAndRoomStartsSearch() {
        val groups = OcrLineAssembler.assemble(listOf(
            line("경기 안산시 단원구 초지동", 20f,20f,1000f,50f),
            line("716-7 203호",440f,90f,500f,50f)))
        val candidates = RawAddressCandidates.fromSpatialBlocks(groups, RegionSelection("경기도"))
        val selected = AutoConversionPolicy().update(candidates, null, 0)
        assertNotNull("Parcel+room line must reach the automatic search gate", selected)
        assertEquals("경기도 안산시 단원구 초지동 716-7", selected!!.text)
        assertEquals("203호", selected.details)
    }

    @Test fun reportedNumericRoadSuffixSplitStartsSearch() {
        val groups = OcrLineAssembler.assemble(listOf(
            line("경기 안산시 단원구 원선1",20f,20f,1000f,50f),
            line("로 37 104동1202호",20f,90f,700f,50f)))
        val candidates = RawAddressCandidates.fromSpatialBlocks(groups, RegionSelection("경기도"))
        val selected = AutoConversionPolicy().update(candidates, null, 0)
        assertNotNull("Numeric road fragment+suffix must reach the automatic search gate", selected)
        assertEquals("경기도 안산시 단원구 원선1로 37", selected!!.text)
        assertEquals("104동 1202호", selected.details)
    }

    @Test fun joinsDifferentFontSizesInOneSpatialAddress() {
        val groups = OcrLineAssembler.assemble(listOf(line("경기도 안산시 단원구 광덕",10f,10f,220f,14f),
            line("동로 25",10f,30f,100f,28f)))
        assertEquals(1, groups.size)
        assertEquals("경기도 안산시 단원구 광덕동로 25", AddressTextParser.extract(groups.single().text))
    }

    private fun pipeline(vararg lines: OcrLine): List<AddressCandidate> = RawAddressCandidates.fromSpatialBlocks(
        OcrLineAssembler.assemble(lines.toList()), RegionSelection("경기도", "안산시 단원구"))

    @Test fun roadNumberAndMountainParcelWithUnitsWorkWithDifferentFontsAndIndent() {
        for ((stem, number, expected) in listOf(Triple("샘골로", "123, 101동 1203호", "샘골로 123"),
                Triple("본오동", "산 5-1 2층", "본오동 산 5-1"))) {
            val candidates = pipeline(line(stem,10f,10f,260f,12f),line(number,120f,40f,180f,32f))
            assertEquals("경기도 안산시 단원구 $expected", AutoConversionPolicy().update(candidates,null,0)?.text)
            assertFalse(candidates.single().details.isBlank())
        }
    }

    @Test fun numberedRoadAndPureSuffixVariantsAreRestored() {
        for ((left,right,base) in listOf(Triple("샘골로12","길 123-4 101동1203호","샘골로12길 123-4"),
                Triple("광덕","동로 25 203호","광덕동로 25"), Triple("중앙","대로 25 2층","중앙대로 25"))) {
            val candidates = pipeline(line(left,10f,10f,240f,20f),line(right,10f,38f,240f,20f))
            assertEquals(base, AddressTextParser.parseParts(AutoConversionPolicy().update(candidates,null,0)!!.text)?.let {
                "${it.name} ${it.number}" })
        }
    }

    @Test fun explicitHyphenAllowsOnlyObservedSubNumberToContinue() {
        val candidates = pipeline(line("초지동 716-",10f,10f,220f,20f),line("7 203호",10f,38f,140f,20f))
        val selected = AutoConversionPolicy().update(candidates,null,0)!!
        assertTrue(selected.text.endsWith("초지동 716-7"))
        assertEquals("203호",selected.details)
        val noHyphen = pipeline(line("초지동 716",10f,10f,220f,20f),line("7 203호",10f,38f,140f,20f))
        assertTrue(noHyphen.none { it.text.endsWith("7167") || it.text.endsWith("716-7") })
    }

    @Test fun elementBoundariesRecoverBaseAndRoomButPackedTokenIsNeverSplitByGuess() {
        val region = RegionSelection("경기도")
        val first = listOf(line("경기 안산시 단원구 초지동",10f,10f,500f,30f))
        val elements = listOf(line("716-7",180f,55f,100f,30f),line("203호",290f,55f,90f,30f))
        val separated = RawAddressCandidates.fromSpatialBlocks(OcrLineAssembler.assembleElements(listOf(first,elements)),region)
        assertEquals("경기도 안산시 단원구 초지동 716-7", AutoConversionPolicy().update(separated,null,0)?.text)
        assertEquals("203호",separated.single().details)
        val packed = RawAddressCandidates.fromSpatialBlocks(OcrLineAssembler.assembleElements(listOf(first,
            listOf(line("716-7203호",180f,55f,200f,30f)))),region)
        assertNull(AutoConversionPolicy().update(packed,null,0))
    }

    @Test fun unitOnlyPhoneAndLabeledPostalLinesDoNotSupplyBaseNumber() {
        for (noise in listOf("203호", "101동", "2층", "010-1234-5678", "우편번호: 12345", "이름: 홍길동 123", "배송 메모: 123", "부서: 영업1부")) {
            val candidates = pipeline(line("초지동",10f,10f,240f,20f),line(noise,10f,38f,240f,20f))
            assertNull(noise,AutoConversionPolicy().update(candidates,null,0))
        }
    }

    @Test fun fiveDigitBaseIsRetainedButBarePostalLikeLineNeedsConfirmation() {
        val full = pipeline(line("초지동 12345",10f,10f,240f,20f))
        assertTrue(AutoConversionPolicy().update(full,null,0)!!.text.endsWith("12345"))
        val separate = pipeline(line("초지동",10f,10f,240f,20f),line("12345",10f,38f,100f,20f))
        assertTrue(separate.single().text.endsWith("12345"))
        assertTrue(separate.single().manualOnly)
        assertNull(AutoConversionPolicy().update(separate,null,0))
    }

    @Test fun ambiguousPredecessorsAndOtherColumnAreNeverSelectedArbitrarily() {
        val trace = mutableListOf<String>()
        val groups = OcrLineAssembler.assemble(listOf(line("초지동",10f,10f,120f,20f),
            line("원곡동",170f,10f,120f,20f),line("716-7 203호",10f,40f,280f,20f)),trace)
        val candidates = RawAddressCandidates.fromSpatialBlocks(groups,RegionSelection())
        assertNull(AutoConversionPolicy().update(candidates,null,0))
        assertTrue(trace.any { it.contains("가능한 앞줄이 여러 개") })
        val otherColumn = pipeline(line("초지동",10f,10f,120f,20f),line("716-7 203호",400f,40f,240f,20f))
        assertNull(AutoConversionPolicy().update(otherColumn,null,0))
    }

    @Test fun diagnosticStatesDistinguishPendingWorkFromNoResultAndKeepSearchState() {
        val state = ScanDiagnostics()
        state.begin("초지동 716-7")
        state.startRecognition()
        assertEquals(ScanDiagnostics.CandidateStage.RECOGNIZING,state.candidateStage)
        state.startExtraction()
        assertEquals(ScanDiagnostics.CandidateStage.EXTRACTING,state.candidateStage)
        state.observe(true,false)
        assertEquals(ScanDiagnostics.CandidateStage.EMPTY,state.candidateStage)
        assertFalse(state.candidateMessage.contains("중입니다"))
        assertEquals("검색 중",state.searchStage)
        state.reset("영역 변경")
        assertEquals("선택 영역 인식을 눌러주세요",state.candidateMessage)
    }

    @Test fun sameLineAndTwoLineSamplesHaveSameQueryAndDetails() {
        for ((full,left,right) in listOf(Triple("경기 안산시 단원구 초지동 716-7 203호","경기 안산시 단원구 초지동","716-7 203호"),
                Triple("경기 안산시 단원구 원선1로 37 104동1202호","경기 안산시 단원구 원선1","로 37 104동1202호"))) {
            val single = pipeline(line(full,10f,10f,800f,30f)).single()
            val wrapped = pipeline(line(left,10f,10f,700f,30f),line(right,120f,52f,480f,30f)).single()
            assertEquals(single.text,wrapped.text)
            assertEquals(single.details,wrapped.details)
        }
    }

    @Test fun removedContactOrPostalFieldCannotBridgeUnrelatedNumericLine() {
        for (field in listOf("우편번호", "전화: 010-1234-5678", "성명: 홍길동")) {
            val candidates = pipeline(line("초지동",10f,10f,400f,40f),
                line(field,10f,55f,300f,12f),line("716-7",10f,72f,140f,20f))
            assertTrue(field, candidates.none { it.completeness == CandidateCompleteness.COMPLETE })
        }
    }

    @Test fun noiseLabelsDoNotRemoveRoadNamesThatMerelyShareTheirPrefix() {
        assertEquals("우편로 12345", AddressTextParser.extract("우편로 12345"))
        assertEquals("초지동 716-7", AddressTextParser.extract("이름: 홍길동 123\n우편번호: 12345\n초지동 716-7 203호\n배송 메모: 999호"))
    }

    @Test fun reportedRoadWorksFromIndividualOcrElementsThroughSearchGate() {
        val rows = listOf(listOf(line("경기",10f,10f,60f,30f),line("안산시",80f,10f,90f,30f),
            line("단원구",180f,10f,90f,30f),line("원선1",280f,10f,100f,30f)),
            listOf(line("로",10f,52f,30f,30f),line("37",50f,52f,50f,30f),line("104동1202호",110f,52f,200f,30f)))
        val trace = mutableListOf<String>()
        val blocks = OcrLineAssembler.assembleElements(rows,trace)
        val candidate = AutoConversionPolicy().update(RawAddressCandidates.fromSpatialBlocks(blocks,RegionSelection("경기도")),null,0)!!
        assertEquals("경기도 안산시 단원구 원선1로 37",candidate.text)
        assertEquals("104동 1202호",candidate.details)
        assertTrue(trace.any { it.startsWith("요소:") && it.contains("FloatBox") })
        assertTrue(trace.any { it.contains("관측된 도로명 조각/접미사") })
    }

    @Test fun administrativeHierarchyMaySpanAnotherLineBeforeParcelAndRoom() {
        val candidates = pipeline(line("경기도",10f,10f,200f,24f),
            line("안산시 단원구 초지동",10f,42f,400f,24f),line("716-7 203호",130f,74f,250f,24f))
        assertEquals("경기도 안산시 단원구 초지동 716-7",AutoConversionPolicy().update(candidates,null,0)?.text)
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
