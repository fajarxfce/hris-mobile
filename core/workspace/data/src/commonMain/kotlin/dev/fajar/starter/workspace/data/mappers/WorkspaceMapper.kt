@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.workspace.data.mappers

import dev.fajar.starter.identity.domain.entities.CompanyMembership
import dev.fajar.starter.workspace.data.dto.WorkspaceRecord
import dev.fajar.starter.workspace.domain.entities.Workspace
import dev.fajar.starter.workspace.domain.entities.WorkspaceAdmission
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json

fun WorkspaceRecord.toWorkspace(): Workspace {
    listOf(id, sessionId, companyId, accountId, familyId).forEach {
        require(Uuid.parse(it).toString() == it)
    }
    require(
        audience.length in 1..2048 && companyName.length in 1..200 && companyCode.length in 1..40
    )
    require(timezone.length in 1..100 && permissions.size <= 1000)
    require(permissions.all { it.matches(Regex("[a-z][a-z0-9_.:-]{0,99}")) })
    val checked = Instant.parse(checkedAt)
    val expires = Instant.parse(expiresAt)
    require(checked < expires)
    return Workspace(
        id,
        sessionId,
        audience,
        CompanyMembership(companyId, companyName, companyCode, timezone),
        permissions.toSet(),
        WorkspaceAdmission(accountId, familyId, checked, expires),
    )
}

fun Workspace.toRecord() =
    WorkspaceRecord(
        id,
        sessionId,
        audience,
        company.id,
        company.name,
        company.code,
        company.timezone,
        permissions.toSet(),
        admission.accountId,
        admission.familyId,
        admission.checkedAt.toString(),
        admission.expiresAt.toString(),
    )

fun workspaceAssociatedData(audience: String, sessionId: String, generation: String) =
    Json.encodeToString(listOf("hris-workspace-v1", audience, sessionId, generation, "0"))
        .encodeToByteArray()
