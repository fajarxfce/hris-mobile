package dev.fajar.starter.auth.presentation.sessions.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.auth.presentation.sessions.SessionsEvent
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.identity.domain.entities.DeviceSession
import dev.fajar.starter.localization.*
import io.github.composefluent.FluentTheme
import kotlin.time.Instant

@Composable
fun SessionCard(
    session: DeviceSession,
    current: Boolean,
    confirming: Boolean,
    working: Boolean,
    onEvent: (SessionsEvent) -> Unit,
) {
    AppCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AppText(session.deviceName, style = FluentTheme.typography.subtitle)
            if (current) AppText(appString(AppString.ThisDevice), color = AppColors.muted)
            AppText(
                appString(
                    AppString.SessionCreated,
                    Instant.fromEpochMilliseconds(session.createdAtEpochMillis).toString(),
                ),
                color = AppColors.muted,
            )
            if (!current) {
                if (confirming) {
                    AppText(appString(AppString.EndSessionConfirmation))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AppButton(
                            appString(AppString.Cancel),
                            { onEvent(SessionsEvent.RevocationCancelled) },
                            enabled = !working,
                            primary = false,
                        )
                        AppButton(
                            appString(AppString.ConfirmEndSession),
                            { onEvent(SessionsEvent.RevocationConfirmed) },
                            loading = working,
                        )
                    }
                } else {
                    AppButton(
                        appString(AppString.EndSession),
                        { onEvent(SessionsEvent.RevocationRequested(session.id)) },
                        enabled = !working,
                        primary = false,
                    )
                }
            }
        }
    }
}
