package com.example.core.l10n

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import com.example.R

@Immutable
class L10nStrings(
    val language: AppLanguage,
    private val res: Resources
) {
    fun get(@StringRes id: Int): String = res.getString(id)

    fun format(@StringRes id: Int, vararg args: Any): String = res.getString(id, *args)

    fun unitLabel(unitKey: String): String = when (unitKey.lowercase()) {
        "kg" -> get(R.string.unit_kg)
        "ton" -> get(R.string.unit_ton)
        "liter" -> get(R.string.unit_liter)
        "piece" -> get(R.string.unit_piece)
        "head" -> get(R.string.unit_head)
        "crate" -> get(R.string.unit_crate)
        else -> unitKey
    }

    fun localizedName(darija: String, ar: String, fr: String): String = when (language) {
        AppLanguage.DARIJA -> darija
        AppLanguage.ARABIC -> ar
        AppLanguage.FRENCH -> fr
    }

    companion object {
        fun create(context: Context, language: AppLanguage): L10nStrings {
            val config = Configuration(context.resources.configuration)
            config.setLocale(language.locale)
            val localizedContext = context.createConfigurationContext(config)
            return L10nStrings(language, localizedContext.resources)
        }
    }
}

val LocalL10n = staticCompositionLocalOf<L10nStrings> {
    error("LocalL10n not provided")
}

@Composable
fun l10n(@StringRes id: Int): String = LocalL10n.current.get(id)
