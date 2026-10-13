package dev.fajar.starter.workspace.domain.entities

/** One consistent observation of identity, company admission and company policy. */
data class WorkspaceContext(
    val sessionId: String,
    val workspace: Workspace?,
    val policy: CompanyPolicyStatus?,
)
