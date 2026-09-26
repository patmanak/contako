package com.patmanak.contako.ui

/** Proton WebClients ACCENT_COLORS_MAP, used by its contact-group ColorPicker (2026-09-05). */
internal object EditorPresentationPolicy {
    val groupColorPalette = listOf(
        "#8080FF", "#DB60D6", "#EC3E7C", "#F78400", "#936D58",
        "#5252CC", "#A839A4", "#BA1E55", "#C44800", "#54473F",
        "#415DF0", "#179FD9", "#1DA583", "#3CBB3A", "#B4A40E",
        "#273EB2", "#0A77A6", "#0F735A", "#258723", "#807304",
    )

    fun membershipKey(option: EmailMembershipOption): String =
        "${option.contactId}:${option.emailValueId}"
}
