package kr.co.addresslens

class CandidateTracker(
    private val replaceAfterFrames: Int = 3,
    private val emptyGraceMs: Long = 2_500L
) {
    private var visible = emptyList<AddressCandidate>()
    private var pendingSignature: String? = null
    private var pendingCount = 0
    private var lastNonEmptyAt = 0L
    private var frozen = false

    fun update(incoming: List<AddressCandidate>, nowMs: Long): List<AddressCandidate> {
        if (frozen) return visible
        if (incoming.isEmpty()) return if (nowMs - lastNonEmptyAt <= emptyGraceMs) visible else emptyList()
        lastNonEmptyAt = nowMs
        if (visible.isEmpty()) {
            visible = incoming
            return visible
        }
        if (incoming.any { isSameAddressFamily(it, visible.first()) }) {
            visible = mergeStable(visible, incoming)
            pendingSignature = null
            pendingCount = 0
            return visible
        }
        val signature = incoming.joinToString("|") { identity(it) }
        if (signature == pendingSignature) pendingCount++ else {
            pendingSignature = signature
            pendingCount = 1
        }
        if (pendingCount >= replaceAfterFrames) {
            visible = incoming
            pendingSignature = null
            pendingCount = 0
        }
        return visible
    }

    fun freeze(selected: AddressCandidate, alternatives: List<AddressCandidate> = visible): AddressCandidate {
        frozen = true
        visible = (listOf(selected) + alternatives).distinctBy { identity(it) }.take(3)
        return selected
    }

    fun unfreeze(keepVisible: Boolean = false) {
        frozen = false
        pendingSignature = null
        pendingCount = 0
        if (!keepVisible) visible = emptyList()
    }

    fun clear() {
        frozen = false
        visible = emptyList()
        pendingSignature = null
        pendingCount = 0
        lastNonEmptyAt = 0L
    }

    private fun mergeStable(old: List<AddressCandidate>, newer: List<AddressCandidate>): List<AddressCandidate> {
        val result = old.toMutableList()
        newer.forEach { candidate ->
            val index = result.indexOfFirst { isSameAddressFamily(it, candidate) }
            if (index >= 0) {
                val existing = result[index]
                if (candidate.completeness > existing.completeness || candidate.confidence > existing.confidence ||
                    (existing.details.isEmpty() && candidate.details.isNotEmpty())) {
                    result[index] = candidate
                }
            } else result += candidate
        }
        return result.distinctBy(::identity).take(5)
    }

    companion object {
        /** Stable identity used by both candidate tracking and continuous-scan hand-off. */
        internal fun identity(candidate: AddressCandidate): String {
            val parts = AddressTextParser.parseParts(candidate.text)
            return if (parts == null) AddressTextParser.normalizeKey(candidate.text) else listOf(
                hierarchyKey(parts.prefix),
                parts.kind.name,
                AddressTextParser.normalizeKey(parts.name),
                parts.mountain.toString(),
                parts.number.orEmpty(), AddressTextParser.normalizeKey(candidate.details),
                AddressTextParser.normalizeKey(candidate.alternativeTarget)
            ).joinToString(":")
        }

        /**
         * A missing hierarchy is allowed to be completed by a later OCR frame, but two
         * explicitly different administrative hierarchies must never be treated as one address.
         */
        internal fun isSameAddressFamily(left: AddressCandidate, right: AddressCandidate): Boolean {
            val leftParts = AddressTextParser.parseParts(left.text)
            val rightParts = AddressTextParser.parseParts(right.text)
            if (leftParts == null || rightParts == null) return identity(left) == identity(right)
            return leftParts.kind == rightParts.kind &&
                (left.details.isEmpty() || right.details.isEmpty() ||
                    AddressTextParser.normalizeKey(left.details) == AddressTextParser.normalizeKey(right.details)) &&
                leftParts.mountain == rightParts.mountain &&
                AddressTextParser.normalizeKey(leftParts.name) == AddressTextParser.normalizeKey(rightParts.name) &&
                (leftParts.number == null || rightParts.number == null || leftParts.number == rightParts.number) &&
                compatibleHierarchy(leftParts.prefix, rightParts.prefix)
        }

        private fun hierarchyKey(prefix: List<String>): String = prefix
            .joinToString("/") { AddressTextParser.normalizeKey(it) }

        private fun compatibleHierarchy(left: List<String>, right: List<String>): Boolean {
            if (left.isEmpty() || right.isEmpty()) return true
            val leftTokens = left.map(AddressTextParser::normalizeKey)
            val rightTokens = right.map(AddressTextParser::normalizeKey)

            val leftProvince = leftTokens.firstOrNull(::isProvince)
            val rightProvince = rightTokens.firstOrNull(::isProvince)
            if (leftProvince != null && rightProvince != null && leftProvince != rightProvince) return false

            val leftDistricts = leftTokens.filter(::isDistrict)
            val rightDistricts = rightTokens.filter(::isDistrict)
            if (!compatiblePath(leftDistricts, rightDistricts)) return false

            val leftSubdistricts = leftTokens.filter(::isSubdistrict)
            val rightSubdistricts = rightTokens.filter(::isSubdistrict)
            return compatiblePath(leftSubdistricts, rightSubdistricts)
        }

        private fun compatiblePath(left: List<String>, right: List<String>): Boolean {
            if (left.isEmpty() || right.isEmpty()) return true
            val shorter = if (left.size <= right.size) left else right
            val longer = if (left.size <= right.size) right else left
            return longer.take(shorter.size) == shorter || longer.takeLast(shorter.size) == shorter
        }

        private fun isProvince(token: String): Boolean =
            token.endsWith("특별자치도") || token.endsWith("특별자치시") ||
                token.endsWith("광역시") || token.endsWith("특별시") || token.endsWith("도")

        private fun isDistrict(token: String): Boolean =
            token.endsWith("시") || token.endsWith("군") || token.endsWith("구")

        private fun isSubdistrict(token: String): Boolean =
            token.endsWith("읍") || token.endsWith("면") || token.endsWith("리")
    }
}
