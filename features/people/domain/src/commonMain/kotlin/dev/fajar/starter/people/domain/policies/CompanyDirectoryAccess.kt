package dev.fajar.starter.people.domain.policies

import dev.fajar.starter.common.result.*
import dev.fajar.starter.workspace.domain.entities.CompanyModule
import dev.fajar.starter.workspace.domain.entities.Workspace
import dev.fajar.starter.workspace.domain.policies.requireCompanyModule

fun requireCompanyDirectoryAccess(workspace: Workspace): AppResult<Unit> {
    val available = requireCompanyModule(workspace, CompanyModule.PEOPLE)
    if (available is AppResult.Failed) return available
    return if (workspace.permissions.any { it == "people.directory.read" || it == "people.read" })
        AppResult.Success(Unit)
    else
        AppResult.Failed(
            Failure(FailureKind.AccessDenied, "Access denied.", code = "access_denied")
        )
}
