package dev.fajar.starter.app.bootstrap

import dev.fajar.starter.app.navigation.AppLink
import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.workspace.domain.entities.CompanyPolicyStatus

data class AppState(
    val stage: AppStage = AppStage.Loading,
    val pendingLink: AppLink? = null,
    val accessFailure: Failure? = null,
    val companyPolicy: CompanyPolicyStatus? = null,
    val refreshingWorkspace: Boolean = false,
    val changingWorkspace: Boolean = false,
    val workspaceRefreshFailure: Failure? = null,
)
