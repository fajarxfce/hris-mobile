package dev.fajar.starter.workspace.presentation.recovery.pages

import androidx.compose.runtime.Composable
import dev.fajar.starter.designsystem.components.*
import dev.fajar.starter.localization.*
import dev.fajar.starter.workspace.presentation.recovery.*

@Composable
fun WorkspaceRecoveryPage(
    state: WorkspaceRecoveryState,
    onEvent: (WorkspaceRecoveryEvent) -> Unit,
) {
    AppPage {
        AppBrand()
        AppHeading(appString(AppString.WorkspaceConnectionTitle))
        AppText(appString(AppString.WorkspaceConnectionDescription))
        if (state.failure != null) AppFeedback(failureText(state.failure))
        AppButton(
            appString(AppString.TryAgain),
            { onEvent(WorkspaceRecoveryEvent.RetryRequested) },
            loading = state.refreshing,
            enabled = !state.refreshing && !state.changingCompany,
        )
        AppButton(
            appString(AppString.ChangeCompany),
            { onEvent(WorkspaceRecoveryEvent.CompanyChangeRequested) },
            primary = false,
            loading = state.changingCompany,
        )
    }
}
