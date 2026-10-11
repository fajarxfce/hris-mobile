package dev.fajar.starter.inbox.presentation.detail.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.inbox.presentation.detail.*
import dev.fajar.starter.inbox.presentation.detail.widgets.InboxActions
import dev.fajar.starter.localization.*
import io.github.composefluent.FluentTheme
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.ArrowLeft
import io.github.composefluent.icons.regular.ArrowSync

@Composable
fun InboxItemPage(state: InboxItemState, onEvent: (InboxItemEvent) -> Unit) {
    val failure = state.failure
    AppLazyPage(
        spacing = 20.dp,
        maxWidth = 720.dp,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        topBar = {
            AppTopBar(
                title = appString(AppString.Inbox),
                navigation = {
                    AppIconButton(
                        Icons.Regular.ArrowLeft,
                        appString(AppString.Back),
                        { onEvent(InboxItemEvent.BackRequested) },
                    )
                },
                actions = {
                    AppIconButton(
                        Icons.Regular.ArrowSync,
                        appString(AppString.Refresh),
                        { onEvent(InboxItemEvent.RefreshRequested) },
                        enabled = !state.syncing && !state.working,
                    )
                },
            )
        },
    ) {
        if (!state.hasSnapshot && (state.syncing || state.loading))
            item(key = "loading") { AppLoading() }
        if (failure != null)
            item(key = "failure") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppFeedback(failureText(failure))
                    if (state.needsVerification)
                        AppButton(
                            appString(AppString.VerifyAccount),
                            { onEvent(InboxItemEvent.VerificationRequested) },
                        )
                }
            }
        if (state.pending)
            item(key = "pending") {
                AppText(appString(AppString.InboxAwaitingSync), color = AppColors.muted)
            }
        state.item?.let { message ->
            item(key = "title") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppHeading(message.title, style = FluentTheme.typography.subtitle)
                    AppText(
                        formatDate(message.deliveredAt.toEpochMilliseconds(), appLanguageTag()),
                        color = AppColors.muted,
                    )
                    if (message.acknowledgedAt != null)
                        AppBadge(appString(AppString.InboxAcknowledged))
                    else if (message.acknowledgementRequired)
                        AppBadge(appString(AppString.InboxAcknowledgementRequired))
                    else if (message.readAt != null) AppBadge(appString(AppString.InboxRead))
                }
            }
            item(key = "body") { SelectionContainer { AppText(message.body) } }
        }
        if (state.hasSnapshot && state.item == null)
            item(key = "unavailable") {
                AppText(appString(AppString.InboxMessageUnavailable), color = AppColors.muted)
            }
        item(key = "actions") { InboxActions(state, onEvent) }
    }
}
