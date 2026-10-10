package dev.fajar.starter.workspace.domain.entities

import dev.fajar.starter.identity.domain.entities.CompanyMembership

/** A new selection gets a new scope even when the user returns to the same company. */
data class Workspace(
    val id: String,
    val sessionId: String,
    val audience: String,
    val company: CompanyMembership,
    val permissions: Set<String>,
)
