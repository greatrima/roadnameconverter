package kr.co.addresslens

import org.junit.Assert.*
import org.junit.Test

class AutoConversionPolicyTest {
    private fun address(text: String, score: Int = 100) =
        AddressCandidate(text, AddressKind.PARCEL, confidence = score)

    @Test fun rawAddressStartsImmediatelyWithManualCorrectionsStillVisible() {
        val policy = AutoConversionPolicy()
        val exact = address("경기도 안산시 상록구 본오동 123-4")
        val candidates = listOf(exact, address("경기도 안산시 상록구 본원동 123-4", 93).copy(manualOnly = true))
        assertEquals(exact, policy.update(candidates, null, 0))
        assertNull(policy.update(candidates.reversed(), exact, 300))
    }

    @Test fun twoEquallyLikelyRegionsNeedSelection() {
        val policy = AutoConversionPolicy()
        val candidates = listOf(address("경기도 성남시 중앙동 123"), address("경기도 과천시 중앙동 123"))
        repeat(10) { assertNull(policy.update(candidates, null, it * 300L)) }
    }

    @Test fun partialNeverStartsButLowScoreAndMissingDictionaryDoNotBlockRawAddress() {
        val policy = AutoConversionPolicy()
        val partial = AddressCandidate("본오동", AddressKind.PARCEL, CandidateCompleteness.PARTIAL)
        repeat(4) {
            assertNull(policy.update(listOf(partial), null, it * 300L))
        }
        val raw = address("본오동 123", 10)
        assertEquals(raw, policy.update(listOf(raw), null, 1_200))
    }

    @Test fun firstFrameSearchesButChangedAddressNeedsStableFramesAndCooldown() {
        val policy = AutoConversionPolicy()
        val first = address("본오동 123")
        val full = address("본오동 123-4")
        assertEquals(first, policy.update(listOf(first), null, 0))
        assertNull(policy.update(listOf(full), first, 300))
        assertNull(policy.update(listOf(full), first, 600)) // rate-limited
        assertNull(policy.update(emptyList(), first, 900)) // breaks the consecutive run
        assertNull(policy.update(listOf(full), first, 1_200))
        assertEquals(full, policy.update(listOf(full), first, 1_500))
    }

    @Test fun detailsChangeIsStableAndMissingDetailsCannotEraseSelection() {
        val policy = AutoConversionPolicy()
        val selected = address("본오동 5").copy(details = "101동 1203호")
        val next = selected.copy(details = "101동 1204호")
        assertEquals(selected, policy.update(listOf(selected), null, 0))
        assertNull(policy.update(listOf(selected.copy(details = "")), selected, 1_200))
        assertNull(policy.update(listOf(next), selected, 1_500))
        assertEquals(next, policy.update(listOf(next), selected, 1_800))
    }

    @Test fun repeatedWhitespaceVariantsAndTransientMisreadsDoNotRepeatRequests() {
        val policy = AutoConversionPolicy()
        var selected: AddressCandidate? = null
        var requests = 0
        val raw = address("본오동 5-1")
        repeat(100) { index ->
            val frame = when (index % 10) {
                5 -> address("본오동 5-7")
                6 -> raw.copy(text = "본오동\n5 - 1")
                else -> raw
            }
            policy.update(listOf(frame), selected, index * 300L)?.let { selected = it; requests++ }
        }
        assertEquals(1, requests)
        policy.reset()
        assertEquals(raw, policy.update(listOf(raw), null, 30_000))
    }

    @Test fun dictionarySpellingChangesNeverAutomaticallySearch() {
        val corrected = address("상록구 본오동 5").copy(dictionaryCorrected = true)
        repeat(5) { assertNull(AutoConversionPolicy().update(listOf(corrected), null, it * 300L)) }
    }

    @Test fun manuallyCorrectedChoiceSurvivesSameRawTypoUntilNextAddress() {
        val policy = AutoConversionPolicy()
        val raw = address("경기도 안산시 상콕구 본오동 5")
        val chosen = address("경기도 안산시 상록구 본오동 5").copy(manualOnly = true)
        policy.onSelected(0, listOf(raw))
        policy.pause() // Pausing/resuming must not lose a user's explicit correction.
        repeat(20) { assertNull(policy.update(listOf(raw), chosen, it * 300L)) }
        val next = address("경기도 안산시 상록구 본오동 7")
        assertNull(policy.update(listOf(next), chosen, 6_000))
        assertEquals(next, policy.update(listOf(next), chosen, 6_300))
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
