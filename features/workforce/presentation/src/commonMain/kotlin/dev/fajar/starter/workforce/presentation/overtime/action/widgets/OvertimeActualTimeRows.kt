package dev.fajar.starter.workforce.presentation.overtime.action.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.localization.*
import dev.fajar.starter.workforce.presentation.overtime.action.*
import dev.fajar.starter.workforce.presentation.overtime.action.formatters.*
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.ChevronRight
import io.github.composefluent.icons.regular.Clock
import kotlinx.datetime.LocalDateTime

@Composable
fun OvertimeActualTimeRows(
    field: OvertimeActualField,
    value: LocalDateTime,
    enabled: Boolean,
    onEvent: (OvertimeActionEvent) -> Unit,
) {
    AppCard(Modifier.fillMaxWidth(), padding = 0.dp) {
        AppListRow(
            appString(field.dateLabel()),
            { onEvent(OvertimeActionEvent.DatePickerRequested(field)) },
            supportingText = formatLocalDate(value.date.toString(), appLanguageTag()),
            enabled = enabled,
            trailing = { AppIcon(Icons.Regular.ChevronRight) },
        )
        AppListRow(
            appString(field.timeLabel()),
            { onEvent(OvertimeActionEvent.TimePickerRequested(field)) },
            supportingText = value.time.toString(),
            enabled = enabled,
            leading = { AppIcon(Icons.Regular.Clock) },
            trailing = { AppIcon(Icons.Regular.ChevronRight) },
        )
    }
}
