package dev.fajar.starter.home.presentation.home

import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.home.presentation.catalog.HomeServiceCatalog
import dev.fajar.starter.identity.domain.entities.User
import dev.fajar.starter.workspace.domain.entities.Workspace

enum class HomeTab {
    Home,
    Chat,
    Inbox,
    /** Compatibility with previously saved HomeRoute values; opens Chat / Contacts. */
    Employees,
    Account,
}

enum class ChatSection {
    Conversations,
    Contacts,
}

data class HomeState(
    val workspace: Workspace,
    val tab: HomeTab = HomeTab.Home,
    val user: User? = null,
    val operation: HomeOperation? = null,
    val failure: Failure? = null,
    val services: HomeServiceCatalog = HomeServiceCatalog(),
    val chatSection: ChatSection =
        if (tab == HomeTab.Employees) ChatSection.Contacts else ChatSection.Conversations,
) {
    val working: Boolean
        get() = operation != null
}
