package dev.fajar.starter.workforce.presentation.attendance.history.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.localization.*
import dev.fajar.starter.workforce.domain.attendance.entities.AttendanceHistoryDay
import dev.fajar.starter.workforce.presentation.attendance.formatters.*
import dev.fajar.starter.workforce.presentation.attendance.history.formatters.attendanceHistoryStatusLabel
import dev.fajar.starter.workforce.presentation.calendar.formatters.calendarDuration
import io.github.composefluent.FluentTheme

@Composable
fun AttendanceHistoryDayDialog(day: AttendanceHistoryDay, timezone: String, onDismiss: () -> Unit) {
    AppDialog(
        formatLocalDate(day.attendance.date.toString(), appLanguageTag()),
        appString(AppString.Close),
        onDismiss,
    ) {
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { AppText(appString(attendanceHistoryStatusLabel(day.status))) }
            val correction = day.attendance.correction
            if (correction != null)
                item(key = "correction") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        AppHeading(
                            appString(AppString.AttendanceCorrected),
                            style = FluentTheme.typography.bodyStrong,
                        )
                        AppText(
                            appString(AppString.ClockIn) +
                                ": " +
                                (correction.clockIn?.let { attendanceTime(it, timezone) } ?: "—")
                        )
                        AppText(
                            appString(AppString.ClockOut) +
                                ": " +
                                (correction.clockOut?.let { attendanceTime(it, timezone) } ?: "—")
                        )
                    }
                }
            if (day.attendance.entries.isEmpty() && correction == null && day.commands.isEmpty())
                item(key = "empty") {
                    AppText(appString(AppString.AttendanceEmpty), color = AppColors.muted)
                }
            items(day.attendance.entries, key = { "entry/${it.id}" }) { entry ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AppText(
                        attendanceKind(entry.kind) +
                            " · " +
                            attendanceTime(entry.capturedAt, timezone)
                    )
                    AppText(attendanceStatus(entry.status), color = AppColors.muted)
                }
            }
            if (day.attendance.acceptedMinutes > 0)
                item(key = "accepted") {
                    AppText(
                        appString(AppString.AttendanceAcceptedHours) +
                            ": " +
                            calendarDuration(day.attendance.acceptedMinutes)
                    )
                }
            items(day.commands, key = { it.command.id }) { record ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AppText(
                        attendanceKind(record.command.kind) +
                            " · " +
                            attendanceTime(record.command.capturedAt, timezone)
                    )
                    AppText(attendanceOutcome(record.outcome), color = AppColors.muted)
                }
            }
        }
    }
}
