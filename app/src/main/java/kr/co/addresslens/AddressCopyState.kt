package kr.co.addresslens

/** Only a successful conversion supplies a copyable result; never copy status text. */
class AddressCopyState {
    private var input = ""
    var convertedAddress: String? = null
        set(value) { field = value?.trim()?.takeIf(String::isNotEmpty) }

    val inputAddress: String? get() = input.trim().takeIf(String::isNotEmpty)

    fun updateInput(value: String) {
        if (input != value) convertedAddress = null
        input = value
    }
}
