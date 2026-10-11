package dev.fajar.starter.workspace.domain.entities

data class CompanyPolicyStatus(
    val workspaceId: String,
    val availability: CompanyAvailability,
    val stale: Boolean,
)
