package dev.fajar.starter.app.bootstrap

import dev.fajar.starter.app.navigation.AppLink
import dev.fajar.starter.common.result.Failure

data class AppState(
    val stage: AppStage = AppStage.Loading,
    val pendingLink: AppLink? = null,
    val accessFailure: Failure? = null,
)
