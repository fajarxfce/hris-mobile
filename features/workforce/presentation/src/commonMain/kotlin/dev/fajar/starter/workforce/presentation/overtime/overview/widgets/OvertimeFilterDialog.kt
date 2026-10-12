package dev.fajar.starter.workforce.presentation.overtime.overview.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.localization.*
import dev.fajar.starter.workforce.domain.overtime.entities.OvertimeStatus
import dev.fajar.starter.workforce.presentation.overtime.formatters.label
import dev.fajar.starter.workforce.presentation.overtime.overview.*

@Composable
fun OvertimeFilterDialog(state: OvertimeState, onEvent: (OvertimeEvent) -> Unit) {
    AppDialog(
        appString(
            when (state.dialog) {
                OvertimeDialog.MONTH -> AppString.OvertimeMonth
                OvertimeDialog.STATUS -> AppString.OvertimeRequestStatus
                else -> AppString.ChooseEmployment
            }
        ),
        appString(AppString.Close),
        { onEvent(OvertimeEvent.DialogDismissed) },
    ) {
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (state.dialog) {
                OvertimeDialog.MONTH ->
                    state.picker?.let { picker ->
                        item(key = "months") {
                            AppMonthPicker(
                                picker.year.toString(),
                                picker.months.mapIndexed { index, date ->
                                    AppMonthChoice(
                                        index + 1,
                                        formatMonth(
                                            date.toString(),
                                            appLanguageTag(),
                                            includeYear = false,
                                        ),
                                        date == state.filters?.period?.from,
                                        true,
                                    )
                                },
                                { onEvent(OvertimeEvent.PickerMonthSelected(it)) },
                                appString(AppString.PreviousYear),
                                appString(AppString.NextYear),
                                onPreviousYear =
                                    picker.previousYear?.let { year ->
                                        { onEvent(OvertimeEvent.PickerYearRequested(year)) }
                                    },
                                onNextYear =
                                    picker.nextYear?.let { year ->
                                        { onEvent(OvertimeEvent.PickerYearRequested(year)) }
                                    },
                            )
                        }
                    }
                OvertimeDialog.STATUS -> {
                    item(key = "all") {
                        AppRadioRow(
                            appString(AppString.OvertimeAllStatuses),
                            state.filters?.status == null,
                            { onEvent(OvertimeEvent.StatusSelected(null)) },
                        )
                    }
                    items(OvertimeStatus.entries, key = { it.name }) { status ->
                        AppRadioRow(
                            appString(status.label()),
                            status == state.filters?.status,
                            { onEvent(OvertimeEvent.StatusSelected(status)) },
                        )
                    }
                }
                OvertimeDialog.EMPLOYMENT -> {
                    if (state.loading || state.refreshing)
                        item(key = "loading") { AppLoading(compact = true) }
                    state.failure?.let { failure ->
                        item(key = "failure") { AppFeedback(failureText(failure)) }
                    }
                    val content = state.content?.value
                    items(content?.directory?.items.orEmpty(), key = { it.id }) { employee ->
                        AppRadioRow(
                            employee.employeeNumber,
                            employee.id == content?.selection?.employeeId,
                            { onEvent(OvertimeEvent.EmploymentSelected(employee.id)) },
                            enabled = !state.refreshing,
                        )
                    }
                    if (content?.directory?.nextCursor != null)
                        item(key = "next") {
                            AppTextButton(
                                appString(AppString.MoreEmployments),
                                {
                                    onEvent(
                                        OvertimeEvent.EmploymentPageRequested(
                                            content.directory.nextCursor
                                        )
                                    )
                                },
                                enabled = !state.refreshing,
                            )
                        }
                    if (content?.selection?.employmentAfter != null)
                        item(key = "first") {
                            AppTextButton(
                                appString(AppString.CalendarFirstEmployments),
                                { onEvent(OvertimeEvent.EmploymentPageRequested(null)) },
                                enabled = !state.refreshing,
                            )
                        }
                }
                null -> Unit
            }
        }
    }
}
