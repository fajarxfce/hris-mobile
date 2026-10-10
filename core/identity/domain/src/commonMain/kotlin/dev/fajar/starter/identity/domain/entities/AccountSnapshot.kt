package dev.fajar.starter.identity.domain.entities

data class AccountSnapshot(
    val user: User,
    val companies: List<CompanyMembership>,
    val permissions: Set<String>,
    val assurance: SessionAssurance,
)
