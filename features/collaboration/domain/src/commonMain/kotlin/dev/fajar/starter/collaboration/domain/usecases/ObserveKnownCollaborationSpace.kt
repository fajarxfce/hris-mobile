package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.localCollaborationSpaceAccountAccess
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceRootRepository
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

/** A local cache locator only; consumers still apply directory, command and runtime admission. */
class ObserveKnownCollaborationSpace(
    private val roots: CollaborationSpaceRootRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(
        access: CollaborationSpaceAccountAccess
    ): Flow<AppResult<CollaborationSpaceRoot?>> =
        sessions
            .observe()
            .map { session ->
                when (session) {
                    is AppResult.Failed -> session
                    is AppResult.Success ->
                        localCollaborationSpaceAccountAccess(access, session.value, server.audience)
                }
            }
            .distinctUntilChanged()
            .flatMapLatest { owned ->
                when (owned) {
                    is AppResult.Failed -> flowOf(owned)
                    is AppResult.Success -> roots.observe(owned.value)
                }
            }
            .buffer(0)
            .distinctUntilChanged()
}
