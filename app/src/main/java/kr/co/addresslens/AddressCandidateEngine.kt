package kr.co.addresslens

class AddressCandidateEngine(private val dictionary: AddressDictionary) {
    fun candidates(rawBlocks: List<String>, region: RegionSelection): List<AddressCandidate> {
        val parsed = AddressTextParser.extractCandidatesFromOcrSamples(rawBlocks, includePartial = true)
        return parsed.flatMap { correct(it, region) }
            .distinctBy { AddressTextParser.normalizeKey(it.text) }
            .sortedWith(
                compareByDescending<AddressCandidate> { it.completeness == CandidateCompleteness.COMPLETE }
                    .thenByDescending { it.confidence }
                    .thenBy { it.text }
            ).take(5)
    }

    fun candidate(raw: String, region: RegionSelection): AddressCandidate? =
        AddressTextParser.extractCandidates(raw).flatMap { correct(it, region) }.firstOrNull()

    private fun correct(candidate: AddressCandidate, region: RegionSelection): List<AddressCandidate> {
        val parts = AddressTextParser.parseParts(candidate.text) ?: return listOf(candidate)
        val explicitProvinceToken = parts.prefix.firstOrNull(::isProvinceToken)
        val explicitProvince = explicitProvinceToken?.let(dictionary::correctProvince)
        val districtToken = parts.prefix.lastOrNull(::isDistrictToken)
        val explicitDistrict = districtToken?.let {
            dictionary.correctDistrict(it, explicitProvince ?: region.province.takeIf(String::isNotBlank))
        }
        val resolvedProvince = explicitProvince ?: explicitDistrict?.let {
            dictionary.provinceForDistrict(it, region.province.takeIf(String::isNotBlank))
        }
        val lookupRegion = when {
            resolvedProvince != null -> RegionSelection(resolvedProvince, explicitDistrict.orEmpty(), region.localities)
            else -> region
        }
        val matches = dictionary.match(parts.name, parts.kind, lookupRegion, resolvedProvince)
        val explicitIntermediateRegions = parts.prefix.filter(::isIntermediateRegionToken)
        if (matches.isEmpty()) {
            val fallbackText = if (parts.prefix.isEmpty() && !region.isEmpty) {
                listOf(region.province, region.district, candidate.text)
                    .filter(String::isNotBlank).joinToString(" ")
            } else candidate.text
            return listOf(
                candidate.copy(
                    text = fallbackText,
                    confidence = candidate.confidence.coerceAtMost(65),
                    dictionaryCorrected = fallbackText != candidate.text
                )
            )
        }

        return matches.take(5).map { match ->
            val prefix = linkedSetOf<String>()
            // Keep different regions visible even when no default region was selected.
            prefix += match.province
            prefix += match.district
            prefix += explicitIntermediateRegions
            val number = parts.number?.let { (if (parts.mountain) "산 " else "") + it }
            val text = (prefix + match.name).joinToString(" ") + (number?.let { " $it" } ?: "")
            val corrected = match.name != parts.name || explicitProvinceToken != explicitProvince ||
                (districtToken != null && districtToken != explicitDistrict) ||
                (parts.prefix.isEmpty() && !region.isEmpty)
            candidate.copy(
                text = text.trim(),
                confidence = (100 - match.distance * 12 + if (match.localityContext) 5 else 0).coerceIn(50, 100),
                dictionaryCorrected = corrected,
                verified = false
            )
        }
    }

    private fun isProvinceToken(token: String): Boolean =
        token.endsWith("도") || token.endsWith("광역시") || token.endsWith("특별시") || token.endsWith("특별자치시")

    private fun isDistrictToken(token: String): Boolean =
        token.endsWith("시") || token.endsWith("군") || token.endsWith("구")

    private fun isIntermediateRegionToken(token: String): Boolean =
        token.endsWith("읍") || token.endsWith("면")
}
