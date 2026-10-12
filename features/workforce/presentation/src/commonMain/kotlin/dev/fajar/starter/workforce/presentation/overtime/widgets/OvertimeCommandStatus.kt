package dev.fajar.starter.workforce.presentation.overtime.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.localization.*
import dev.fajar.starter.workforce.domain.overtime.entities.*
import dev.fajar.starter.workforce.presentation.calendar.formatters.calendarDuration
import dev.fajar.starter.workforce.presentation.overtime.formatters.overtimeTimeRange
import io.github.composefluent.FluentTheme

@Composable
fun OvertimeCommandStatus(
    record: OvertimeCommandRecord,
    sending: Boolean,
    working: Boolean,
    onRetry: (() -> Unit)?,
    onOpen: (() -> Unit)?,
    onDiscard: (() -> Unit)?,
) {
    val command = record.command
    AppCard(Modifier.fillMaxWidth(), padding = 16.dp, spacing = 12.dp) {
        AppHeading(
            formatLocalDate(command.workDate.toString(), appLanguageTag()),
            style = FluentTheme.typography.subtitle,
        )
        val interval =
            when (val payload = command.payload) {
                is OvertimeCommandPayload.Plan -> payload.requested
                is OvertimeCommandPayload.Actual -> payload.actual
                is OvertimeCommandPayload.Withdrawal -> null
            }
        if (interval != null) {
            AppText(
                overtimeTimeRange(interval, command.timezone, command.workDate),
                style = FluentTheme.typography.bodyStrong,
            )
            AppText(calendarDuration(interval.workedMinutes.toLong()), color = AppColors.muted)
        }
        AppText(command.reason)
        AppText(
            appString(
                if (sending) AppString.OvertimeCommandSending
                else
                    when (record.outcome) {
                        OvertimeCommandOutcome.Pending -> AppString.OvertimeCommandQueued
                        is OvertimeCommandOutcome.Unconfirmed ->
                            AppString.OvertimeCommandUnconfirmed
                        is OvertimeCommandOutcome.Confirmed -> AppString.OvertimeCommandConfirmed
                        is OvertimeCommandOutcome.Rejected -> AppString.OvertimeCommandRejected
                    }
            )
        )
        if (working) AppLoading(compact = true)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (onRetry != null)
                AppButton(appString(AppString.OvertimeRetryDelivery), onRetry, enabled = !working)
            if (onOpen != null)
                AppButton(appString(AppString.OvertimeOpenRequest), onOpen, enabled = !working)
            if (onDiscard != null)
                AppTextButton(
                    appString(AppString.OvertimeDiscardCommand),
                    onDiscard,
                    enabled = !working,
                )
        }
    }
}
