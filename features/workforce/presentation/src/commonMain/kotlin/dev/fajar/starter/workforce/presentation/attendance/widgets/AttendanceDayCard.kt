package dev.fajar.starter.workforce.presentation.attendance.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.localization.*
import dev.fajar.starter.workforce.domain.attendance.entities.AttendanceDay
import dev.fajar.starter.workforce.presentation.attendance.formatters.*
import dev.fajar.starter.workforce.presentation.calendar.formatters.calendarDuration
import io.github.composefluent.FluentTheme

@Composable
fun AttendanceDayCard(day: AttendanceDay, timezone: String) {
    AppCard(Modifier.fillMaxWidth(), spacing = 10.dp) {
        AppHeading(
            formatLocalDate(day.date.toString(), appLanguageTag()),
            style = FluentTheme.typography.bodyStrong,
        )
        if (day.entries.isEmpty() && day.correction == null)
            AppText(appString(AppString.AttendanceEmpty), color = AppColors.muted)
        day.entries.forEach { entry ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AppText(
                        attendanceKind(entry.kind) +
                            " · " +
                            attendanceTime(entry.capturedAt, timezone)
                    )
                    AppText(attendanceStatus(entry.status), color = AppColors.muted)
                }
            }
        }
        if (day.correction != null) AppText(appString(AppString.AttendanceCorrected))
        if (day.acceptedMinutes > 0)
            AppText(
                appString(AppString.AttendanceAcceptedHours) +
                    ": " +
                    calendarDuration(day.acceptedMinutes),
                color = AppColors.muted,
            )
    }
}
