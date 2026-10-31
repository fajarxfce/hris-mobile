package dev.fajar.starter.workforce.presentation.overtime.overview.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.localization.*
import dev.fajar.starter.workforce.presentation.overtime.overview.*

@Composable
fun OvertimeMonthBar(
    month: OvertimeMonthFilter,
    onEvent: (OvertimeEvent) -> Unit,
    filters: (@Composable () -> Unit)? = null,
) {
    AppPeriodNavigation(
        appString(AppString.PreviousMonth),
        appString(AppString.NextMonth),
        { month.previous?.let { onEvent(OvertimeEvent.MonthRequested(it)) } },
        { month.next?.let { onEvent(OvertimeEvent.MonthRequested(it)) } },
        Modifier.fillMaxWidth(),
        previousEnabled = month.previous != null,
        nextEnabled = month.next != null,
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppFilterChip(
                formatMonth(month.selected.toString(), appLanguageTag()),
                false,
                { onEvent(OvertimeEvent.MonthFilterRequested) },
                enabled = true,
                icon = null,
            )
            filters?.invoke()
        }
    }
}
