package kr.co.addresslens

/** A new OCR frame cannot reset the selected address's request state. */
class ScanDiagnostics {
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
    }
    fun begin(query: String) { request = query; result = ""; searchStage = "검색 중" }
    fun finish(stage: String, message: String) { searchStage = stage; result = message }
    fun reset(reason: String = "대기") { ocrStage = reason; searchStage = "대기"; request = ""; result = "" }
}
