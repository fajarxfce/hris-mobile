package dev.fajar.starter.settings.presentation.preferences.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.localization.*
import dev.fajar.starter.settings.domain.entities.AppLanguage
import dev.fajar.starter.settings.presentation.preferences.*
import io.github.composefluent.FluentTheme
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.ArrowLeft

@Composable
fun SettingsPage(
    state: SettingsState,
    onEvent: (SettingsEvent) -> Unit,
    securitySettings: @Composable () -> Unit = {},
) {
    AppPage(
        maxWidth = 640.dp,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        topBar = {
            AppTopBar(
                appString(AppString.Settings),
                navigation = {
                    AppIconButton(
                        Icons.Regular.ArrowLeft,
                        appString(AppString.Back),
                        { onEvent(SettingsEvent.BackRequested) },
                    )
                },
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AppHeading(appString(AppString.Language), style = FluentTheme.typography.bodyStrong)
            AppCard(Modifier.fillMaxWidth().selectableGroup(), padding = 4.dp) {
                AppLanguage.entries.forEach { language ->
                    key(language) {
                        AppRadioRow(
                            appString(
                                when (language) {
                                    AppLanguage.System -> AppString.SystemLanguage
                                    AppLanguage.English -> AppString.English
                                    AppLanguage.Indonesian -> AppString.Indonesian
                                }
                            ),
                            selected = state.language == language,
                            onClick = { onEvent(SettingsEvent.LanguageSelected(language)) },
                            enabled = !state.saving,
                        )
                    }
                }
            }
        }
        if (state.error != null) {
            AppFeedback(failureText(state.error))
            AppTextButton(appString(AppString.Retry), { onEvent(SettingsEvent.ReloadRequested) })
        }
        securitySettings()
    }
}
