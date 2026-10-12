package dev.fajar.starter.workforce.presentation.overtime.overview.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.localization.*
import dev.fajar.starter.workforce.domain.overtime.entities.OvertimeSummary
import dev.fajar.starter.workforce.presentation.calendar.formatters.calendarDuration
import dev.fajar.starter.workforce.presentation.overtime.formatters.*
import io.github.composefluent.FluentTheme
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.ChevronRight

@Composable
fun OvertimeRequestRow(request: OvertimeSummary, onClick: () -> Unit) {
    AppListRow(
        title = formatLocalDate(request.workDate.toString(), appLanguageTag()),
        supportingText = overtimeTimeRange(request.requested, request.timezone, request.workDate),
        onClick = onClick,
        metadata = {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AppBadge(appString(request.status.label()))
                AppText(
                    calendarDuration(request.requested.workedMinutes.toLong()),
                    color = AppColors.muted,
                    style = FluentTheme.typography.caption,
                )
            }
        },
        trailing = { AppIcon(Icons.Regular.ChevronRight, tint = AppColors.muted) },
    )
}
