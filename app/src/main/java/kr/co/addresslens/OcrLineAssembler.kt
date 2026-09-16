package kr.co.addresslens

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal data class OcrLine(val text: String, val box: FloatBox)
internal data class OcrAddressBlock(val text: String, val box: FloatBox)

/** Spatial/structural grouping only; no sender/recipient classification or priority. */
internal object OcrLineAssembler {
    private val irrelevant = Regex("^(?:성명|이름|전화|휴대폰|연락처|TEL|FAX|부서)\\s*[:：].*", RegexOption.IGNORE_CASE)
    private val phone = Regex("^(?:0\\d{1,2})[- ]?\\d{3,4}[- ]?\\d{4}$")
    private val detail = Regex("^(?:[,.( ]|지하|\\d+\\s*(?:동|층|호)|-\\s*\\d).*|.*(?:아파트|빌라|오피스텔|타운|푸르지오|래미안|힐스테이트).*")

    fun assemble(lines: List<OcrLine>): List<OcrAddressBlock> {
        val groups = mutableListOf<MutableList<OcrLine>>()
        for (line in lines.filter { it.text.isNotBlank() }.sortedWith(compareBy({ it.box.top }, { it.box.left }))) {
            if (irrelevant.matches(line.text.trim()) || phone.matches(line.text.trim())) continue
            val previous = groups.asReversed().firstOrNull { canJoin(it.last(), line, it.joinToString("\n") { item -> item.text }) }
            if (previous == null) groups += mutableListOf(line) else previous += line
        }
        return groups.map { group ->
            OcrAddressBlock(group.joinToString("\n") { it.text }, FloatBox(group.minOf { it.box.left },
                group.minOf { it.box.top }, group.maxOf { it.box.right }, group.maxOf { it.box.bottom }))
        }
    }

    private fun canJoin(previous: OcrLine, next: OcrLine, context: String): Boolean {
        val a = previous.box; val b = next.box
        val size = max(a.height, b.height).coerceAtLeast(1f)
        val overlap = min(a.right, b.right) - max(a.left, b.left)
        val sameRow = abs((a.top + a.bottom - b.top - b.bottom) / 2) < min(a.height, b.height) * .55f
        if (sameRow) {
            if (b.left < a.right || b.left - a.right > size * 1.3f) return false
        } else {
            if (b.top < a.bottom - min(a.height, b.height) * .25f || b.top - a.bottom > size * 1.65f) return false
            if (abs(a.left - b.left) > size * 1.2f && overlap < min(a.width, b.width) * .65f) return false
        }
        val first = AddressTextParser.extract(context)
        val second = AddressTextParser.extract(next.text)
        if (first != null && second != null) return false // Two complete addresses remain separate.
        if (first != null) return detail.matches(next.text.trim()) ||
            (context.count { it == '(' } > context.count { it == ')' })
        if (second != null && previous.text.trim().matches(
                Regex(".*(?:아파트|빌라|오피스텔|타운|푸르지오|래미안|힐스테이트|빌딩|회관|센터)$"))) return true
        val tokens = previous.text.trim().split(Regex("\\s+"))
        val tail = tokens.lastOrNull().orEmpty()
        val regionOrName = tail.matches(Regex("[가-힣0-9]+(?:도|시|군|구|읍|면|동|리|가|로|길)"))
        val number = next.text.trim().matches(Regex("(?:산\\s*)?\\d+(?:\\s*-\\s*\\d+)?(?:번지)?"))
        if (regionOrName && (number || second != null || next.text.trim().matches(Regex("[가-힣0-9]+(?:시|군|구|읍|면|동|리)")))) return true
        // Only a real observed road-suffix continuation; no guessed characters or numeric concatenation.
        return tail.matches(Regex("[가-힣]{1,12}")) && !regionOrName &&
            next.text.trim().matches(Regex("[가-힣0-9]{1,4}(?:로|길)\\s+\\d+.*"))
    }
}
