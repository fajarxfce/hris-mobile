package dev.fajar.starter.app.bootstrap

import dev.fajar.starter.app.navigation.AppLink

data class AppState(val stage: AppStage = AppStage.Loading, val pendingLink: AppLink? = null)
