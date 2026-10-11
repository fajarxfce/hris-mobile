package dev.fajar.starter.workspace.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class WorkspaceRecord(
    val id: String,
    val sessionId: String,
    val audience: String,
    val companyId: String,
    val companyName: String,
    val companyCode: String,
    val timezone: String,
    val permissions: Set<String>,
    val accountId: String,
    val familyId: String,
    val checkedAt: String,
    val expiresAt: String,
    val policy: CompanyPolicyRecord? = null,
) {
    override fun toString() = "WorkspaceRecord([redacted])"
}
