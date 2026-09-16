package kr.co.addresslens

enum class AddressKind { PARCEL, ROAD, UNKNOWN }
enum class CandidateCompleteness { PARTIAL, COMPLETE }

data class AddressCandidate(
    val text: String,
    val kind: AddressKind,
    val completeness: CandidateCompleteness = CandidateCompleteness.COMPLETE,
    val confidence: Int = 100,
    val dictionaryCorrected: Boolean = false,
    val verified: Boolean = false,
    val details: String = "",
    val manualOnly: Boolean = false,
    val alternativeTarget: String = "",
    val reviewReason: String = ""
)

data class AddressParts(
    val prefix: List<String>,
    val name: String,
    val number: String?,
    val kind: AddressKind,
    val mountain: Boolean = false
)

/** Structure-only parsing. Existence and spelling correction are handled by AddressCandidateEngine. */
object AddressTextParser {
    private const val PROVINCE_SUFFIX = "(?:특별자치도|특별자치시|광역시|특별시|도)"
    private const val DISTRICT_SUFFIX = "(?:시|군|구)"
    private const val LOCALITY_SUFFIX = "(?:읍|면|동|가|리)"
    private const val ROAD_SUFFIX = "(?:대로|로|길)"
    private const val NUMBER = "\\d{1,5}(?:\\s*-\\s*\\d{1,5})?"
    private const val PREFIX = "(?:[가-힣0-9]+(?:$PROVINCE_SUFFIX|$DISTRICT_SUFFIX|읍|면)\\s+)*"

    private val parcelAddress = Regex(
        "$PREFIX([가-힣][가-힣0-9]*$LOCALITY_SUFFIX)\\s*(산\\s*)?($NUMBER)(?:\\s*번지)?(?!\\d|\\s*(?:-|동|층|호))"
    )
    private val roadAddress = Regex(
        "$PREFIX([가-힣0-9]+$ROAD_SUFFIX)\\s*($NUMBER)(?!\\d|\\s*(?:-|동|층|호))"
    )
    private val addressNameOnly = Regex(
        "^(?:[가-힣0-9]+(?:$PROVINCE_SUFFIX|$DISTRICT_SUFFIX|읍|면)\\s+)*" +
            "(?:[가-힣][가-힣0-9]*$LOCALITY_SUFFIX|[가-힣0-9]+$ROAD_SUFFIX)$"
    )
    private val numberOnly = Regex("^(?:산\\s*)?\\d{1,5}(?:\\s*-\\s*\\d{1,5})?(?:\\s*번지)?$")
    private val subNumberOnly = Regex("^-\\s*\\d{1,5}$")
    private val phoneLike = Regex("(?:01[016789]|0\\d{1,2})[- ]?\\d{3,4}[- ]?\\d{4}")
    private val nonAddressLabel = Regex("^(?:성명|이름|전화|휴대폰|연락처|TEL|FAX|우편번호|우편|ZIP|부서|부서명|배송\\s*메모|배송\\s*요청사항)(?:(?:\\s*[:：]\\s*|\\s+).*|$)", RegexOption.IGNORE_CASE)

    internal fun isNonAddressLine(text: String): Boolean =
        nonAddressLabel.matches(text.trim()) || phoneLike.matches(text.trim()) ||
            Regex("^\\(?우\\)?\\s*[:：]?\\s*\\d{5}$").matches(text.trim())

    internal fun startsWithBaseNumber(text: String): Boolean {
        val normalized = normalizeOcrText(text).trim()
        if (isNonAddressLine(text) || phoneLike.containsMatchIn(normalized)) return false
        return Regex("^(?:산\\s*)?$NUMBER(?:\\s*번지)?(?!\\d|\\s*(?:-|동|층|호))(?=\\s|[,.(]|$)")
            .containsMatchIn(normalized)
    }

    fun extract(rawText: String): String? = extractCandidate(rawText)?.text

    fun extractCandidate(rawText: String): AddressCandidate? =
        extractCandidates(rawText).firstOrNull { it.completeness == CandidateCompleteness.COMPLETE }

    fun extractCandidates(rawText: String, includePartial: Boolean = false): List<AddressCandidate> {
        if (rawText.isBlank()) return emptyList()
        val normalized = normalizeOcrText(rawText)
        val variants = LinkedHashSet<String>()
        splitBlocks(normalized).forEach { block ->
            val lines = cleanLines(block)
            variants += restoreStructuredText(block).replace('\n', ' ')
            variants.addAll(lines)
            variants += lines.joinToString(" ")
            addAdjacentLineRepairs(lines, variants)
        }
        return rankedCandidates(variants, includePartial).map { it.copy(details = AddressDetails.extract(rawText, it.text)) }
    }

    fun extractFromOcrSamples(samples: List<String>): AddressCandidate? =
        extractCandidatesFromOcrSamples(samples).firstOrNull {
            it.completeness == CandidateCompleteness.COMPLETE
        }

    fun extractCandidatesFromOcrSamples(
        samples: List<String>,
        includePartial: Boolean = false
    ): List<AddressCandidate> {
        val normalizedSamples = samples.filter(String::isNotBlank).map(::normalizeOcrText)
        if (normalizedSamples.isEmpty()) return emptyList()

        val variants = LinkedHashSet<String>()
        val lines = mutableListOf<String>()
        normalizedSamples.forEach { sample ->
            splitBlocks(sample).forEach { block ->
                val blockLines = cleanLines(block)
                variants += restoreStructuredText(block).replace('\n', ' ')
                variants += blockLines.joinToString("\n")
                variants += restoreStructuredText(block).replace('\n', ' ')
                variants.addAll(blockLines)
                variants += blockLines.joinToString(" ")
                addAdjacentLineRepairs(blockLines, variants)
                lines += blockLines
            }
        }

        // OCR may emit the two halves of one printed line as adjacent samples. Repair only
        // an unfinished Hangul tail followed immediately by a short road-suffix fragment and
        // a base number. Keeping this adjacency- and shape-bound avoids combining unrelated
        // sender/receiver address blocks or prepending arbitrary text to a complete road name.
        for (index in 0 until normalizedSamples.lastIndex) {
            val leftLines = cleanLines(normalizedSamples[index])
            val rightLines = cleanLines(normalizedSamples[index + 1])
            val left = leftLines.lastOrNull() ?: continue
            val right = rightLines.firstOrNull() ?: continue
            repairRoadAcrossSamples(left, right)?.let(variants::add)
        }

        // Cross-frame repair is deliberately narrow: only an address-name and a number,
        // or a recognized base number and its hyphen suffix may be joined.
        lines.distinct().filter(addressNameOnly::matches).forEach { name ->
            lines.distinct().filter(numberOnly::matches).forEach { number -> variants += "$name $number" }
        }
        lines.distinct().forEach { possibleBase ->
            val base = extractCandidate(possibleBase)
            if (base != null && '-' !in base.text && normalizeKey(base.text) == normalizeKey(possibleBase)) {
                lines.distinct().filter(subNumberOnly::matches).forEach { suffix ->
                    variants += "${base.text}$suffix"
                }
            }
        }
        return rankedCandidates(variants, includePartial).map { candidate ->
            candidate.copy(details = normalizedSamples.firstNotNullOfOrNull {
                AddressDetails.extract(it, candidate.text).takeIf(String::isNotBlank)
            }.orEmpty())
        }
    }

    internal fun repairRoadAcrossSamples(left: String, right: String): String? {
        val normalizedLeft = normalizeOcrText(left).trim()
        val leftMatch = Regex("^(?:(?:[가-힣0-9]+(?:$PROVINCE_SUFFIX|$DISTRICT_SUFFIX|읍|면))\\s+)*([가-힣][가-힣0-9]{0,24})$")
            .matchEntire(normalizedLeft) ?: return null
        val unfinishedName = leftMatch.groupValues[1]
        if (Regex("(?:$PROVINCE_SUFFIX|$DISTRICT_SUFFIX|$LOCALITY_SUFFIX|$ROAD_SUFFIX)$")
                .containsMatchIn(unfinishedName)) {
            return null
        }
        val continuation = Regex("^([가-힣0-9]{0,4}(?:대로|로|길))\\s*($NUMBER)(?!\\d|\\s*(?:-|동|층|호))(?=\\s|\\(|,|$)")
            .find(right) ?: return null
        // A complete-looking road word is not treated as a continuation. Typical split pieces
        // start with a suffix-bearing short fragment such as '동로' or '로12길'.
        val fragment = continuation.groupValues[1]
        if (fragment.length > 4 && !fragment.startsWith("로")) return null
        return "$normalizedLeft${fragment} ${continuation.groupValues[2]}" + right.substring(continuation.range.last + 1)
    }

    internal fun repairNumberAcrossSamples(left: String, right: String): String? {
        val before = normalizeOcrText(left).trimEnd()
        val after = normalizeOcrText(right).trimStart()
        if (!before.endsWith('-') || !startsWithBaseNumber(after) || after.startsWith("산")) return null
        val base = parseParts(before.dropLast(1)) ?: return null
        if (base.number == null || '-' in base.number) return null
        return before + after
    }

    fun parseParts(address: String): AddressParts? {
        val formatted = format(address)
        roadAddress.find(formatted)?.takeIf { normalizeKey(it.value) == normalizeKey(formatted) }?.let { match ->
            val name = match.groupValues[1]
            val number = match.groupValues[2].replace(Regex("\\s+"), "")
            return AddressParts(prefixTokens(match.value, name), name, number, AddressKind.ROAD)
        }
        parcelAddress.find(formatted)?.takeIf { normalizeKey(it.value) == normalizeKey(formatted) }?.let { match ->
            val name = match.groupValues[1]
            val mountain = match.groupValues[2].isNotBlank()
            val number = match.groupValues[3].replace(Regex("\\s+"), "")
            return AddressParts(prefixTokens(match.value, name), name, number, AddressKind.PARCEL, mountain)
        }
        if (addressNameOnly.matches(formatted)) {
            val tokens = formatted.split(Regex("\\s+"))
            val name = tokens.last()
            val kind = if (name.endsWith("대로") || name.endsWith("로") || name.endsWith("길")) {
                AddressKind.ROAD
            } else AddressKind.PARCEL
            return AddressParts(tokens.dropLast(1), name, null, kind)
        }
        return null
    }

    fun classify(address: String): AddressKind = parseParts(address)?.kind ?: AddressKind.UNKNOWN

    /** Reconstructs only strong same-block line continuations; the untouched OCR source stays separate. */
    fun restoreStructuredText(rawText: String): String = splitBlocks(normalizeOcrText(rawText))
        .joinToString("\n\n") { block ->
            val lines = cleanLines(block)
            if (lines.isEmpty()) return@joinToString ""
            val restored = mutableListOf(lines.first())
            lines.drop(1).forEach { line ->
                val previous = restored.last()
                val numberRepair = repairNumberAcrossSamples(previous, line)
                if (numberRepair != null) {
                    restored[restored.lastIndex] = numberRepair
                } else if (shouldJoinWithoutSpace(previous, line)) {
                    restored[restored.lastIndex] = previous + line
                } else restored += line
            }
            restored.joinToString("\n")
        }

    fun normalizeKey(address: String): String = address
        .replace(Regex("[‐‑‒–—−﹣－]"), "-")
        .replace(Regex("\\s+"), "")
        .lowercase()

    private fun rankedCandidates(texts: Iterable<String>, includePartial: Boolean): List<AddressCandidate> {
        val complete = texts.asSequence().flatMap { text ->
            sequenceOf(
                parcelAddress.findAll(text).map { AddressCandidate(format(it.value), AddressKind.PARCEL) },
                roadAddress.findAll(text).map { match ->
                    val number = match.groupValues[2]
                    AddressCandidate(format(match.value.dropLast(number.length).trimEnd() + " " + number), AddressKind.ROAD)
                }
            ).flatten()
        }.filterNot { phoneLike.containsMatchIn(it.text) }
            .distinctBy { normalizeKey(it.text) }
            .toMutableList()

        val filtered = complete.filter { candidate ->
            val key = normalizeKey(candidate.text)
            complete.none { other ->
                other !== candidate && other.kind == candidate.kind &&
                    normalizeKey(other.text).let { it.length > key.length && (it.startsWith(key) || it.endsWith(key)) }
            }
        }.toMutableList()

        if (includePartial) {
            texts.asSequence().map { it.trim() }.filter(addressNameOnly::matches).forEach { text ->
                val parts = parseParts(text) ?: return@forEach
                if (filtered.none { normalizeKey(it.text).startsWith(normalizeKey(text)) }) {
                    filtered += AddressCandidate(
                        text = format(text),
                        kind = parts.kind,
                        completeness = CandidateCompleteness.PARTIAL,
                        confidence = 55
                    )
                }
            }
        }
        return filtered.distinctBy { normalizeKey(it.text) }
            .sortedWith(
                compareByDescending<AddressCandidate> { it.completeness == CandidateCompleteness.COMPLETE }
                    .thenByDescending { it.text.length }
                    .thenBy { it.text }
            )
    }

    private fun cleanLines(block: String): List<String> = block.lineSequence()
        .map { it.replace(Regex("\\s+"), " ").trim() }
        .filter { it.isNotEmpty() && !isNonAddressLine(it) }
        .toList()

    private fun addAdjacentLineRepairs(lines: List<String>, variants: MutableSet<String>) {
        for (index in 0 until lines.lastIndex) {
            val first = lines[index]
            val second = lines[index + 1]
            variants += "$first $second"
            if (addressNameOnly.matches(first) && (numberOnly.matches(second) || subNumberOnly.matches(second))) {
                variants += first + if (subNumberOnly.matches(second)) second else " $second"
            }
            // A road word split at a line boundary, e.g. "광덕" / "동로 25".
            repairRoadAcrossSamples(first, second)?.let(variants::add)
            repairNumberAcrossSamples(first, second)?.let(variants::add)
        }
    }

    private fun shouldJoinWithoutSpace(previous: String, next: String): Boolean {
        val openParentheses = previous.count { it == '(' } > previous.count { it == ')' }
        val roadContinuation = repairRoadAcrossSamples(previous, next) != null
        val detailNumberContinuation = previous.matches(Regex(".*(?:\\d+\\s*(?:동|층)|지하)\\s*\\d{1,2}$")) &&
            next.matches(Regex("^\\d{2,4}호.*"))
        val administrativeContinuation = previous.matches(Regex(".*[가-힣]$")) &&
            next.matches(Regex("^(?:도|시|군|구|읍|면|동|리)(?:\\s+|\\d).*")) &&
            !previous.substringAfterLast(' ').matches(Regex(".*(?:$PROVINCE_SUFFIX|$DISTRICT_SUFFIX|$LOCALITY_SUFFIX|$ROAD_SUFFIX)$"))
        return roadContinuation || administrativeContinuation || detailNumberContinuation ||
            (openParentheses && previous.matches(Regex(".*[가-힣]$")) && next.matches(Regex("^[가-힣].*")))
    }

    private fun splitBlocks(text: String): List<String> = text.lineSequence()
        .joinToString("\n") { if (isNonAddressLine(it)) "\n" else it }
        .split(Regex("\\n\\s*\\n+"))

    private fun prefixTokens(value: String, name: String): List<String> =
        value.substringBefore(name).trim().split(Regex("\\s+")).filter(String::isNotBlank)

    private fun normalizeOcrText(rawText: String): String = AddressMatchValidator.normalizeRegions(rawText)
        .replace(Regex("[‐‑‒–—−﹣－]"), "-")
        .replace(Regex("(?i)(지번|도로명)?\\s*주소\\s*[:：]?"), " ")
        .replace(Regex("[|,_·•]"), " ")
        .replace('（', '(')
        .replace('）', ')')

    private fun format(value: String): String = value
        .replace(Regex("\\s*-\\s*"), "-")
        .replace(Regex("\\s+"), " ")
        .replace(Regex("(읍|면|동|가|리)(?=(?:산)?\\d)"), "\$1 ")
        .replace(Regex("산(?=\\d)"), "산 ")
        .replace(Regex("\\s*번지$"), "")
        .trim()
}
