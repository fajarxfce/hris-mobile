package dev.fajar.starter.workforce.presentation.overtime.plan.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.localization.*
import dev.fajar.starter.workforce.presentation.overtime.plan.*
import dev.fajar.starter.workforce.presentation.overtime.plan.formatters.*
import kotlinx.datetime.*

@Composable
fun OvertimePlanPicker(state: OvertimePlanState, onEvent: (OvertimePlanEvent) -> Unit) {
    val dialog = state.dialog ?: return
    AppDialog(
        appString(
            when (dialog) {
                is OvertimePlanDialog.Date -> dialog.field.label()
                is OvertimePlanDialog.Time -> dialog.field.label()
                OvertimePlanDialog.Discard -> AppString.OvertimeDiscardCommand
                OvertimePlanDialog.DiscardDraft -> AppString.OvertimeDiscardDraft
                OvertimePlanDialog.ReloadDraft -> AppString.OvertimeReloadDraft
                OvertimePlanDialog.LeaveUnsaved -> AppString.OvertimeWithoutSaving
            }
        ),
        appString(AppString.Close),
        { onEvent(OvertimePlanEvent.DialogDismissed) },
    ) {
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (dialog) {
                OvertimePlanDialog.DiscardDraft,
                OvertimePlanDialog.ReloadDraft,
                OvertimePlanDialog.LeaveUnsaved ->
                    item(key = "working-confirmation") {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            AppText(
                                appString(
                                    when (dialog) {
                                        OvertimePlanDialog.DiscardDraft ->
                                            AppString.OvertimeDiscardDraftExplanation
                                        OvertimePlanDialog.ReloadDraft ->
                                            AppString.OvertimeReloadDraftExplanation
                                        else -> AppString.OvertimeWithoutSavingExplanation
                                    }
                                )
                            )
                            AppButton(
                                appString(
                                    when (dialog) {
                                        OvertimePlanDialog.DiscardDraft ->
                                            AppString.OvertimeDiscardDraft
                                        OvertimePlanDialog.ReloadDraft ->
                                            AppString.OvertimeReloadDraft
                                        else -> AppString.OvertimeWithoutSaving
                                    }
                                ),
                                {
                                    onEvent(
                                        when (dialog) {
                                            OvertimePlanDialog.DiscardDraft ->
                                                OvertimePlanEvent.DiscardDraftConfirmed
                                            OvertimePlanDialog.ReloadDraft ->
                                                OvertimePlanEvent.ReloadDraftConfirmed
                                            else -> OvertimePlanEvent.ExitWithoutSaving
                                        }
                                    )
                                },
                                enabled = state.work == null && !state.closing,
                            )
                        }
                    }
                is OvertimePlanDialog.Date ->
                    item(key = "calendar") {
                        val bounds = state.planDateBounds(dialog.field)
                        AppDatePicker(
                            formatMonth(dialog.month.toString(), appLanguageTag()),
                            overtimeWeekdayLabels.map { appString(it) },
                            planCalendarDates(state, dialog, appLanguageTag()),
                            { onEvent(OvertimePlanEvent.DateSelected(it)) },
                            appString(AppString.PreviousMonth),
                            appString(AppString.NextMonth),
                            if (bounds != null && dialog.month > bounds.start) {
                                { onEvent(OvertimePlanEvent.DateMonthRequested(-1)) }
                            } else null,
                            if (
                                bounds != null &&
                                    dialog.month.plus(1, DateTimeUnit.MONTH) <= bounds.endInclusive
                            ) {
                                { onEvent(OvertimePlanEvent.DateMonthRequested(1)) }
                            } else null,
                        )
                    }
                is OvertimePlanDialog.Time -> {
                    item(key = "time") {
                        AppTimeInput(
                            dialog.hours,
                            dialog.minutes,
                            appString(AppString.OvertimeHour),
                            appString(AppString.OvertimeMinute),
                            { onEvent(OvertimePlanEvent.HoursChanged(it)) },
                            { onEvent(OvertimePlanEvent.MinutesChanged(it)) },
                        )
                    }
                    item(key = "confirm-time") {
                        AppButton(
                            appString(AppString.OvertimeUseTime),
                            { onEvent(OvertimePlanEvent.TimeConfirmed) },
                            Modifier.fillMaxWidth(),
                            enabled = state.editable && dialog.value != null,
                        )
                    }
                }
                OvertimePlanDialog.Discard -> {
                    item(key = "discard-message") {
                        AppText(appString(AppString.OvertimeDiscardPrompt))
                    }
                    item(key = "discard-confirmation") {
                        AppButton(
                            appString(AppString.OvertimeDiscardCommand),
                            { onEvent(OvertimePlanEvent.DiscardConfirmed) },
                            Modifier.fillMaxWidth(),
                            enabled = state.canDiscard,
                        )
                    }
                }
            }
        }
    }
}
