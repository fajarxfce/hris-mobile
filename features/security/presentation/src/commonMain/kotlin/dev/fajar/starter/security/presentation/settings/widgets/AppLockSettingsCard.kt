package dev.fajar.starter.security.presentation.settings.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.localization.*
import dev.fajar.starter.security.presentation.settings.*
import io.github.composefluent.FluentTheme

@Composable
fun AppLockSettingsCard(state: LockSettingsState, onEvent: (LockSettingsEvent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AppHeading(appString(AppString.Security), style = FluentTheme.typography.bodyStrong)
        AppCard(Modifier.fillMaxWidth(), padding = 4.dp) {
            AppSwitchRow(
                title = appString(AppString.AppLock),
                supportingText =
                    appString(
                        if (state.available) AppString.AppLockDescription
                        else AppString.DeviceAuthenticationUnavailable
                    ),
                checked = state.enabled,
                enabled = state.available && !state.saving,
                onCheckedChange = { onEvent(LockSettingsEvent.EnabledChanged(it)) },
            )
        }
        if (state.failure != null) AppFeedback(appString(AppString.DeviceAuthenticationFailed))
    }
}
