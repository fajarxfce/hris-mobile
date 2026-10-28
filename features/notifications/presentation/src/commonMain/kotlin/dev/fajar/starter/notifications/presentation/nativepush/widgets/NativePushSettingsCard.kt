package dev.fajar.starter.notifications.presentation.nativepush.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.localization.*
import dev.fajar.starter.notifications.domain.entities.NotificationAccess
import dev.fajar.starter.notifications.presentation.nativepush.*
import io.github.composefluent.FluentTheme

@Composable
fun NativePushSettingsCard(state: NativePushState, onEvent: (NativePushEvent) -> Unit) {
    val status = state.status
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AppHeading(appString(AppString.Notifications), style = FluentTheme.typography.bodyStrong)
        AppCard(Modifier.fillMaxWidth(), padding = 4.dp) {
            AppSwitchRow(
                title = appString(AppString.NativePushTitle),
                supportingText =
                    appString(
                        if (status?.supported == false) AppString.NativePushUnavailable
                        else AppString.NativePushDescription
                    ),
                checked = status?.enabled == true,
                enabled = status?.supported == true && state.sessionId != null && !state.busy,
                onCheckedChange = {
                    onEvent(
                        if (it) NativePushEvent.EnableRequested
                        else NativePushEvent.DisableRequested
                    )
                },
            )
        }
        if (status?.supported == true) {
            AppText(
                appString(
                    when {
                        status.pending -> AppString.NativePushPending
                        status.access == NotificationAccess.Denied -> AppString.NativePushPermission
                        status.registered -> AppString.NativePushEnabled
                        status.enabled -> AppString.NativePushPending
                        else -> AppString.NativePushDisabled
                    }
                )
            )
            if (state.busy) AppLoading()
            if (state.failure != null) AppFeedback(failureText(state.failure))
            if (state.failure != null || status.pending)
                AppButton(
                    appString(AppString.Retry),
                    enabled = !state.busy,
                    onClick = { onEvent(NativePushEvent.RetryRequested) },
                )
        }
        if (status == null && state.failure != null) {
            AppFeedback(failureText(state.failure))
            AppButton(
                appString(AppString.Retry),
                enabled = !state.busy,
                onClick = { onEvent(NativePushEvent.RetryRequested) },
            )
        }
    }
}
