package dev.fajar.starter.security.presentation.settings.widgets

import androidx.compose.runtime.Composable
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.localization.*
import dev.fajar.starter.security.presentation.settings.*
import io.github.composefluent.FluentTheme

@Composable
fun AppLockSettingsCard(state: LockSettingsState, onEvent: (LockSettingsEvent) -> Unit) {
    AppCard {
        AppText(appString(AppString.AppLock), style = FluentTheme.typography.bodyStrong)
        AppText(
            appString(
                if (state.available) AppString.AppLockDescription
                else AppString.DeviceAuthenticationUnavailable
            )
        )
        if (state.available)
            AppButton(
                appString(if (state.enabled) AppString.DisableAppLock else AppString.EnableAppLock),
                { onEvent(LockSettingsEvent.EnabledChanged(!state.enabled)) },
                enabled = !state.saving,
                primary = false,
            )
        if (state.failure != null) AppFeedback(appString(AppString.DeviceAuthenticationFailed))
    }
}
