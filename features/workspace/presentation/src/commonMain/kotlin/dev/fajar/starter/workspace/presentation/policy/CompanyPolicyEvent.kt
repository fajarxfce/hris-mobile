package dev.fajar.starter.workspace.presentation.policy

sealed interface CompanyPolicyEvent {
    data object RefreshRequested : CompanyPolicyEvent

    data object CompanyChangeRequested : CompanyPolicyEvent
}
