package kr.co.addresslens

/** A new OCR frame cannot reset the selected address's request state. */
class ScanDiagnostics {
    enum class CandidateStage { IDLE, AREA_CHANGED, RECOGNIZING, EXTRACTING, EMPTY, READY }
    var candidateStage = CandidateStage.IDLE
        private set
    val candidateMessage: String get() = when (candidateStage) {
        CandidateStage.IDLE -> "주소 인식을 기다리고 있습니다"
        CandidateStage.AREA_CHANGED -> "선택 영역 인식을 눌러주세요"
        CandidateStage.RECOGNIZING -> "선택 영역의 글자를 인식 중입니다"
        CandidateStage.EXTRACTING -> "인식한 글자에서 주소를 추출 중입니다"
        CandidateStage.EMPTY -> "완성된 주소 후보를 찾지 못했습니다"
        CandidateStage.READY -> "주소 후보를 확인해 주세요"
    }
    var ocrStage = "대기"
        private set
    var searchStage = "대기"
        private set
    var request = ""
        private set
    var result = ""
        private set

    fun observe(hasText: Boolean, hasAddress: Boolean) {
        ocrStage = if (!hasText) "OCR: 글자 없음" else if (!hasAddress) "주소 추출: 완성 주소 없음" else "기본주소 추출 완료"
        candidateStage = if (hasAddress) CandidateStage.READY else CandidateStage.EMPTY
    }
    fun startRecognition() { ocrStage = "글자 인식 중"; candidateStage = CandidateStage.RECOGNIZING }
    fun startExtraction() { ocrStage = "주소 추출 중"; candidateStage = CandidateStage.EXTRACTING }
    fun begin(query: String) { request = query; result = ""; searchStage = "검색 중" }
    fun finish(stage: String, message: String) { searchStage = stage; result = message }
    fun reset(reason: String = "대기") {
        ocrStage = reason; searchStage = "대기"; request = ""; result = ""
        candidateStage = if (reason == "영역 변경") CandidateStage.AREA_CHANGED else CandidateStage.IDLE
    }
}
