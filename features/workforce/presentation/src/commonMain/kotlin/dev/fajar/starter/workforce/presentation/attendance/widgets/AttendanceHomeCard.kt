package dev.fajar.starter.workforce.presentation.attendance.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.localization.*
import dev.fajar.starter.workforce.presentation.attendance.*
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.ArrowSync

@Composable
fun AttendanceHomeCard(state: AttendanceState, onEvent: (AttendanceEvent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AttendanceCaptureCard(
            state,
            onEvent,
            actions = {
                AppIconButton(
                    Icons.Regular.ArrowSync,
                    appString(AppString.Refresh),
                    { onEvent(AttendanceEvent.RefreshRequested) },
                    enabled = !state.refreshing && !state.working,
                )
            },
        )
        if (state.content?.hasEmploymentChoice == true)
            AppTextButton(
                appString(AppString.ChooseEmployment),
                { onEvent(AttendanceEvent.EmploymentPickerRequested) },
                enabled = !state.working,
            )
        if (!state.content?.commands.isNullOrEmpty())
            AppTextButton(
                appString(AppString.AttendancePendingActions),
                { onEvent(AttendanceEvent.QueueRequested) },
            )
        if (state.failure != null && state.dialog == null) AttendanceFeedback(state, onEvent)
    }
    when (state.dialog) {
        AttendanceDialog.QUEUE -> AttendanceQueueDialog(state, onEvent)
        AttendanceDialog.EMPLOYMENT -> AttendanceEmploymentDialog(state, onEvent)
        null -> Unit
    }
}
