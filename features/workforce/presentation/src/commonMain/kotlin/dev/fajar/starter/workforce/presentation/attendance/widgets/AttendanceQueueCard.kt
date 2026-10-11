package dev.fajar.starter.workforce.presentation.attendance.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.localization.*
import dev.fajar.starter.workforce.domain.attendance.entities.*
import dev.fajar.starter.workforce.presentation.attendance.*
import dev.fajar.starter.workforce.presentation.attendance.formatters.*

@Composable
fun AttendanceQueueCard(
    record: AttendanceCommandRecord,
    timezone: String,
    working: Boolean,
    onEvent: (AttendanceEvent) -> Unit,
) {
    AppCard(Modifier.fillMaxWidth(), spacing = 10.dp) {
        AppText(
            attendanceKind(record.command.kind) +
                " · " +
                attendanceTime(record.command.capturedAt, timezone)
        )
        AppText(
            formatLocalDate(record.command.workDate.toString(), appLanguageTag()),
            color = AppColors.muted,
        )
        AppText(attendanceOutcome(record.outcome))
        record.outcome.failureOrNull()?.let { AppFeedback(failureText(it)) }
        if (record.outcome !is AttendanceCommandOutcome.Confirmed)
            AppButton(
                appString(AppString.TryAgain),
                { onEvent(AttendanceEvent.RetryRequested(record.command.id)) },
                enabled = !working,
                primary = false,
            )
        if (record.outcome is AttendanceCommandOutcome.Rejected)
            AppButton(
                appString(AppString.AttendanceDismiss),
                { onEvent(AttendanceEvent.RejectionDismissed(record.command.id, record.revision)) },
                enabled = !working,
                primary = false,
            )
    }
}
