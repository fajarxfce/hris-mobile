package dev.fajar.starter.people.domain.usecases

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.people.domain.entities.EmploymentProfile
import dev.fajar.starter.people.domain.repositories.PeopleRepository
import dev.fajar.starter.workspace.domain.entities.CompanyModule
import dev.fajar.starter.workspace.domain.policies.requireCompanyModule
import dev.fajar.starter.workspace.domain.policies.validateWorkspaceOwnership
import dev.fajar.starter.workspace.domain.policies.workspaceFailure
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.datetime.LocalDate

class LoadEmploymentProfile(
    private val people: PeopleRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock = Clock.System,
) {
    suspend operator fun invoke(
        workspaceId: String,
        employeeId: String,
        asOf: LocalDate,
    ): AppResult<EmploymentProfile> {
        val session =
            when (val result = sessions.current()) {
                is AppResult.Failed -> return result
                is AppResult.Success ->
                    result.value
                        ?: return workspaceFailure(
                            "client.session_changed",
                            FailureKind.Unauthorized,
                        )
            }
        val workspace =
            when (val result = workspaces.current(session.id)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        val admission = validateWorkspaceOwnership(workspaceId, workspace, session, clock.now())
        if (admission is AppResult.Failed) return admission
        requireNotNull(workspace)
        requireNotNull(session)
        val availability = requireCompanyModule(workspace, CompanyModule.PEOPLE)
        if (availability is AppResult.Failed) return availability
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
            when (val result = workspaces.current(session.id)) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        val currentSession =
            when (val result = sessions.current()) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        val ownership =
            validateWorkspaceOwnership(workspaceId, currentWorkspace, currentSession, clock.now())
        if (ownership is AppResult.Failed) return ownership
        val stillAvailable =
            requireCompanyModule(requireNotNull(currentWorkspace), CompanyModule.PEOPLE)
        if (stillAvailable is AppResult.Failed) return stillAvailable
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
