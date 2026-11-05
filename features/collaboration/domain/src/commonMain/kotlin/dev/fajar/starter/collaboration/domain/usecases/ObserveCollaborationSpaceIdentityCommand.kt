package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.localCollaborationSpaceAccess
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceIdentityCommandRepository
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

/** Local command facts only; a saved receipt grants no provider or SDK authority. */
class ObserveCollaborationSpaceIdentityCommand(
    private val commands: CollaborationSpaceIdentityCommandRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(
        access: CollaborationSpaceLocalAccess
    ): Flow<AppResult<CollaborationSpaceIdentityCommandRecord?>> =
        sessions
            .observe()
            .map { session ->
                when (session) {
                    is AppResult.Failed -> session
                    is AppResult.Success ->
                        localCollaborationSpaceAccess(access, session.value, server.audience)
                }
            }
            .distinctUntilChanged()
            .flatMapLatest { owned ->
                when (owned) {
                    is AppResult.Failed -> flowOf(owned)
                    is AppResult.Success -> commands.observe(owned.value)
                }
            }
            .buffer(0)
            .distinctUntilChanged()
}
