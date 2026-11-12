package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlin.time.Clock
import kotlinx.coroutines.*

/** Retains a delay for one operationally failed global turn without advancing its cursor. */
class DeferCollaborationSpaceHistoryRecoveryTurn(
    private val demands: CollaborationSpaceIncomingDemandRepository,
    private val roots: CollaborationSpaceRootRepository,
    private val registrations: CollaborationSpaceRegistrationCommandRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val sync: CollaborationSpaceSyncRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        turn: CollaborationSpaceHistoryRecoveryTurn,
        failure: Failure,
    ): AppResult<Boolean> {
        currentCoroutineContext().ensureActive()
        val runtime = turn.runtime
        if (
            !validCollaborationOriginalConversationOwner(runtime.owner) ||
                !validCollaborationSpaceConversationId(runtime.deviceId) ||
                !validCollaborationSpaceConversationId(runtime.operationId) ||
                !validCollaborationSpaceIncomingSource(turn.source) ||
                !validCollaborationSpaceHistoryRecovery(turn.recovery) ||
                turn.recovery.complete
        )
            return changed(FailureKind.Validation)
        if (
            isCollaborationSpaceIncomingQueueContention(failure) ||
                failure.kind in
                    setOf(
                        FailureKind.Unauthorized,
                        FailureKind.AccessDenied,
                        FailureKind.Validation,
                        FailureKind.Conflict,
                    )
        )
            return AppResult.Failed(failure)
        return withTimeoutOrNull(5_000) { Deferral(turn).run() } ?: changed(FailureKind.Timeout)
    }

    private inner class Deferral(val turn: CollaborationSpaceHistoryRecoveryTurn) {
        val runtime = turn.runtime
        val access = runtime.owner.localDirectoryAccess()
        val account = runtime.owner.parent.localSpaceAccountAccess()

        suspend fun run(): AppResult<Boolean> {
            current().let { if (it is AppResult.Failed) return it }
            val loaded = demands.current(access)
            val previous =
                (loaded as? AppResult.Success)
                    ?.value
                    ?.let(::captureCollaborationSpaceIncomingDemandSnapshot)
            current().let { if (it is AppResult.Failed) return it }
            if (loaded is AppResult.Failed) return loaded
            val active = previous?.demand?.historyRecovery
            if (
                previous == null ||
                    !validCollaborationMessageId(previous.generation) ||
                    !validCollaborationSpaceIncomingDemand(access, previous.demand) ||
                    active == null ||
                    !sameCollaborationSpaceHistoryRecoveryTurn(turn.recovery, active)
            )
                return changed()
            val retry =
                nextCollaborationSpaceIncomingRetry(active.retry, clock.now().toEpochMilliseconds())
                    ?: return changed(FailureKind.Validation)
            val planned = planCollaborationSpaceHistoryRecoveryDefer(active, turn.recovery, retry)
            if (planned is AppResult.Failed) return planned
            val next = (planned as AppResult.Success).value
            val write =
                demands.replace(
                    access,
                    previous,
                    previous.demand.copy(historyRecovery = next),
                    null,
                )
            val written =
                when (write) {
                    is AppResult.Failed -> write
                    is AppResult.Success ->
                        AppResult.Success(
                            write.value?.let(::captureCollaborationSpaceIncomingDemandSnapshot)
                        )
                }
            current().let { if (it is AppResult.Failed) return it }
            if (written is AppResult.Failed && written.failure.kind != FailureKind.Storage)
                return written
            val found = demands.current(access)
            val retained =
                (found as? AppResult.Success)
                    ?.value
                    ?.let(::captureCollaborationSpaceIncomingDemandSnapshot)
            current().let { if (it is AppResult.Failed) return it }
            if (found is AppResult.Failed) return found
            return confirmCollaborationSpaceHistoryRecoveryWrite(
                access,
                previous,
                next,
                written,
                retained,
            )
        }

        suspend fun current(): AppResult<Unit> {
            admission().let { if (it is AppResult.Failed) return it }
            val loaded = registrations.current(access, runtime.owner.parent.nativeSessionId)
            admission().let { if (it is AppResult.Failed) return it }
            if (loaded is AppResult.Failed) return loaded
            if ((loaded as AppResult.Success).value != turn.registration) return changed()
            val platform =
                CollaborationNativePlatform.entries.firstOrNull {
                    it.name == build.platform.name.uppercase()
                } ?: return changed(FailureKind.Unavailable)
            if (
                registeredCollaborationSpaceOriginalIncomingSdk(
                    runtime,
                    turn.source,
                    turn.registration,
                    platform,
                ) == null
            )
                return changed()
            return AppResult.Success(Unit)
        }

        suspend fun admission(): AppResult<Unit> {
            localAdmission().let { if (it is AppResult.Failed) return it }
            val loaded = roots.current(account)
            localAdmission().let { if (it is AppResult.Failed) return it }
            if (loaded is AppResult.Failed) return loaded
            if ((loaded as AppResult.Success).value?.spaceId != access.spaceId) return changed()
            return AppResult.Success(Unit)
        }

        suspend fun localAdmission(): AppResult<Unit> {
            currentCoroutineContext().ensureActive()
            val current = sessions.current()
            val gate = directory.admission(access)
            currentCoroutineContext().ensureActive()
            if (gate is AppResult.Success) {
                if (!validCollaborationSpaceDirectoryAdmission(gate.value)) return changed()
                gate.value.denial?.let {
                    return AppResult.Failed(it)
                }
            }
            if (current is AppResult.Failed) return current
            val session = (current as AppResult.Success).value
            localCollaborationSpaceAccess(access, session, server.audience).let {
                if (it is AppResult.Failed) return it
            }
            if (gate is AppResult.Failed) return gate
            if (session?.binding?.serverId != runtime.owner.parent.nativeSessionId)
                return changed(FailureKind.Unauthorized)
            if (sync.incomingSource(runtime) != turn.source) return changed(FailureKind.Unavailable)
            return AppResult.Success(Unit)
        }
    }

    private fun changed(kind: FailureKind = FailureKind.Conflict) =
        collaborationRecoveryFailure("client.collaboration_history_recovery_turn_changed", kind)
}
