package dev.fajar.starter.people.domain.usecases

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.people.domain.entities.DirectoryDepartments
import dev.fajar.starter.people.domain.policies.requireCompanyDirectoryAccess
import dev.fajar.starter.people.domain.repositories.PeopleRepository
import dev.fajar.starter.workspace.domain.policies.validateWorkspaceOwnership
import dev.fajar.starter.workspace.domain.policies.workspaceFailure
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class LoadDirectoryDepartments(
    private val people: PeopleRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock = Clock.System,
) {
    suspend operator fun invoke(
        workspaceId: String,
        query: String = "",
    ): AppResult<DirectoryDepartments> {
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
        val ownership = validateWorkspaceOwnership(workspaceId, workspace, session, clock.now())
        if (ownership is AppResult.Failed) return ownership
        val admission = requireCompanyDirectoryAccess(requireNotNull(workspace))
        if (admission is AppResult.Failed) return admission
        if (query.length > 120)
            return AppResult.Failed(
                Failure(FailureKind.Validation, "Invalid page.", code = "invalid_page")
            )
        val result = people.directoryDepartments(session.id, workspace.company.id, query.trim())
        currentCoroutineContext().ensureActive()
        val currentWorkspace =
            when (val current = workspaces.current(session.id)) {
                is AppResult.Failed -> return current
                is AppResult.Success -> current.value
            }
        val currentSession =
            when (val current = sessions.current()) {
                is AppResult.Failed -> return current
                is AppResult.Success -> current.value
            }
        val stillOwned =
            validateWorkspaceOwnership(workspaceId, currentWorkspace, currentSession, clock.now())
        if (stillOwned is AppResult.Failed) return stillOwned
        val stillAdmitted = requireCompanyDirectoryAccess(requireNotNull(currentWorkspace))
        if (stillAdmitted is AppResult.Failed) return stillAdmitted
        return result
    }
}
