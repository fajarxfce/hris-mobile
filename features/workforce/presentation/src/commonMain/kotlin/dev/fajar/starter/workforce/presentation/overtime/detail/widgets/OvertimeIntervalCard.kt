package dev.fajar.starter.workforce.presentation.overtime.detail.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.localization.*
import dev.fajar.starter.workforce.domain.overtime.entities.OvertimeInterval
import dev.fajar.starter.workforce.presentation.calendar.formatters.calendarDuration
import dev.fajar.starter.workforce.presentation.overtime.formatters.overtimeTimeRange
import io.github.composefluent.FluentTheme
import kotlinx.datetime.LocalDate

@Composable
fun OvertimeIntervalCard(
    title: String,
    interval: OvertimeInterval,
    timezone: String,
    workDate: LocalDate,
) {
    AppCard(Modifier.fillMaxWidth(), padding = 16.dp, spacing = 8.dp) {
        AppHeading(title, style = FluentTheme.typography.bodyStrong)
        AppText(
            overtimeTimeRange(interval, timezone, workDate),
            style = FluentTheme.typography.subtitle,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            AppText(calendarDuration(interval.workedMinutes.toLong()))
            AppText(
                appString(
                    AppString.CalendarBreak,
                    formatNumber(interval.breakMinutes.toLong(), appLanguageTag()),
                ),
                color = AppColors.muted,
            )
        }
        AppText(timezone, color = AppColors.muted, style = FluentTheme.typography.caption)
    }
}
