package dev.fajar.starter.workspace.presentation.selection

import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.identity.domain.entities.CompanyMembership

data class WorkspaceState(
    val companies: List<CompanyMembership> = emptyList(),
    val loading: Boolean = false,
    val selectingId: String? = null,
    val signingOut: Boolean = false,
    val failure: Failure? = null,
) {
    val busy: Boolean
        get() = loading || selectingId != null || signingOut
}
