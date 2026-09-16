package kr.co.addresslens

import org.junit.Assert.assertEquals
import org.junit.Test

class CandidateTrackerTest {
    private fun complete(text: String) = AddressCandidate(text, AddressKind.PARCEL)
    private fun partial(text: String) = AddressCandidate(
        text, AddressKind.PARCEL, CandidateCompleteness.PARTIAL, 60
    )

    @Test
    fun keepsCandidatesThroughTransientFailureAndUpgradesPartial() {
        val tracker = CandidateTracker()
        assertEquals("응암동", tracker.update(listOf(partial("응암동")), 1_000).single().text)
        assertEquals("응암동", tracker.update(emptyList(), 2_000).single().text)
        assertEquals(
            "응암동 118-36",
            tracker.update(listOf(complete("응암동 118-36")), 2_100).single().text
        )
    }

    @Test
    fun replacesClearlyDifferentAddressOnlyAfterRepeatedFrames() {
        val tracker = CandidateTracker(replaceAfterFrames = 3)
        tracker.update(listOf(complete("응암동 118-36")), 1)
        repeat(2) { index ->
            assertEquals(
                "응암동 118-36",
                tracker.update(listOf(complete("좌천동 849-25")), (index + 2).toLong()).single().text
            )
        }
        assertEquals(
            "좌천동 849-25",
            tracker.update(listOf(complete("좌천동 849-25")), 4).single().text
        )
    }

    @Test
    fun freezesSelectionAgainstLateUpdates() {
        val tracker = CandidateTracker()
        val selected = complete("본오동 123-4")
        tracker.update(listOf(selected), 1)
        tracker.freeze(selected)
        assertEquals(selected, tracker.update(listOf(complete("좌천동 849-25")), 10_000).single())
    }

    @Test
    fun doesNotMergeSameParcelAndNumberFromDifferentRegions() {
        val tracker = CandidateTracker(replaceAfterFrames = 3)
        tracker.update(listOf(complete("서울특별시 은평구 응암동 118-36")), 1)

        repeat(2) { index ->
            assertEquals(
                "서울특별시 은평구 응암동 118-36",
                tracker.update(
                    listOf(complete("부산광역시 부산진구 응암동 118-36")),
                    (index + 2).toLong()
                ).single().text
            )
        }
        assertEquals(
            "부산광역시 부산진구 응암동 118-36",
            tracker.update(listOf(complete("부산광역시 부산진구 응암동 118-36")), 4).single().text
        )
    }

    @Test
    fun doesNotMergeSameRoadAndNumberFromDifferentDistricts() {
        val tracker = CandidateTracker(replaceAfterFrames = 2)
        tracker.update(listOf(AddressCandidate("경기도 수원시 중앙로 10", AddressKind.ROAD)), 1)

        assertEquals(
            "경기도 수원시 중앙로 10",
            tracker.update(
                listOf(AddressCandidate("경기도 안산시 중앙로 10", AddressKind.ROAD)),
                2
            ).single().text
        )
        assertEquals(
            "경기도 안산시 중앙로 10",
            tracker.update(
                listOf(AddressCandidate("경기도 안산시 중앙로 10", AddressKind.ROAD)),
                3
            ).single().text
        )
    }

    @Test
    fun allowsLaterFrameToCompleteMissingHierarchy() {
        val tracker = CandidateTracker()
        tracker.update(listOf(partial("응암동")), 1)

        assertEquals(
            "서울특별시 은평구 응암동 118-36",
            tracker.update(listOf(complete("서울특별시 은평구 응암동 118-36")), 2).single().text
        )
    }
}
