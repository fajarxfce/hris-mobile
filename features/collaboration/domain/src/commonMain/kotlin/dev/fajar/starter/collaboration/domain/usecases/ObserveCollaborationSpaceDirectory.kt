package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceDirectoryRepository
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.*
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

/** Local facts only. Credential age and network availability do not schedule or hide data. */
class ObserveCollaborationSpaceDirectory(
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(
        access: CollaborationSpaceLocalAccess
    ): Flow<AppResult<CollaborationSpaceDirectorySnapshot>> =
        sessions
            .observe()
            .map { result ->
                when (result) {
                    is AppResult.Failed -> result
                    is AppResult.Success ->
                        localCollaborationSpaceAccess(access, result.value, server.audience)
                }
            }
            .distinctUntilChanged()
            .flatMapLatest { result ->
                when (result) {
                    is AppResult.Failed -> flowOf(result)
                    is AppResult.Success ->
                        directory.observeAdmission(result.value).map { admission ->
                            when (admission) {
                                is AppResult.Failed -> admission
                                is AppResult.Success -> {
                                    val gate = admission.value
                                    when {
                                        !validCollaborationSpaceDirectoryAdmission(gate) ->
                                            collaborationRecoveryFailure(
                                                "client.collaboration_invalid_response",
                                                FailureKind.Unexpected,
                                            )
                                        gate.denial != null ->
                                            AppResult.Failed(requireNotNull(gate.denial))
                                        else -> result
                                    }
                                }
                            }
                        }
                }
            }
            .buffer(0)
            .distinctUntilChanged()
            .flatMapLatest { access ->
                when (access) {
                    is AppResult.Failed -> flowOf(access)
                    is AppResult.Success -> directory.observe(access.value)
                }
            }
            .buffer(0)
            .distinctUntilChanged()
}
