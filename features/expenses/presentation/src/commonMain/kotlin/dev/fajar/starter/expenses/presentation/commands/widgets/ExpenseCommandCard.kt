package dev.fajar.starter.expenses.presentation.commands.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.presentation.commands.*
import dev.fajar.starter.localization.*
import io.github.composefluent.FluentTheme

@Composable
fun ExpenseCommandCard(
    record: ExpenseCommandRecord,
    busy: Boolean,
    enabled: Boolean,
    onRetry: () -> Unit,
    onOpen: () -> Unit,
    onDiscard: () -> Unit,
) {
    AppCard(Modifier.fillMaxWidth(), padding = 16.dp, spacing = 12.dp) {
        AppHeading(
            appString(
                if (busy) AppString.ExpenseProcessing
                else
                    when (record.outcome) {
                        ExpenseCommandOutcome.Pending -> AppString.ExpenseReadyToSend
                        is ExpenseCommandOutcome.Unconfirmed -> AppString.ExpenseConfirmationPending
                        is ExpenseCommandOutcome.Confirmed -> AppString.ExpenseCommandConfirmed
                        is ExpenseCommandOutcome.Rejected -> AppString.ExpenseCommandRejected
                    }
            ),
            style = FluentTheme.typography.bodyStrong,
        )
        val draft = record.command.payload as? ExpenseCommandPayload.SaveDraft
        if (draft != null) {
            AppText(draft.title)
            AppText(
                appString(
                    AppString.ExpenseItemCount,
                    formatNumber(draft.lines.size.toLong(), appLanguageTag()),
                ),
                color = AppColors.muted,
            )
        } else AppText(record.command.reason)
        if (!busy && record.outcome is ExpenseCommandOutcome.Unconfirmed)
            AppText(appString(AppString.ExpenseConfirmationHint), color = AppColors.muted)
        if (busy) AppLoading(compact = true)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (record.canRetry())
                AppTextButton(
                    appString(
                        if (record.outcome == ExpenseCommandOutcome.Pending)
                            AppString.ExpenseSendNow
                        else AppString.ExpenseCheckStatus
                    ),
                    onRetry,
                    enabled = enabled && !busy,
                )
            if (record.outcome is ExpenseCommandOutcome.Confirmed)
                AppTextButton(
                    appString(AppString.ExpenseViewClaim),
                    onOpen,
                    enabled = enabled && !busy,
                )
            if (record.canDiscard())
                AppTextButton(
                    appString(
                        if (draft != null) AppString.ExpenseEditDraft
                        else AppString.ExpenseDiscardRequest
                    ),
                    onDiscard,
                    enabled = enabled && !busy,
                )
        }
    }
}
