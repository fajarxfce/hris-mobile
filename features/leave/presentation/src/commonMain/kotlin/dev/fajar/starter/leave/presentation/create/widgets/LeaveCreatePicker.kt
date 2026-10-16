package dev.fajar.starter.leave.presentation.create.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.leave.domain.entities.LeavePortion
import dev.fajar.starter.leave.presentation.create.*
import dev.fajar.starter.leave.presentation.formatters.*
import dev.fajar.starter.localization.*
import kotlinx.datetime.Month

@Composable
fun LeaveCreatePicker(state: LeaveCreateState, onEvent: (LeaveCreateEvent) -> Unit) {
    val dialog = state.dialog ?: return
    val form = state.form ?: return
    AppDialog(
        appString(
            when (dialog) {
                LeaveCreateDialog.DiscardDraft -> AppString.LeaveDiscardDraft
                LeaveCreateDialog.ReloadDraft -> AppString.LeaveReloadDraft
                LeaveCreateDialog.LeaveUnsaved -> AppString.LeaveWithoutSaving
                LeaveCreateDialog.Types -> AppString.LeaveType
                LeaveCreateDialog.Portion -> AppString.LeavePortion
                is LeaveCreateDialog.Date ->
                    if (dialog.field == LeaveDateField.FROM) AppString.LeaveFromDate
                    else AppString.LeaveUntilDate
            }
        ),
        appString(AppString.Close),
        { onEvent(LeaveCreateEvent.DialogDismissed) },
    ) {
        LazyColumn(
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (dialog) {
                LeaveCreateDialog.DiscardDraft,
                LeaveCreateDialog.ReloadDraft,
                LeaveCreateDialog.LeaveUnsaved ->
                    item(key = "confirmation") {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            AppText(
                                appString(
                                    when (dialog) {
                                        LeaveCreateDialog.DiscardDraft ->
                                            AppString.LeaveDiscardDraftExplanation
                                        LeaveCreateDialog.ReloadDraft ->
                                            AppString.LeaveReloadDraftExplanation
                                        else -> AppString.LeaveWithoutSavingExplanation
                                    }
                                )
                            )
                            AppButton(
                                appString(
                                    when (dialog) {
                                        LeaveCreateDialog.DiscardDraft ->
                                            AppString.LeaveDiscardDraft
                                        LeaveCreateDialog.ReloadDraft -> AppString.LeaveReloadDraft
                                        else -> AppString.LeaveWithoutSaving
                                    }
                                ),
                                {
                                    onEvent(
                                        when (dialog) {
                                            LeaveCreateDialog.DiscardDraft ->
                                                LeaveCreateEvent.DiscardDraftConfirmed
                                            LeaveCreateDialog.ReloadDraft ->
                                                LeaveCreateEvent.ReloadDraftConfirmed
                                            else -> LeaveCreateEvent.ExitWithoutSaving
                                        }
                                    )
                                },
                                enabled = state.work == null && !state.closing,
                            )
                        }
                    }
                LeaveCreateDialog.Portion ->
                    items(LeavePortion.entries, key = { it.name }) { portion ->
                        AppRadioRow(
                            appString(portion.label()),
                            form.portion == portion,
                            { onEvent(LeaveCreateEvent.PortionSelected(portion)) },
                            enabled = state.editable,
                        )
                    }
                LeaveCreateDialog.Types -> {
                    if (state.work == LeaveCreateWork.TYPES)
                        item(key = "loading") { AppLoading(compact = true) }
                    state.visibleFailure?.let { failure ->
                        item(key = "failure") {
                            AppFeedback(failureText(failure))
                            AppTextButton(
                                appString(AppString.TryAgain),
                                { onEvent(LeaveCreateEvent.ReloadRequested) },
                                enabled = state.work == null,
                            )
                        }
                    }
                    if (state.work == null && state.catalog.items.isEmpty())
                        item(key = "empty") { AppText(appString(AppString.LeaveNoTypes)) }
                    items(state.catalog.items, key = { it.id }) { type ->
                        AppRadioRow(
                            type.name,
                            form.type?.id == type.id,
                            { onEvent(LeaveCreateEvent.TypeSelected(type.id)) },
                            enabled = state.editable && state.work != LeaveCreateWork.TYPES,
                        )
                    }
                    if (state.catalog.nextCursor != null)
                        item(key = "more") {
                            AppTextButton(
                                appString(AppString.LeaveMoreTypes),
                                {
                                    onEvent(
                                        LeaveCreateEvent.TypePageRequested(state.catalog.nextCursor)
                                    )
                                },
                                enabled = state.work == null,
                            )
                        }
                    if (state.catalogAfter != null)
                        item(key = "first") {
                            AppTextButton(
                                appString(AppString.LeaveFirstTypes),
                                { onEvent(LeaveCreateEvent.TypePageRequested(null)) },
                                enabled = state.work == null,
                            )
                        }
                }
                is LeaveCreateDialog.Date ->
                    item(key = "calendar") {
                        AppDatePicker(
                            formatMonth(dialog.month.toString(), appLanguageTag()),
                            leaveWeekdayLabels.map { appString(it) },
                            leaveCalendarDates(dialog, form, appLanguageTag()),
                            { onEvent(LeaveCreateEvent.DateSelected(it)) },
                            appString(AppString.PreviousMonth),
                            appString(AppString.NextMonth),
                            if (dialog.month.year == 1900 && dialog.month.month == Month.JANUARY)
                                null
                            else {
                                { onEvent(LeaveCreateEvent.DateMonthRequested(-1)) }
                            },
                            if (dialog.month.year == 2200 && dialog.month.month == Month.DECEMBER)
                                null
                            else {
                                { onEvent(LeaveCreateEvent.DateMonthRequested(1)) }
                            },
                        )
                    }
            }
        }
    }
}
