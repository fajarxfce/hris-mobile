package dev.fajar.starter.workforce.presentation.attendance.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.localization.*
import dev.fajar.starter.workforce.domain.attendance.entities.AttendanceKind
import dev.fajar.starter.workforce.presentation.attendance.*
import dev.fajar.starter.workforce.presentation.attendance.formatters.attendanceTime
import dev.fajar.starter.workforce.presentation.calendar.formatters.calendarTimeRange
import io.github.composefluent.FluentTheme

@Composable
fun AttendanceCaptureCard(
    state: AttendanceState,
    onEvent: (AttendanceEvent) -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
) {
    AppCard(
        Modifier.fillMaxWidth().testTag("attendance-capture"),
        padding = 16.dp,
        spacing = 12.dp,
    ) {
        val content = state.content
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AppHeading(
                    appString(AppString.AttendanceToday),
                    style = FluentTheme.typography.bodyStrong,
                )
                if (content != null || state.loading || state.refreshing)
                    AppText(
                        content?.let { formatLocalDate(it.date.toString(), appLanguageTag()) }
                            ?: appString(AppString.Loading),
                        color = AppColors.muted,
                        style = FluentTheme.typography.caption,
                    )
            }
            actions()
        }
        if (content?.employee != null || (content == null && (state.loading || state.refreshing))) {
            if (content?.shift != null)
                AppText(calendarTimeRange(content.shift), color = AppColors.muted)
            if (content?.employee != null && content.snapshot.directory.items.size > 1)
                AppText(content.employee.employeeNumber, color = AppColors.muted)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AppText(appString(AppString.ClockIn), color = AppColors.muted)
                    AppText(
                        content?.clockIn?.let {
                            attendanceTime(it.capturedAt, content.snapshot.directory.timezone)
                        } ?: "—",
                        style = FluentTheme.typography.subtitle,
                    )
                    if (content?.clockIn != null)
                        AppText(
                            appString(content.clockIn.status),
                            style = FluentTheme.typography.caption,
                            color = AppColors.muted,
                        )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AppText(appString(AppString.ClockOut), color = AppColors.muted)
                    AppText(
                        content?.clockOut?.let {
                            attendanceTime(it.capturedAt, content.snapshot.directory.timezone)
                        } ?: "—",
                        style = FluentTheme.typography.subtitle,
                    )
                    if (content?.clockOut != null)
                        AppText(
                            appString(content.clockOut.status),
                            style = FluentTheme.typography.caption,
                            color = AppColors.muted,
                        )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppButton(
                    appString(AppString.ClockIn),
                    { onEvent(AttendanceEvent.CaptureRequested(AttendanceKind.CLOCK_IN)) },
                    Modifier.weight(1f),
                    enabled = content?.canClockIn == true && !state.working && !state.refreshing,
                )
                AppButton(
                    appString(AppString.ClockOut),
                    { onEvent(AttendanceEvent.CaptureRequested(AttendanceKind.CLOCK_OUT)) },
                    Modifier.weight(1f),
                    enabled = content?.canClockOut == true && !state.working && !state.refreshing,
                    primary = false,
                )
            }
        } else if (content != null)
            AppText(appString(AppString.CalendarNoEmployment), color = AppColors.muted)
        if (state.working) AppLoading()
    }
}
