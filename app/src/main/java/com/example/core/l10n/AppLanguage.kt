package com.example.core.l10n

import androidx.compose.ui.unit.LayoutDirection
import java.util.Locale

enum class AppLanguage(
    val code: String,
    val locale: Locale,
    val layoutDirection: LayoutDirection
) {
    DARIJA("ary", Locale.ROOT, LayoutDirection.Rtl),
    ARABIC("ar", Locale("ar"), LayoutDirection.Rtl),
    FRENCH("fr", Locale("fr"), LayoutDirection.Ltr);

    val isRtl: Boolean get() = layoutDirection == LayoutDirection.Rtl

    companion object {
        fun fromCode(code: String?): AppLanguage =
            entries.firstOrNull { it.code == code } ?: DARIJA
    }
}
