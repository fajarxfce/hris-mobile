package dev.fajar.starter.workforce.presentation.attendance.history.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.localization.*
import dev.fajar.starter.workforce.presentation.attendance.history.*

@Composable
fun AttendanceMonthBar(month: AttendanceMonthFilter?, onEvent: (AttendanceHistoryEvent) -> Unit) {
    AppPeriodNavigation(
        appString(AppString.PreviousMonth),
        appString(AppString.NextMonth),
        { month?.previous?.let { onEvent(AttendanceHistoryEvent.MonthRequested(it)) } },
        { month?.next?.let { onEvent(AttendanceHistoryEvent.MonthRequested(it)) } },
        Modifier.fillMaxWidth(),
        previousEnabled = month?.previous != null,
        nextEnabled = month?.next != null,
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppFilterChip(
                month?.let { formatMonth(it.selected.toString(), appLanguageTag()) }
                    ?: appString(AppString.AttendanceMonth),
                false,
                { onEvent(AttendanceHistoryEvent.DateFilterRequested) },
                enabled = month != null,
                icon = null,
            )
        }
    }
}
