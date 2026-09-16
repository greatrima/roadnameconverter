package kr.co.addresslens

/** Decide when to start verification, never whether an address actually exists. */
class AutoConversionPolicy(private val requiredFrames: Int = 2) {
    private var pending: String? = null
    private var count = 0

    fun update(candidates: List<AddressCandidate>, dictionaryReady: Boolean): AddressCandidate? {
        if (!dictionaryReady) { reset(); return null }
        val complete = candidates.filter {
            !it.manualOnly && it.completeness == CandidateCompleteness.COMPLETE &&
                AddressTextParser.parseParts(it.text)?.number != null
        }.distinctBy(CandidateTracker::identity).sortedByDescending { it.confidence }
        val best = complete.firstOrNull() ?: run { reset(); return null }
        val runnerUp = complete.getOrNull(1)
        // Exact names can coexist with weaker spelling suggestions without blocking search.
        val clearWinner = best.confidence >= 88 && (runnerUp == null ||
            (best.confidence == 100 && runnerUp.confidence < 100) ||
            best.confidence - runnerUp.confidence >= 8)
        if (!clearWinner) { reset(); return null }
        val key = CandidateTracker.identity(best)
        if (key == pending) count++ else { pending = key; count = 1 }
        return best.takeIf { count >= requiredFrames }
    }

    fun reset() { pending = null; count = 0 }
}
