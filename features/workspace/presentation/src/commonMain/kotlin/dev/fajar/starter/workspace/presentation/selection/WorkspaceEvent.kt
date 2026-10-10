package dev.fajar.starter.workspace.presentation.selection

sealed interface WorkspaceEvent {
    data object CompaniesRequested : WorkspaceEvent

    data class CompanySelected(val companyId: String) : WorkspaceEvent

    data object SignOutRequested : WorkspaceEvent
}
