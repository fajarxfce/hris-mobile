package dev.fajar.starter.workforce.presentation.overtime.plan.widgets

import androidx.compose.runtime.Composable
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.localization.*
import dev.fajar.starter.workforce.presentation.overtime.plan.*
import io.github.composefluent.FluentTheme

@Composable
fun OvertimePlanLocalSaveStatus(state: OvertimePlanState, onEvent: (OvertimePlanEvent) -> Unit) {
    val local = state.persistence ?: return
    if (local.failure == null) {
        AppText(
            appString(
                if (local.saved) AppString.OvertimeSavedLocally else AppString.OvertimeSavingLocally
            ),
            color = AppColors.muted,
            style = FluentTheme.typography.caption,
        )
    } else {
        AppFeedback(failureText(local.failure))
        if (local.failure.code == "client.overtime_working_copy_changed")
            AppTextButton(
                appString(AppString.OvertimeReloadDraft),
                { onEvent(OvertimePlanEvent.ReloadDraftRequested) },
                enabled = state.work == null && !state.closing,
            )
        else
            AppTextButton(
                appString(AppString.TryAgain),
                { onEvent(OvertimePlanEvent.SaveRetryRequested) },
                enabled = !local.writing && state.work == null && !state.closing,
            )
    }
}
