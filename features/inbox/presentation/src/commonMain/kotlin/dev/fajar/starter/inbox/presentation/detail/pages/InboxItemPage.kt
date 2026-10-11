package dev.fajar.starter.inbox.presentation.detail.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.inbox.presentation.detail.*
import dev.fajar.starter.localization.*
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.ArrowLeft

@Composable
fun InboxItemPage(state: InboxItemState, onEvent: (InboxItemEvent) -> Unit) {
    val failure = state.failure
    AppLazyPage {
        item(key = "heading") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AppIconButton(
                    Icons.Regular.ArrowLeft,
                    appString(AppString.Back),
                    { onEvent(InboxItemEvent.BackRequested) },
                )
                AppText(appString(AppString.Inbox), Modifier.weight(1f), color = AppColors.muted)
                AppButton(
                    appString(if (state.syncing) AppString.Loading else AppString.Refresh),
                    { onEvent(InboxItemEvent.RefreshRequested) },
                    enabled = !state.syncing,
                    primary = false,
                )
            }
        }
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
                    AppHeading(message.title)
                    AppText(
                        formatDate(message.deliveredAt.toEpochMilliseconds(), appLanguageTag()),
                        color = AppColors.muted,
                    )
                    if (message.acknowledgedAt != null)
                        AppBadge(appString(AppString.InboxAcknowledged))
                    else if (message.acknowledgementRequired)
                        AppBadge(appString(AppString.InboxAcknowledgementRequired))
                }
            }
            item(key = "body") { SelectionContainer { AppText(message.body) } }
        }
        if (state.hasSnapshot && state.item == null)
            item(key = "unavailable") {
                AppText(appString(AppString.InboxMessageUnavailable), color = AppColors.muted)
            }
    }
}
