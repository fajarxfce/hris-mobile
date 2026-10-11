package dev.fajar.starter.home.presentation.home

import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.identity.domain.entities.User
import dev.fajar.starter.workspace.domain.entities.Workspace

enum class HomeTab {
    Home,
    Account,
}

data class HomeState(
    val workspace: Workspace,
    val tab: HomeTab = HomeTab.Home,
    val user: User? = null,
    val working: Boolean = false,
    val failure: Failure? = null,
    val canOpenProfile: Boolean = false,
    val canOpenInbox: Boolean = false,
)
