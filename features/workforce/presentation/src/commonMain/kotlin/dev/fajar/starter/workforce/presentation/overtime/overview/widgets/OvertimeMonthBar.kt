package dev.fajar.starter.workforce.presentation.overtime.overview.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.localization.*
import dev.fajar.starter.workforce.presentation.overtime.overview.OvertimeEvent
import dev.fajar.starter.workforce.presentation.overtime.overview.OvertimeMonthFilter
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.CalendarLtr
import io.github.composefluent.icons.regular.ChevronLeft
import io.github.composefluent.icons.regular.ChevronRight

@Composable
fun OvertimeMonthBar(month: OvertimeMonthFilter, onEvent: (OvertimeEvent) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        AppIconButton(
            Icons.Regular.ChevronLeft,
            appString(AppString.PreviousMonth),
            { month.previous?.let { onEvent(OvertimeEvent.MonthRequested(it)) } },
            enabled = month.previous != null,
        )
        AppFilterChip(
            formatMonth(month.selected.toString(), appLanguageTag()),
            false,
            { onEvent(OvertimeEvent.MonthFilterRequested) },
            Modifier.weight(1f),
            icon = Icons.Regular.CalendarLtr,
            maxLines = 2,
        )
        AppIconButton(
            Icons.Regular.ChevronRight,
            appString(AppString.NextMonth),
            { month.next?.let { onEvent(OvertimeEvent.MonthRequested(it)) } },
            enabled = month.next != null,
        )
    }
}
