package kr.co.addresslens

/** Main-thread state: only the current, still-pending conversion may publish a result. */
class ConversionRequestGate {
    private var generation = 0L
    var pending = false
        private set

    fun begin(): Long {
        pending = true
        return ++generation
    }

    fun invalidate() {
        generation++
        pending = false
    }

    fun finish(request: Long): Boolean {
        if (!pending || request != generation) return false
        pending = false
        return true
    }
}
