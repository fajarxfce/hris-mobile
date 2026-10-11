package dev.fajar.starter.people.domain.usecases

import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.people.domain.entities.SelfEmploymentDirectory
import dev.fajar.starter.people.domain.repositories.PeopleRepository
import dev.fajar.starter.workspace.domain.policies.validateWorkspaceOwnership
import dev.fajar.starter.workspace.domain.policies.workspaceFailure
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.time.Clock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class LoadSelfEmployments(
    private val people: PeopleRepository,
    private val sessions: SessionRepository,
    private val workspaces: WorkspaceRepository,
    private val clock: Clock = Clock.System,
) {
    suspend operator fun invoke(
        workspaceId: String,
        after: String? = null,
    ): AppResult<SelfEmploymentDirectory> {
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
        if (after != null && (after.isBlank() || after.length > 32))
            return AppResult.Failed(
                Failure(FailureKind.Validation, "Invalid page.", code = "invalid_page")
            )
        val result = people.selfEmployments(session.id, workspace.company.id, after)
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
        val ownership =
            validateWorkspaceOwnership(workspaceId, currentWorkspace, currentSession, clock.now())
        if (ownership is AppResult.Failed) return ownership
        if (
            result is AppResult.Success &&
                result.value.items.any { it.accountId != session.user.id }
        )
            return AppResult.Failed(
                Failure(
                    FailureKind.Unexpected,
                    "The profile could not be loaded.",
                    code = "client.invalid_response",
                )
            )
        return result
    }
}
