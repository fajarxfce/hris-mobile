package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceRegistrationCommandRepository
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

/** Owned local operations across original native parents; no age, SDK or live admission policy. */
class ObserveCollaborationSpaceRegistrations(
    private val commands: CollaborationSpaceRegistrationCommandRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(
        access: CollaborationSpaceLocalAccess
    ): Flow<AppResult<List<CollaborationSpaceRegistrationRecord>>> =
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
                    is AppResult.Success ->
                        commands.observeAll(owned.value).map { rows ->
                            when (rows) {
                                is AppResult.Failed -> rows
                                is AppResult.Success ->
                                    if (
                                        rows.value.size <= 128 &&
                                            rows.value.map { it.command.id }.distinct().size ==
                                                rows.value.size &&
                                            rows.value
                                                .map { it.command.binding.nativeSessionId }
                                                .distinct()
                                                .size == rows.value.size &&
                                            rows.value.all {
                                                validCollaborationSpaceRegistrationRecord(
                                                    owned.value,
                                                    it,
                                                )
                                            }
                                    )
                                        rows
                                    else
                                        collaborationRecoveryFailure(
                                            "client.collaboration_invalid_command",
                                            FailureKind.Unexpected,
                                        )
                            }
                        }
                }
            }
            .buffer(0)
            .distinctUntilChanged()
}
