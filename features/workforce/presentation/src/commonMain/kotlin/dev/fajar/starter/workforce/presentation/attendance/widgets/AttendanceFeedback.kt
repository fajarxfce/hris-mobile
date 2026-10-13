package dev.fajar.starter.workforce.presentation.attendance.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.localization.*
import dev.fajar.starter.workforce.presentation.attendance.*

@Composable
fun AttendanceFeedback(state: AttendanceState, onEvent: (AttendanceEvent) -> Unit) {
    val failure = state.feedbackFailure ?: return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AppFeedback(failureText(failure))
        if (state.needsVerification)
            AppButton(
                appString(AppString.VerifyAccount),
                { onEvent(AttendanceEvent.VerificationRequested) },
            )
        if (state.settingsTarget != null)
            AppTextButton(
                appString(AppString.OpenSettings),
                { onEvent(AttendanceEvent.SettingsRequested) },
                enabled = !state.working,
            )
        if (state.withoutLocationKind != null) {
            AppText(appString(AppString.AttendanceWithoutLocationHint), color = AppColors.muted)
            AppTextButton(
                appString(AppString.AttendanceWithoutLocation),
                { onEvent(AttendanceEvent.CaptureRequested(state.withoutLocationKind, true)) },
                enabled = !state.working,
            )
        }
    }
}
