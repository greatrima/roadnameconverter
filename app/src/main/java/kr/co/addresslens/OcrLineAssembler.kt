package kr.co.addresslens

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal data class OcrLine(val text: String, val box: FloatBox)
internal data class OcrAddressBlock(val text: String, val box: FloatBox, val requiresConfirmation: Boolean = false)

/** Spatial/structural grouping only; no sender/recipient classification or priority. */
internal object OcrLineAssembler {
    private val detail = Regex("^(?:[,.( ]|지하|\\d+\\s*(?:동|층|호)|-\\s*\\d).*|.*(?:아파트|빌라|오피스텔|타운|푸르지오|래미안|힐스테이트).*")

    /** Preserve ML Kit element boundaries rather than splitting a packed numeric token by guesswork. */
    fun assembleElements(rows: List<List<OcrLine>>, trace: MutableList<String>? = null): List<OcrAddressBlock> {
        val lines = rows.flatMap { elements ->
            val groups = mutableListOf<MutableList<OcrLine>>()
            elements.forEach { element ->
                record(trace, "요소: ${element.text} @ ${element.box}")
                val previous = groups.lastOrNull()?.lastOrNull()
                if (previous == null || element.box.left - previous.box.right > max(previous.box.height, element.box.height) * 1.8f)
                    groups += mutableListOf(element) else groups.last() += element
            }
            groups.map { group -> OcrLine(group.joinToString(" ") { it.text }, bounds(group)) }
        }
        return assemble(lines, trace)
    }

    fun assemble(lines: List<OcrLine>, trace: MutableList<String>? = null): List<OcrAddressBlock> {
        val groups = mutableListOf<MutableList<OcrLine>>()
        val uncertain = mutableSetOf<Int>()
        val barriers = mutableListOf<OcrLine>()
        for (line in lines.filter { it.text.isNotBlank() }.sortedWith(compareBy({ it.box.top }, { it.box.left }))) {
            record(trace, "줄: ${line.text} @ ${line.box}")
            if (AddressTextParser.isNonAddressLine(line.text)) {
                record(trace, "제외: 이름/연락처/우편/부서/메모 필드")
                barriers += line
                continue
            }
            val eligible = groups.indices.filter { index ->
                val group = groups[index]
                val previousBox = group.last().box
                if (barriers.any { barrier ->
                        barrier.box.top >= previousBox.bottom - 1f && barrier.box.bottom <= line.box.top + 1f &&
                            min(barrier.box.right, previousBox.right) > max(barrier.box.left, previousBox.left)
                    }) {
                    record(trace, "연결 거부: 주소 사이의 다른 정보 필드")
                    return@filter false
                }
                val decision = canJoin(group.last(), line, group.joinToString("\n") { it.text })
                record(trace, "${group.last().text} → ${line.text}: ${decision.reason}")
                decision.allowed
            }
            if (eligible.size == 1) {
                val index = eligible.single()
                groups[index] += line
                if (line.text.trim().matches(Regex("\\d{5}"))) uncertain += index
                record(trace, "연결: ${groups[index].joinToString(" / ") { it.text }}")
            } else {
                if (eligible.size > 1) record(trace, "연결 거부: 가능한 앞줄이 여러 개")
                groups += mutableListOf(line)
            }
        }
        return groups.mapIndexed { index, group ->
            OcrAddressBlock(group.joinToString("\n") { it.text }, bounds(group), index in uncertain)
        }
    }

    private data class Decision(val allowed: Boolean, val reason: String)
    private fun bounds(lines: List<OcrLine>) = FloatBox(lines.minOf { it.box.left }, lines.minOf { it.box.top },
        lines.maxOf { it.box.right }, lines.maxOf { it.box.bottom })
    private fun record(trace: MutableList<String>?, text: String) { if (trace != null && trace.size < 100) trace += text }

    private fun canJoin(previous: OcrLine, next: OcrLine, context: String): Decision {
        val a = previous.box; val b = next.box
        val size = max(a.height, b.height).coerceAtLeast(1f)
        val overlap = min(a.right, b.right) - max(a.left, b.left)
        val sameRow = abs((a.top + a.bottom - b.top - b.bottom) / 2) < min(a.height, b.height) * .55f
        if (sameRow) {
            if (b.left < a.right || b.left - a.right > size * 1.3f) return Decision(false, "가로 거리/겹침")
        } else {
            if (b.top < a.bottom - min(a.height, b.height) * .25f || b.top - a.bottom > size * 1.65f) return Decision(false, "세로 거리/읽기 순서")
            if (abs(a.left - b.left) > size * 1.2f && overlap < min(a.width, b.width) * .65f) return Decision(false, "다른 열/가로 겹침 부족")
        }
        val first = AddressTextParser.extract(context)
        val second = AddressTextParser.extract(next.text)
        if (first != null && second != null) return Decision(false, "독립된 완성 주소 2개")
        if (AddressTextParser.repairRoadAcrossSamples(previous.text, next.text) != null)
            return Decision(true, "관측된 도로명 조각/접미사")
        if (AddressTextParser.repairNumberAcrossSamples(context, next.text) != null)
            return Decision(true, "명시적 하이픈 뒤 부번")
        if (first != null) {
            val allowed = detail.matches(next.text.trim()) || (context.count { it == '(' } > context.count { it == ')' })
            return Decision(allowed, if (allowed) "상세주소/괄호 문맥" else "이미 완성된 기본주소")
        }
        if (second != null && previous.text.trim().matches(
                Regex(".*(?:아파트|빌라|오피스텔|타운|푸르지오|래미안|힐스테이트|빌딩|회관|센터)$"))) return Decision(true, "건물명 아래 기본주소")
        val tokens = previous.text.trim().split(Regex("\\s+"))
        val tail = tokens.lastOrNull().orEmpty()
        val regionOrName = tail.matches(Regex("[가-힣0-9]+(?:도|시|군|구|읍|면|동|리|가|로|길)"))
        val number = AddressTextParser.startsWithBaseNumber(next.text)
        val followingName = next.text.trim().matches(Regex("(?:[가-힣0-9]+(?:도|시|군|구|읍|면|동|리|로|길)\\s*)+"))
        if (regionOrName && (number || second != null || followingName))
            return Decision(true, if (number) "주소명 뒤 번지/건물번호 + 상세주소" else "주소 계층")
        return Decision(false, "주소 연결 근거 부족/번호 경계 불명확")
    }
}
