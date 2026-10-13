package dev.fajar.starter.workspace.presentation.navigation

import androidx.compose.runtime.State
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dev.fajar.starter.workspace.presentation.recovery.*
import dev.fajar.starter.workspace.presentation.recovery.pages.WorkspaceRecoveryPage

fun NavGraphBuilder.workspaceRecoveryRoutes(
    state: State<WorkspaceRecoveryState>,
    onEvent: (WorkspaceRecoveryEvent) -> Unit,
) {
    composable<WorkspaceRecoveryRoute> { WorkspaceRecoveryPage(state.value, onEvent) }
}
