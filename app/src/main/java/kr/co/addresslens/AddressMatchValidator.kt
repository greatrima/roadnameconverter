package kr.co.addresslens

/** No fuzzy matching at the API boundary: OCR suggestions and verified addresses are distinct. */
object AddressMatchValidator {
    private val provinceAliases = mapOf("서울" to "서울특별시", "부산" to "부산광역시",
        "대구" to "대구광역시", "인천" to "인천광역시", "광주" to "광주광역시",
        "대전" to "대전광역시", "울산" to "울산광역시", "세종" to "세종특별자치시",
        "경기" to "경기도", "강원" to "강원특별자치도", "강원도" to "강원특별자치도",
        "충북" to "충청북도", "충남" to "충청남도", "전북" to "전북특별자치도",
        "전라북도" to "전북특별자치도", "전남" to "전라남도", "경북" to "경상북도",
        "경남" to "경상남도", "제주" to "제주특별자치도", "제주도" to "제주특별자치도")
    private val aliasTokens = Regex("(?<!\\S)(?:${provinceAliases.keys.joinToString("|") { Regex.escape(it) }})(?=\\s|$)")

    fun normalizeRegions(text: String): String = aliasTokens.replace(text) { canonicalRegion(it.value) }

    fun exact(query: String, returnedAddress: String): Boolean {
        val wanted = parts(query) ?: return false
        val actual = parts(returnedAddress) ?: return false
        if (wanted.kind != actual.kind || wanted.name != actual.name || wanted.mountain != actual.mountain ||
            wanted.number == null || actual.number == null || numberKey(wanted.number) != numberKey(actual.number)) return false
        // Every supplied region must occur in order. Omitted upper levels may be supplied by the API.
        var position = 0
        val actualPath = actual.prefix.map(::canonicalRegion)
        for (token in wanted.prefix.map(::canonicalRegion)) {
            val index = (position until actualPath.size).firstOrNull { actualPath[it] == token } ?: return false
            position = index + 1
        }
        return true
    }

    fun parts(text: String): AddressParts? = AddressTextParser.extract(normalizeRegions(text))?.let(AddressTextParser::parseParts)

    private fun canonicalRegion(value: String) = provinceAliases[value] ?: value
    private fun numberKey(value: String): List<Int?> = value.split('-').map { it.trim().toIntOrNull() }

    /** Only explicitly returned parcel numbers are related parcels; never infer one from a road. */
    fun relatedParcels(primary: String, landNumbers: String): List<String> {
        val parts = parts(primary)?.takeIf { it.kind == AddressKind.PARCEL } ?: return emptyList()
        if (!landNumbers.matches(Regex("(?:산\\s*)?\\d+(?:-\\d+)?(?:\\s*[,;]\\s*(?:산\\s*)?\\d+(?:-\\d+)?)*"))) return emptyList()
        val prefix = (parts.prefix + parts.name).joinToString(" ")
        return landNumbers.split(',', ';').filter { !parts.mountain || it.trim().startsWith("산") }.map { number ->
            val value = number.trim()
            "$prefix $value"
        }
    }
}
