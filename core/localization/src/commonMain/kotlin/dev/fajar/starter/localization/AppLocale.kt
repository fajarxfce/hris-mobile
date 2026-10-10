package dev.fajar.starter.localization

import androidx.compose.runtime.*
import androidx.compose.ui.text.intl.Locale
import dev.fajar.starter.localization.resources.*
import dev.fajar.starter.settings.domain.entities.AppLanguage
import org.jetbrains.compose.resources.*

val LocalAppLanguage = staticCompositionLocalOf { AppLanguage.System }

@Composable
fun appLanguageTag(): String =
    when (LocalAppLanguage.current) {
        AppLanguage.English -> "en"
        AppLanguage.Indonesian -> "id"
        AppLanguage.System -> if (Locale.current.language in setOf("id", "in")) "id" else "en"
    }

@Composable
fun appString(key: AppString, vararg args: Any): String =
    stringResource(if (appLanguageTag() == "id") key.indonesian else key.english, *args)

/** Render formatting belongs to UI infrastructure, not domain or ViewModels. */
expect fun formatNumber(value: Long, languageTag: String): String

expect fun formatDate(epochMillis: Long, languageTag: String): String

@Composable
fun activityCount(count: Int): String =
    pluralStringResource(
        if (appLanguageTag() == "id")
            dev.fajar.starter.localization.resources.Res.plurals.activity_count_id
        else dev.fajar.starter.localization.resources.Res.plurals.activity_count_en,
        count,
        formatNumber(count.toLong(), appLanguageTag()),
    )
