package dev.fajar.starter.workforce.presentation.attendance.history.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.localization.*
import dev.fajar.starter.workforce.presentation.attendance.history.*
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.CalendarLtr
import io.github.composefluent.icons.regular.ChevronLeft
import io.github.composefluent.icons.regular.ChevronRight

@Composable
fun AttendanceMonthBar(month: AttendanceMonthFilter?, onEvent: (AttendanceHistoryEvent) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        AppIconButton(
            Icons.Regular.ChevronLeft,
            appString(AppString.PreviousMonth),
            { month?.previous?.let { onEvent(AttendanceHistoryEvent.MonthRequested(it)) } },
            enabled = month?.previous != null,
        )
        AppFilterChip(
            month?.let { formatMonth(it.selected.toString(), appLanguageTag()) }
                ?: appString(AppString.AttendanceMonth),
            selected = false,
            onClick = { onEvent(AttendanceHistoryEvent.DateFilterRequested) },
            modifier = Modifier.weight(1f),
            enabled = month != null,
            icon = Icons.Regular.CalendarLtr,
        )
        AppIconButton(
            Icons.Regular.ChevronRight,
            appString(AppString.NextMonth),
            { month?.next?.let { onEvent(AttendanceHistoryEvent.MonthRequested(it)) } },
            enabled = month?.next != null,
        )
    }
}
