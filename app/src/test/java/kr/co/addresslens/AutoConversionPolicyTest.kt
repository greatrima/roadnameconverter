package kr.co.addresslens

import org.junit.Assert.*
import org.junit.Test

class AutoConversionPolicyTest {
    private fun address(text: String, score: Int = 100) =
        AddressCandidate(text, AddressKind.PARCEL, confidence = score)

    @Test fun exactAddressStartsWithWeakerAlternativesStillVisible() {
        val policy = AutoConversionPolicy()
        val exact = address("경기도 안산시 상록구 본오동 123-4")
        val candidates = listOf(exact, address("경기도 안산시 상록구 본원동 123-4", 93))
        assertNull(policy.update(candidates, true))
        assertEquals(exact, policy.update(candidates.reversed(), true))
    }

    @Test fun twoEquallyLikelyRegionsNeedSelection() {
        val policy = AutoConversionPolicy()
        val candidates = listOf(address("경기도 성남시 중앙동 123"), address("경기도 과천시 중앙동 123"))
        repeat(10) { assertNull(policy.update(candidates, true)) }
    }

    @Test fun partialOrMissingDictionaryNeverStartsSearch() {
        val policy = AutoConversionPolicy()
        val partial = AddressCandidate("본오동", AddressKind.PARCEL, CandidateCompleteness.PARTIAL)
        repeat(4) {
            assertNull(policy.update(listOf(partial), true))
            assertNull(policy.update(listOf(address("본오동 123")), false))
        }
    }

    @Test fun emptyFrameAndChangingNumbersResetStability() {
        val policy = AutoConversionPolicy()
        val first = address("본오동 123")
        val full = address("본오동 123-4")
        assertNull(policy.update(listOf(first), true))
        assertNull(policy.update(emptyList(), true))
        assertNull(policy.update(listOf(first), true))
        assertNull(policy.update(listOf(full), true))
        assertEquals(full, policy.update(listOf(full), true))
    }

    @Test fun selectedAddressKeepsOtherOptionsAndIgnoresLateOcr() {
        val tracker = CandidateTracker()
        val selected = address("본오동 123")
        val alternative = address("초지동 456")
        tracker.update(listOf(selected, alternative), 0)
        tracker.freeze(selected)
        assertEquals(listOf(selected, alternative), tracker.update(listOf(address("사동 789")), 100))
    }

    @Test fun mountainParcelIsNotTheSameAddress() {
        assertFalse(CandidateTracker.isSameAddressFamily(address("본오동 산 123"), address("본오동 123")))
    }

    @Test fun oldReleaseDictionaryCannotReplaceNewerBundledData() {
        assertTrue(DictionaryUpdater.isOlderVersion("2026-09-13", "2026-09-14"))
        assertFalse(DictionaryUpdater.isOlderVersion("2026-09-14T20:00:00", "2026-09-14"))
    }
}
