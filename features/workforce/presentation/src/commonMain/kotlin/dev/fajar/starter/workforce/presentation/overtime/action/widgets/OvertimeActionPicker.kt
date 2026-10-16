package dev.fajar.starter.workforce.presentation.overtime.action.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.localization.*
import dev.fajar.starter.workforce.presentation.overtime.action.*
import dev.fajar.starter.workforce.presentation.overtime.action.formatters.*
import kotlinx.datetime.*

@Composable
fun OvertimeActionPicker(state: OvertimeActionState, onEvent: (OvertimeActionEvent) -> Unit) {
    val dialog = state.dialog ?: return
    AppDialog(
        appString(
            when (dialog) {
                is OvertimeActionDialog.Date -> dialog.field.dateLabel()
                is OvertimeActionDialog.Time -> dialog.field.timeLabel()
                OvertimeActionDialog.Discard -> AppString.OvertimeDiscardCommand
                OvertimeActionDialog.DiscardDraft -> AppString.OvertimeDiscardDraft
                OvertimeActionDialog.ReloadDraft -> AppString.OvertimeReloadDraft
                OvertimeActionDialog.LeaveUnsaved -> AppString.OvertimeWithoutSaving
            }
        ),
        appString(AppString.Close),
        { onEvent(OvertimeActionEvent.DialogDismissed) },
    ) {
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (dialog) {
                OvertimeActionDialog.DiscardDraft,
                OvertimeActionDialog.ReloadDraft,
                OvertimeActionDialog.LeaveUnsaved ->
                    item(key = "working-confirmation") {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            AppText(
                                appString(
                                    when (dialog) {
                                        OvertimeActionDialog.DiscardDraft ->
                                            AppString.OvertimeDiscardDraftExplanation
                                        OvertimeActionDialog.ReloadDraft ->
                                            AppString.OvertimeReloadDraftExplanation
                                        else -> AppString.OvertimeWithoutSavingExplanation
                                    }
                                )
                            )
                            AppButton(
                                appString(
                                    when (dialog) {
                                        OvertimeActionDialog.DiscardDraft ->
                                            AppString.OvertimeDiscardDraft
                                        OvertimeActionDialog.ReloadDraft ->
                                            AppString.OvertimeReloadDraft
                                        else -> AppString.OvertimeWithoutSaving
                                    }
                                ),
                                {
                                    onEvent(
                                        when (dialog) {
                                            OvertimeActionDialog.DiscardDraft ->
                                                OvertimeActionEvent.DiscardDraftConfirmed
                                            OvertimeActionDialog.ReloadDraft ->
                                                OvertimeActionEvent.ReloadDraftConfirmed
                                            else -> OvertimeActionEvent.ExitWithoutSaving
                                        }
                                    )
                                },
                                enabled = state.work == null && !state.closing,
                            )
                        }
                    }
                is OvertimeActionDialog.Date ->
                    item(key = "date") {
                        val bounds = state.actualDateBounds()
                        AppDatePicker(
                            formatMonth(dialog.month.toString(), appLanguageTag()),
                            listOf(
                                    AppString.WeekdayMondayShort,
                                    AppString.WeekdayTuesdayShort,
                                    AppString.WeekdayWednesdayShort,
                                    AppString.WeekdayThursdayShort,
                                    AppString.WeekdayFridayShort,
                                    AppString.WeekdaySaturdayShort,
                                    AppString.WeekdaySundayShort,
                                )
                                .map { appString(it) },
                            actualCalendarDates(state, dialog, appLanguageTag()),
                            { onEvent(OvertimeActionEvent.DateSelected(it)) },
                            appString(AppString.PreviousMonth),
                            appString(AppString.NextMonth),
                            if (bounds != null && dialog.month > bounds.start) {
                                { onEvent(OvertimeActionEvent.DateMonthRequested(-1)) }
                            } else null,
                            if (
                                bounds != null &&
                                    dialog.month.plus(1, DateTimeUnit.MONTH) <= bounds.endInclusive
                            ) {
                                { onEvent(OvertimeActionEvent.DateMonthRequested(1)) }
                            } else null,
                        )
                    }
                is OvertimeActionDialog.Time -> {
                    item(key = "time") {
                        AppTimeInput(
                            dialog.hours,
                            dialog.minutes,
                            appString(AppString.OvertimeHour),
                            appString(AppString.OvertimeMinute),
                            { onEvent(OvertimeActionEvent.HoursChanged(it)) },
                            { onEvent(OvertimeActionEvent.MinutesChanged(it)) },
                        )
                    }
                    item(key = "confirm-time") {
                        AppButton(
                            appString(AppString.OvertimeUseTime),
                            { onEvent(OvertimeActionEvent.TimeConfirmed) },
                            Modifier.fillMaxWidth(),
                            enabled = state.editable && dialog.value != null,
                        )
                    }
                }
                OvertimeActionDialog.Discard -> {
                    item(key = "message") { AppText(appString(AppString.OvertimeDiscardPrompt)) }
                    item(key = "confirm-discard") {
                        AppButton(
                            appString(AppString.OvertimeDiscardCommand),
                            { onEvent(OvertimeActionEvent.DiscardConfirmed) },
                            Modifier.fillMaxWidth(),
                            enabled = state.canDiscard,
                        )
                    }
                }
            }
        }
    }
}
