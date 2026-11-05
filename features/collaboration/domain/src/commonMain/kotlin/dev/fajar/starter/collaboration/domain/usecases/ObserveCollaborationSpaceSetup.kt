package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceSetupCommandRepository
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

/** Owned summaries across native parents; no age, company, live grant or credential projection. */
class ObserveCollaborationSpaceSetup(
    private val commands: CollaborationSpaceSetupCommandRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(
        access: CollaborationSpaceLocalAccess
    ): Flow<AppResult<List<CollaborationSpaceSetupSummary>>> =
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
                                is AppResult.Success -> {
                                    val values = rows.value
                                    if (
                                        values.size > 128 ||
                                            values.map { it.command.id }.distinct().size !=
                                                values.size ||
                                            values
                                                .map { it.command.binding.nativeSessionId }
                                                .distinct()
                                                .size != values.size ||
                                            values.any {
                                                !validCollaborationSpaceSetupRecord(owned.value, it)
                                            }
                                    )
                                        invalidSpaceSetup()
                                    else AppResult.Success(values.map { it.summary() })
                                }
                            }
                        }
                }
            }
            .buffer(0)
            .distinctUntilChanged()
}

private fun CollaborationSpaceSetupRecord.summary() =
    CollaborationSpaceSetupSummary(
        command.id,
        command.registrationId,
        command.binding.nativeSessionId,
        command.request.deviceId,
        when (command.request) {
            is CollaborationSpaceProvisioningRetry -> CollaborationSpaceSetupKind.RETRY
            is CollaborationSpaceBootstrap -> CollaborationSpaceSetupKind.BOOTSTRAP
        },
        when (outcome) {
            CollaborationSpaceSetupOutcome.Pending -> CollaborationSpaceSetupPhase.PENDING
            is CollaborationSpaceSetupOutcome.Unconfirmed ->
                CollaborationSpaceSetupPhase.UNCONFIRMED
            is CollaborationSpaceSetupOutcome.Retried -> CollaborationSpaceSetupPhase.RETRIED
            is CollaborationSpaceSetupOutcome.Bootstrapped ->
                CollaborationSpaceSetupPhase.BOOTSTRAPPED
        },
        command.createdAt,
        attempts,
        (outcome as? CollaborationSpaceSetupOutcome.Unconfirmed)?.failure,
    )
