package dev.fajar.starter.settings.presentation.preferences.pages

import androidx.compose.runtime.Composable
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.localization.*
import dev.fajar.starter.security.domain.access.entities.Permission
import dev.fajar.starter.settings.domain.entities.AppLanguage
import dev.fajar.starter.settings.presentation.preferences.*
import io.github.composefluent.FluentTheme

@Composable
fun SettingsPage(
    state: SettingsState,
    onEvent: (SettingsEvent) -> Unit,
    securitySettings: @Composable () -> Unit = {},
) {
    AppPage {
        AppHeading(appString(AppString.Settings), style = FluentTheme.typography.title)
        AppCard {
            AppText(appString(AppString.AccountAccess), style = FluentTheme.typography.bodyStrong)
            AppText(appString(AppString.AccountRoles, state.roles.joinToString(", ")))
            state.permissions.forEach { permission ->
                AppText(
                    appString(
                        when (permission) {
                            Permission.SaveActivity -> AppString.PermissionSaveActivity
                            Permission.UploadFile -> AppString.PermissionUploadFile
                            Permission.DownloadFile -> AppString.PermissionDownloadFile
                        }
                    )
                )
            }
            if (state.accessError != null) AppFeedback(failureText(state.accessError))
            AppButton(
                appString(AppString.RefreshAccess),
                { onEvent(SettingsEvent.AccessRefreshRequested) },
                enabled = !state.loadingAccess,
                primary = false,
            )
        }
        securitySettings()
        AppText(appString(AppString.LanguageDescription))
        AppLanguage.entries.forEach { language ->
            AppButton(
                appString(
                    when (language) {
                        AppLanguage.System -> AppString.SystemLanguage
                        AppLanguage.English -> AppString.English
                        AppLanguage.Indonesian -> AppString.Indonesian
                    }
                ),
                { onEvent(SettingsEvent.LanguageSelected(language)) },
                primary = state.language == language,
                enabled = !state.saving,
            )
        }
        if (state.error != null) {
            AppFeedback(failureText(state.error))
            AppButton(appString(AppString.Retry), { onEvent(SettingsEvent.ReloadRequested) })
        }
        AppButton(
            appString(AppString.Back),
            { onEvent(SettingsEvent.BackRequested) },
            primary = false,
        )
    }
}
