package dev.fajar.starter.workforce.presentation.calendar.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.localization.*
import dev.fajar.starter.workforce.domain.entities.WorkDay
import dev.fajar.starter.workforce.presentation.calendar.formatters.*
import io.github.composefluent.FluentTheme

@Composable
fun CalendarDayCard(day: WorkDay, today: Boolean) {
    AppCard(Modifier.fillMaxWidth(), padding = 16.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AppText(
                    formatLocalDate(day.date.toString(), appLanguageTag()),
                    Modifier.weight(1f),
                    color = AppColors.muted,
                )
                if (today) AppBadge(appString(AppString.CalendarToday))
            }
            when (day) {
                is WorkDay.Work -> {
                    AppHeading(day.shift.name, style = FluentTheme.typography.bodyStrong)
                    AppText(calendarTimeRange(day))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        AppText(calendarDuration(day.plannedMinutes), color = AppColors.muted)
                        AppText(
                            appString(
                                AppString.CalendarBreak,
                                formatNumber(day.shift.breakMinutes.toLong(), appLanguageTag()),
                            ),
                            color = AppColors.muted,
                        )
                        AppText(calendarWorkMode(day.shift.mode), color = AppColors.muted)
                    }
                    AppText(
                        calendarOrigin(day.origin),
                        color = AppColors.muted,
                        style = FluentTheme.typography.caption,
                    )
                    AppText(
                        appString(AppString.CalendarTimezone, day.shift.timezone),
                        color = AppColors.muted,
                        style = FluentTheme.typography.caption,
                    )
                }
                is WorkDay.Off -> {
                    AppHeading(
                        appString(AppString.CalendarOff),
                        style = FluentTheme.typography.bodyStrong,
                    )
                    AppText(calendarOrigin(day.origin), color = AppColors.muted)
                }
                is WorkDay.Unassigned ->
                    AppText(appString(AppString.CalendarUnassigned), color = AppColors.muted)
            }
        }
    }
}
