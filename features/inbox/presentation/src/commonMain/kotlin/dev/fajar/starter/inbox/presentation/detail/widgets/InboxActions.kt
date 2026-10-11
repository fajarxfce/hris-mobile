package dev.fajar.starter.inbox.presentation.detail.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.inbox.domain.entities.InboxCommandOutcome
import dev.fajar.starter.inbox.domain.policies.INBOX_MAXIMUM_ATTEMPTS
import dev.fajar.starter.inbox.presentation.detail.*
import dev.fajar.starter.localization.*

@Composable
fun InboxActions(state: InboxItemState, onEvent: (InboxItemEvent) -> Unit) {
    if (state.confirmationVersion != null) {
        AppCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AppText(appString(AppString.InboxAcknowledgementPrompt))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppButton(
                        appString(AppString.Cancel),
                        { onEvent(InboxItemEvent.AcknowledgementCancelled) },
                        primary = false,
                    )
                    AppButton(
                        appString(AppString.InboxAcknowledge),
                        { onEvent(InboxItemEvent.AcknowledgementConfirmed) },
                        loading = state.working,
                    )
                }
            }
        }
    } else if (state.command != null) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (state.command.outcome) {
                InboxCommandOutcome.Pending,
                is InboxCommandOutcome.Unconfirmed -> {
                    AppText(
                        appString(
                            if (state.working) AppString.InboxActionSending
                            else if (state.command.outcome == InboxCommandOutcome.Pending)
                                AppString.InboxActionQueued
                            else AppString.InboxActionUnconfirmed
                        ),
                        color = AppColors.muted,
                    )
                    AppButton(
                        appString(AppString.Retry),
                        { onEvent(InboxItemEvent.RetryActionRequested) },
                        loading = state.working,
                        enabled = state.command.attempts < INBOX_MAXIMUM_ATTEMPTS,
                    )
                }
                is InboxCommandOutcome.Confirmed ->
                    AppText(appString(AppString.InboxActionConfirmed), color = AppColors.muted)
                is InboxCommandOutcome.Rejected -> {
                    AppText(appString(AppString.InboxActionRejected), color = AppColors.muted)
                    AppButton(
                        appString(AppString.InboxReview),
                        { onEvent(InboxItemEvent.ReviewActionRequested) },
                        loading = state.working,
                    )
                }
            }
        }
    } else if (state.actionsReady) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.canRead)
                AppButton(
                    appString(AppString.InboxMarkRead),
                    { onEvent(InboxItemEvent.ReadRequested) },
                    modifier = Modifier.fillMaxWidth(),
                    primary = false,
                )
            if (state.canAcknowledge)
                AppButton(
                    appString(AppString.InboxAcknowledge),
                    { onEvent(InboxItemEvent.AcknowledgementRequested) },
                    modifier = Modifier.fillMaxWidth(),
                )
            if (state.working)
                AppText(appString(AppString.InboxActionSending), color = AppColors.muted)
        }
    }
}
