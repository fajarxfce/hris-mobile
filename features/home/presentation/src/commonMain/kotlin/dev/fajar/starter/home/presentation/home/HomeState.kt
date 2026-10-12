package dev.fajar.starter.home.presentation.home

import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.home.presentation.catalog.HomeServiceCatalog
import dev.fajar.starter.identity.domain.entities.User
import dev.fajar.starter.workspace.domain.entities.Workspace

enum class HomeTab {
    Home,
    Inbox,
    Employees,
    Account,
}

data class HomeState(
    val workspace: Workspace,
    val tab: HomeTab = HomeTab.Home,
    val user: User? = null,
    val operation: HomeOperation? = null,
    val failure: Failure? = null,
    val services: HomeServiceCatalog = HomeServiceCatalog(),
) {
    val working: Boolean
        get() = operation != null
}
