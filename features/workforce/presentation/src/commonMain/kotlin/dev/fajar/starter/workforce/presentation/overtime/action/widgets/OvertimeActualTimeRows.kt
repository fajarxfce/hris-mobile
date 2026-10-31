package dev.fajar.starter.workforce.presentation.overtime.action.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.localization.*
import dev.fajar.starter.workforce.presentation.overtime.action.*
import dev.fajar.starter.workforce.presentation.overtime.action.formatters.*
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.CalendarLtr
import io.github.composefluent.icons.regular.Clock
import kotlinx.datetime.LocalDateTime

@Composable
fun OvertimeActualTimeRows(
    field: OvertimeActualField,
    value: LocalDateTime,
    enabled: Boolean,
    onEvent: (OvertimeActionEvent) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        AppSelectField(
            appString(field.dateLabel()),
            formatLocalDate(value.date.toString(), appLanguageTag()),
            { onEvent(OvertimeActionEvent.DatePickerRequested(field)) },
            enabled = enabled,
            icon = Icons.Regular.CalendarLtr,
        )
        AppSelectField(
            appString(field.timeLabel()),
            value.time.toString(),
            { onEvent(OvertimeActionEvent.TimePickerRequested(field)) },
            enabled = enabled,
            icon = Icons.Regular.Clock,
        )
    }
}
