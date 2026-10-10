package dev.fajar.starter.workspace.data.mappers

import dev.fajar.starter.identity.domain.entities.CompanyMembership
import dev.fajar.starter.workspace.data.dto.WorkspaceRecord
import dev.fajar.starter.workspace.domain.entities.Workspace

fun WorkspaceRecord.toWorkspace() =
    Workspace(
        id,
        sessionId,
        audience,
        CompanyMembership(companyId, companyName, companyCode, timezone),
        permissions.toSet(),
    )

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
    )
