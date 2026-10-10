package dev.fajar.starter.identity.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class AccountSnapshotDto(
    val account: AccountDto,
    val permissions: List<String>,
    val companies: List<CompanyMembershipDto>,
    val assurance: SessionAssuranceDto,
)
