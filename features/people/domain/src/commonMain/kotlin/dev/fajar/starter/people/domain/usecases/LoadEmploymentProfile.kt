package dev.fajar.starter.people.domain.usecases

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.people.domain.entities.EmploymentProfile
import dev.fajar.starter.people.domain.repositories.PeopleRepository
import dev.fajar.starter.workspace.domain.policies.validateWorkspaceOwnership
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.datetime.LocalDate

class LoadEmploymentProfile(
    private val people: PeopleRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
) {
    suspend operator fun invoke(
        workspaceId: String,
        employeeId: String,
        asOf: LocalDate,
    ): AppResult<EmploymentProfile> {
        val workspace =
            when (val result = workspaces.current()) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        val session =
            when (val result = sessions.current()) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        val admission = validateWorkspaceOwnership(workspaceId, workspace, session)
        if (admission is AppResult.Failed) return admission
        requireNotNull(workspace)
        requireNotNull(session)
        val details =
            when (
                val result = people.employment(session.id, workspace.company.id, employeeId, asOf)
            ) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        if (details.employee.accountId != session.user.id)
            return AppResult.Failed(
                Failure(
                    FailureKind.AccessDenied,
                    "The profile is unavailable.",
                    code = "employee_not_found",
                )
            )
        currentCoroutineContext().ensureActive()
        val person =
            when (val result = people.profile(session.id, workspace.company.id, employeeId)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        currentCoroutineContext().ensureActive()
        val currentWorkspace =
            when (val result = workspaces.current()) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        val currentSession =
            when (val result = sessions.current()) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        val ownership = validateWorkspaceOwnership(workspaceId, currentWorkspace, currentSession)
        if (ownership is AppResult.Failed) return ownership
        if (person.accountId != session.user.id || person.personId != details.employee.personId)
            return AppResult.Failed(
                Failure(
                    FailureKind.Unexpected,
                    "The profile could not be loaded.",
                    code = "client.invalid_response",
                )
            )
        return AppResult.Success(EmploymentProfile(details, person))
    }
}
