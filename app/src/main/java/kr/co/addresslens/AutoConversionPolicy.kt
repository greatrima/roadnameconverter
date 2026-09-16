package kr.co.addresslens

/** Starts verification of observed addresses; never declares an address to be real. */
class AutoConversionPolicy(private val replacementFrames: Int = 2, private val minimumIntervalMs: Long = 1_200L) {
    private var pending: String? = null
    private var count = 0
    private var lastStartedAt: Long? = null
    private var manuallyChosenFrom = emptyList<AddressCandidate>()

    fun update(candidates: List<AddressCandidate>, selected: AddressCandidate?, nowMs: Long): AddressCandidate? {
        val complete = candidates.filter {
            !it.manualOnly && !it.dictionaryCorrected && it.completeness == CandidateCompleteness.COMPLETE &&
                AddressTextParser.parseParts(it.text)?.number != null
        }.distinctBy(CandidateTracker::identity)
        // Multiple observed addresses need selection, not an arbitrary first-row choice.
        val next = complete.singleOrNull() ?: run { clearPending(); return null }
        if (selected != null && manuallyChosenFrom.any { anchor ->
                CandidateTracker.isSameAddressFamily(next, anchor) &&
                    (next.details.isBlank() || AddressTextParser.normalizeKey(next.details) == AddressTextParser.normalizeKey(anchor.details))
            }) {
            clearPending()
            return null
        }
        if (selected != null && CandidateTracker.isSameAddressFamily(next, selected) &&
            (next.details.isBlank() || AddressTextParser.normalizeKey(next.details) == AddressTextParser.normalizeKey(selected.details))) {
            clearPending()
            return null
        }
        if (selected != null) {
            val key = CandidateTracker.identity(next)
            if (pending == key) count++ else { pending = key; count = 1 }
            if (count < replacementFrames) return null
        }
        if (lastStartedAt?.let { nowMs - it < minimumIntervalMs } == true) return null
        onSelected(nowMs)
        return next
    }

    fun onSelected(nowMs: Long, observed: List<AddressCandidate> = emptyList()) {
        lastStartedAt = nowMs; manuallyChosenFrom = observed.toList(); clearPending()
    }
    private fun clearPending() { pending = null; count = 0 }
    fun pause() { clearPending() }
    fun reset() { clearPending(); lastStartedAt = null; manuallyChosenFrom = emptyList() }
}
