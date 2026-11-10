package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

/** Observes local root/bootstrap admission. No transport, scheduling, clock or SDK acquisition. */
class ObserveCollaborationSpaceIncomingStartup(
    private val roots: CollaborationSpaceRootRepository,
    private val registrations: CollaborationSpaceRegistrationCommandRepository,
    private val setups: CollaborationSpaceSetupCommandRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(
        parent: CollaborationNativeParent
    ): Flow<AppResult<CollaborationSpaceIncomingStartup?>> =
        sessions
            .observe()
            .map { session ->
                when (session) {
                    is AppResult.Failed -> session
                    is AppResult.Success -> {
                        val current = collaborationPushParent(session.value, server, build)
                        if (current is AppResult.Success && current.value != parent)
                            collaborationRecoveryFailure(
                                "client.session_changed",
                                FailureKind.Unauthorized,
                            )
                        else current
                    }
                }
            }
            .distinctUntilChanged()
            .flatMapLatest { owned ->
                when (owned) {
                    is AppResult.Failed -> flowOf(owned)
                    is AppResult.Success -> observeRoot(owned.value)
                }
            }
            .buffer(0)
            // One latest-owner boundary owns the bootstrap collector and any suspended emission.
            // Nesting that collector inside root observation can forward an old root's result
            // before cancellation reaches a blocked downstream consumer.
            .flatMapLatest { owned ->
                when (owned) {
                    is AppResult.Failed -> flowOf(owned)
                    is AppResult.Success ->
                        owned.value?.let { observeBootstrap(it) } ?: flowOf(AppResult.Success(null))
                }
            }
            .buffer(0)
            .distinctUntilChanged()

    private fun observeRoot(
        parent: CollaborationNativeParent
    ): Flow<AppResult<CollaborationSpaceOwner?>> =
        roots.observe(parent.localSpaceAccountAccess()).distinctUntilChanged().map {
            when (it) {
                is AppResult.Failed -> it
                is AppResult.Success -> {
                    val root = it.value
                    when {
                        root == null -> AppResult.Success(null)
                        !validCollaborationMessageId(root.spaceId) ->
                            collaborationRecoveryFailure(
                                "client.collaboration_incoming_startup_changed",
                                FailureKind.Conflict,
                            )
                        else -> AppResult.Success(CollaborationSpaceOwner(parent, root.spaceId))
                    }
                }
            }
        }

    private fun observeBootstrap(
        owner: CollaborationSpaceOwner
    ): Flow<AppResult<CollaborationSpaceIncomingStartup?>> {
        val access = owner.localDirectoryAccess()
        return combine(
                directory.observeAdmission(access),
                registrations.observe(access, owner.parent.nativeSessionId),
                setups.observeAll(access),
            ) { gate, registered, commands ->
                when {
                    gate is AppResult.Failed -> gate
                    gate is AppResult.Success &&
                        !validCollaborationSpaceDirectoryAdmission(gate.value) ->
                        collaborationRecoveryFailure(
                            "client.collaboration_incoming_startup_changed",
                            FailureKind.Conflict,
                        )
                    gate is AppResult.Success && gate.value.denial != null ->
                        AppResult.Failed(requireNotNull(gate.value.denial))
                    registered is AppResult.Failed -> registered
                    commands is AppResult.Failed -> commands
                    commands is AppResult.Success && commands.value.size > 128 ->
                        collaborationRecoveryFailure(
                            "client.collaboration_incoming_startup_changed",
                            FailureKind.Conflict,
                        )
                    else ->
                        collaborationSpaceIncomingStartup(
                            owner,
                            build,
                            (gate as AppResult.Success).value,
                            (registered as AppResult.Success).value,
                            (commands as AppResult.Success).value.toList(),
                        )
                }
            }
            .buffer(0)
    }
}
