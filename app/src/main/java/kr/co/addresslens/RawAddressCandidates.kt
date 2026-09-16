package kr.co.addresslens

/** Fast, structure-only path. No dictionary lookup or inferred spelling changes. */
object RawAddressCandidates {
    internal fun fromSpatialBlocks(blocks: List<OcrAddressBlock>, region: RegionSelection): List<AddressCandidate> = blocks
        .flatMap { block -> fromBlocks(listOf(block.text), region).map { it.copy(manualOnly = it.manualOnly || block.requiresConfirmation,
            reviewReason = if (block.requiresConfirmation) "우편번호/번지 확인 필요" else it.reviewReason) } }
        .distinctBy(CandidateTracker::identity).take(5)

    fun fromBlocks(blocks: List<String>, region: RegionSelection): List<AddressCandidate> = blocks
        .flatMap { block -> AddressTextParser.extractCandidates(block, includePartial = true).map { candidate ->
            val number = AddressTextParser.parseParts(candidate.text)?.number
            // A five-digit number on its own line may be a postal code. Keep it, but ask for confirmation.
            if (number != null && number.matches(Regex("\\d{5}")) && block.lineSequence().any { it.trim() == number })
                candidate.copy(manualOnly = true, reviewReason = "우편번호/번지 확인 필요") else candidate
        } }
        .map { withDefaultRegion(it, region) }
        .distinctBy(CandidateTracker::identity)
        .take(5)

    fun withDefaultRegion(candidate: AddressCandidate, region: RegionSelection): AddressCandidate {
        if (region.isEmpty) return candidate
        val parts = AddressTextParser.parseParts(candidate.text) ?: return candidate
        val configured = AddressMatchValidator.normalizeRegions(region.displayName()).split(' ').filter(String::isNotBlank)
        val explicit = parts.prefix
        val explicitProvince = explicit.firstOrNull(::isProvince)
        if (explicitProvince != null && explicitProvince != configured.firstOrNull()) return candidate
        val districts = explicit.filter { !isProvince(it) && (it.endsWith("시") || it.endsWith("군") || it.endsWith("구")) }
        // Only supplement a compatible path; never attach the selected province to an unrelated city.
        var position = 0
        for (district in districts) {
            val index = (position until configured.size).firstOrNull { configured[it] == district } ?: return candidate
            position = index + 1
        }
        val prefix = (configured + explicit.filterNot { it in configured }).joinToString(" ")
        val number = parts.number?.let { " ${if (parts.mountain) "산 " else ""}$it" }.orEmpty()
        return candidate.copy(text = "$prefix ${parts.name}$number".trim())
    }

    private fun isProvince(token: String) = token.endsWith("도") || token.endsWith("특별시") ||
        token.endsWith("광역시") || token.endsWith("특별자치시")
}
