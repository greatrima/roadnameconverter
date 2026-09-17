package kr.co.addresslens

/** Stable keys: keep user choices across application updates. */
enum class MenuButton(val preferenceKey: String) {
    REGION("menu_show_region"),
    FLASH("menu_show_flash"),
    MAP("menu_show_map"),
    CONVERT("menu_show_convert"),
    FREEZE("menu_show_freeze"),
    RECOGNIZE_SELECTION("menu_show_recognize_selection"),
    COPY_INPUT("menu_show_copy_input"),
    COPY_RESULT("menu_show_copy_result")
}
