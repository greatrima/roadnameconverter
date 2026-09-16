package kr.co.addresslens

/** Details never enter the API query. Only structured units and explicit building context survive. */
object AddressDetails {
    private val unit = Regex("(?:지하\\s*)?\\d+(?:-\\d+)?\\s*(?:동|층|호)(?![가-힣])")
    private val building = Regex("[가-힣A-Za-z0-9]*(?:아파트|빌라|오피스텔|타운|푸르지오|래미안|자이|힐스테이트|주택|빌딩|회관|센터)")
    fun extract(raw: String, base: String): String {
        val restored = AddressTextParser.restoreStructuredText(raw)
        val parsed = AddressTextParser.parseParts(base) ?: return ""
        val flattened = restored.replace('\n', ' ')
        // Restrict detail extraction to the tail after the base number, not names/phone numbers above it.
        val number = (parsed.number ?: return "").split('-').joinToString("\\s*-\\s*") { Regex.escape(it) }
        val matched = Regex("${Regex.escape(parsed.name)}\\s*(?:산\\s*)?$number(?:\\s*번지)?")
            .find(flattened) ?: return ""
        val tail = flattened.substring(matched.range.last + 1)
        val heading = flattened.substring(0, matched.range.first)
        return (building.findAll(heading + " " + tail).map { it.value }.take(3).toList() +
            unit.findAll(tail).map { it.value.replace(Regex("\\s+"), "") }.take(4).toList())
            .distinct().joinToString(" ")
    }
}
