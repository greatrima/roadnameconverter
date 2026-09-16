package kr.co.addresslens

/** Fast, structure-only path. No dictionary lookup or inferred spelling changes. */
object RawAddressCandidates {
    fun fromBlocks(blocks: List<String>, region: RegionSelection): List<AddressCandidate> = blocks
        .flatMap { AddressTextParser.extractCandidates(it, includePartial = true) }
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
