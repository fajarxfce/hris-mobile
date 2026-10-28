package dev.fajar.starter.app.bootstrap

import dev.fajar.starter.app.navigation.PendingAppLink
import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.workspace.domain.entities.CompanyPolicyStatus

data class AppState(
    val stage: AppStage = AppStage.Loading,
    val pendingLink: PendingAppLink? = null,
    val accessFailure: Failure? = null,
    val companyPolicy: CompanyPolicyStatus? = null,
    val refreshingWorkspace: Boolean = false,
    val changingWorkspace: Boolean = false,
    val workspaceRefreshFailure: Failure? = null,
)
