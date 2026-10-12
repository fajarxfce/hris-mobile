package dev.fajar.starter.leave.presentation.create.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.leave.domain.entities.LeaveCommandOutcome
import dev.fajar.starter.leave.presentation.create.*
import dev.fajar.starter.leave.presentation.formatters.*
import dev.fajar.starter.localization.*
import io.github.composefluent.FluentTheme

@Composable
fun LeaveSubmissionStatus(state: LeaveCreateState, onEvent: (LeaveCreateEvent) -> Unit) {
    val record = state.command ?: return
    val command = record.command
    AppCard(Modifier.fillMaxWidth(), spacing = 12.dp) {
        AppHeading(command.typeName, style = FluentTheme.typography.subtitle)
        AppText(
            leaveDateRange(command.selection.days.first().date, command.selection.days.last().date),
            color = AppColors.muted,
        )
        AppText(leaveDaysText(command.charged), style = FluentTheme.typography.bodyStrong)
        AppText(command.reason)
        if (command.attachmentRevisionIds.isNotEmpty())
            AppText(
                appString(
                    AppString.LeaveAttachedCount,
                    command.attachmentRevisionIds.size.toString(),
                ),
                color = AppColors.muted,
            )
        AppText(
            appString(
                if (
                    state.work == LeaveCreateWork.SENDING &&
                        record.outcome !is LeaveCommandOutcome.Confirmed
                )
                    AppString.LeaveActionSending
                else
                    when (record.outcome) {
                        LeaveCommandOutcome.Pending -> AppString.LeaveSubmissionQueued
                        is LeaveCommandOutcome.Unconfirmed -> AppString.LeaveSubmissionUnconfirmed
                        is LeaveCommandOutcome.Confirmed -> AppString.LeaveSubmissionConfirmed
                        is LeaveCommandOutcome.Rejected -> AppString.LeaveSubmissionRejected
                    }
            )
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.canRetry)
                AppButton(
                    appString(AppString.LeaveRetryDelivery),
                    { onEvent(LeaveCreateEvent.RetryRequested) },
                )
            if (record.outcome is LeaveCommandOutcome.Confirmed)
                AppButton(
                    appString(AppString.LeaveOpenRequest),
                    { onEvent(LeaveCreateEvent.OpenRequestRequested) },
                    enabled = state.work == null,
                )
            if (state.canDiscard)
                AppTextButton(
                    appString(AppString.LeaveDiscardSubmission),
                    { onEvent(LeaveCreateEvent.DiscardRequested) },
                )
        }
    }
}
