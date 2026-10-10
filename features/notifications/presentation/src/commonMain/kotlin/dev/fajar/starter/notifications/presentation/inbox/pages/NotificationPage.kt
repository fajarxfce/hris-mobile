package dev.fajar.starter.notifications.presentation.inbox.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.localization.*
import dev.fajar.starter.notifications.presentation.inbox.*
import io.github.composefluent.FluentTheme

@Composable
fun NotificationPage(state: NotificationState, onEvent: (NotificationEvent) -> Unit) {
    AppLazyPage {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                AppHeading(appString(AppString.Notifications), style = FluentTheme.typography.title)
                AppButton(
                    appString(AppString.Back),
                    { onEvent(NotificationEvent.BackRequested) },
                    primary = false,
                )
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AppButton(
                    appString(AppString.AllowNotifications),
                    { onEvent(NotificationEvent.PermissionRequested) },
                    enabled = !state.busy,
                )
                AppButton(
                    appString(AppString.TestNotification),
                    { onEvent(NotificationEvent.TestRequested) },
                    enabled = !state.busy,
                    primary = false,
                )
                AppButton(
                    appString(AppString.CopyToken),
                    { onEvent(NotificationEvent.TokenRequested) },
                    enabled = !state.busy,
                    primary = false,
                )
                AppButton(
                    appString(AppString.ClearInbox),
                    { onEvent(NotificationEvent.ClearRequested) },
                    enabled = !state.busy && state.messages.isNotEmpty(),
                    primary = false,
                )
            }
        }
        if (state.status != null) item { AppText(appString(state.status)) }
        if (state.error != null)
            item {
                AppFeedback(failureText(state.error))
                AppButton(
                    appString(AppString.Retry),
                    { onEvent(NotificationEvent.Started) },
                    primary = false,
                )
            }
        if (state.loading) item { AppLoading() }
        else if (state.messages.isEmpty()) item { AppText(appString(AppString.EmptyInbox)) }
        items(state.messages, key = { it.id }) { message ->
            AppCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppText(message.title, style = FluentTheme.typography.bodyStrong)
                    AppText(message.body)
                    if (!message.read) AppBadge(appString(AppString.Unread))
                    AppButton(
                        appString(AppString.Open),
                        { onEvent(NotificationEvent.MessageOpened(message)) },
                        primary = false,
                    )
                }
            }
        }
    }
}
