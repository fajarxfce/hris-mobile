package dev.fajar.starter.workforce.presentation.overtime.widgets

import androidx.compose.runtime.Composable
import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.designsystem.theme.AppColors
import dev.fajar.starter.localization.*
import io.github.composefluent.FluentTheme

@Composable
fun OvertimeDraftSaveStatus(
    saved: Boolean,
    failure: Failure?,
    enabled: Boolean,
    onRetry: () -> Unit,
    onReload: () -> Unit,
) {
    if (failure == null) {
        AppText(
            appString(
                if (saved) AppString.OvertimeSavedLocally else AppString.OvertimeSavingLocally
            ),
            color = AppColors.muted,
            style = FluentTheme.typography.caption,
        )
    } else {
        AppFeedback(failureText(failure))
        if (failure.code == "client.overtime_working_copy_changed")
            AppTextButton(appString(AppString.OvertimeReloadDraft), onReload, enabled = enabled)
        else AppTextButton(appString(AppString.TryAgain), onRetry, enabled = enabled)
    }
}
