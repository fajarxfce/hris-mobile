package dev.fajar.starter.workforce.presentation.attendance.history.widgets

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.localization.*
import dev.fajar.starter.workforce.presentation.attendance.history.*
import kotlinx.datetime.LocalDate

@Composable
fun AttendanceMonthDialog(
    picker: AttendanceMonthPicker,
    selected: LocalDate,
    onEvent: (AttendanceHistoryEvent) -> Unit,
) {
    AppDialog(
        appString(AppString.AttendanceMonth),
        appString(AppString.Close),
        { onEvent(AttendanceHistoryEvent.DateFilterDismissed) },
    ) {
        LazyColumn(contentPadding = PaddingValues(16.dp)) {
            item {
                AppMonthPicker(
                    yearLabel = picker.year.toString(),
                    months =
                        picker.months.mapIndexed { index, option ->
                            AppMonthChoice(
                                index + 1,
                                formatMonth(
                                    option.date.toString(),
                                    appLanguageTag(),
                                    includeYear = false,
                                ),
                                option.date == selected,
                                option.enabled,
                            )
                        },
                    onMonthSelected = { onEvent(AttendanceHistoryEvent.PickerMonthSelected(it)) },
                    previousYearLabel = appString(AppString.PreviousYear),
                    nextYearLabel = appString(AppString.NextYear),
                    onPreviousYear =
                        picker.previousYear?.let { year ->
                            { onEvent(AttendanceHistoryEvent.PickerYearRequested(year)) }
                        },
                    onNextYear =
                        picker.nextYear?.let { year ->
                            { onEvent(AttendanceHistoryEvent.PickerYearRequested(year)) }
                        },
                )
            }
        }
    }
}
