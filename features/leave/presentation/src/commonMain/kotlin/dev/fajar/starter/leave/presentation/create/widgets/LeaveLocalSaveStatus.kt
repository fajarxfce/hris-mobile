package dev.fajar.starter.leave.presentation.create.widgets

import androidx.compose.runtime.Composable
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.leave.presentation.create.*
import dev.fajar.starter.localization.*
import io.github.composefluent.FluentTheme

@Composable
fun LeaveLocalSaveStatus(state: LeaveCreateState, onEvent: (LeaveCreateEvent) -> Unit) {
    val local = state.persistence ?: return
    if (local.failure == null) {
        AppText(
            appString(
                if (local.saved) AppString.LeaveSavedLocally else AppString.LeaveSavingLocally
            ),
            color = AppColors.muted,
            style = FluentTheme.typography.caption,
        )
    } else {
        AppFeedback(failureText(local.failure))
        if (local.failure.code == "client.leave_working_copy_changed")
            AppTextButton(
                appString(AppString.LeaveReloadDraft),
                { onEvent(LeaveCreateEvent.ReloadDraftRequested) },
                enabled = state.work == null && !state.closing,
            )
        else
            AppTextButton(
                appString(AppString.TryAgain),
                { onEvent(LeaveCreateEvent.SaveRetryRequested) },
                enabled = !local.writing && state.work == null && !state.closing,
            )
    }
}
