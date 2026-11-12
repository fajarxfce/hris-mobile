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

/** Reads one account metadata row under the current root and installed source; creates no work. */
class ReadCollaborationSpaceHistoryRecoveryTurn(
    private val demands: CollaborationSpaceIncomingDemandRepository,
    private val locators: CollaborationIncomingHistoryLocatorRepository,
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
        runtime: CollaborationSpaceRuntimeReady
    ): AppResult<CollaborationSpaceHistoryRecoverySelection> {
        currentCoroutineContext().ensureActive()
        if (
            !validCollaborationOriginalConversationOwner(runtime.owner) ||
                !validCollaborationSpaceConversationId(runtime.deviceId) ||
                !validCollaborationSpaceConversationId(runtime.operationId)
        )
            return changed(FailureKind.Validation)
        return withTimeoutOrNull(5_000) { Reading(runtime).run() } ?: changed(FailureKind.Timeout)
    }

    private inner class Reading(val runtime: CollaborationSpaceRuntimeReady) {
        val access = runtime.owner.localDirectoryAccess()
        val account = runtime.owner.parent.localSpaceAccountAccess()
        var source: CollaborationSpaceIncomingSource? = null
        var registration: CollaborationSpaceRegistrationRecord? = null

        suspend fun run(): AppResult<CollaborationSpaceHistoryRecoverySelection> {
            admission().let { if (it is AppResult.Failed) return it }
            val loaded = readDemand()
            if (loaded is AppResult.Failed) return loaded
            val captured = (loaded as AppResult.Success).value?.demand?.historyRecovery
            if (captured == null || captured.complete)
                return AppResult.Success(CollaborationSpaceHistoryRecoverySelection.Idle)
            val now = clock.now().toEpochMilliseconds()
            if (now !in 0..9_007_199_254_440_991L) return changed(FailureKind.Validation)
            if (!eligibleCollaborationSpaceHistoryRecovery(captured, now))
                return AppResult.Success(
                    CollaborationSpaceHistoryRecoverySelection.Deferred(
                        requireNotNull(captured.retry)
                    )
                )
            source = sync.incomingSource(runtime)
            if (source == null || !validCollaborationSpaceIncomingSource(requireNotNull(source)))
                return changed(FailureKind.Unavailable)
            current().let { if (it is AppResult.Failed) return it }
            val found = locators.read(account, captured.afterPartition, 1)
            val page =
                (found as? AppResult.Success)?.value?.let {
                    captureCollaborationSpaceHistoryRecoveryPage(it, captured.afterPartition)
                }
            current().let { if (it is AppResult.Failed) return it }
            if (found is AppResult.Failed) return found
            if (page == null) return changed()
            val checked = readDemand()
            if (checked is AppResult.Failed) return checked
            val latest =
                (checked as AppResult.Success).value?.demand?.historyRecovery ?: return changed()
            if (!sameCollaborationSpaceHistoryRecoveryTurn(captured, latest)) return changed()
            current().let { if (it is AppResult.Failed) return it }
            return AppResult.Success(
                CollaborationSpaceHistoryRecoverySelection.Ready(
                    CollaborationSpaceHistoryRecoveryTurn(
                        runtime,
                        requireNotNull(source),
                        requireNotNull(registration),
                        latest,
                        page,
                    )
                )
            )
        }

        suspend fun readDemand(): AppResult<CollaborationSpaceIncomingDemandSnapshot?> {
            val loaded = demands.current(access)
            val raw = (loaded as? AppResult.Success)?.value
            val snapshot = raw?.let(::captureCollaborationSpaceIncomingDemandSnapshot)
            admission().let { if (it is AppResult.Failed) return it }
            if (loaded is AppResult.Failed) return loaded
            if (
                raw != null &&
                    (snapshot == null ||
                        !validCollaborationMessageId(snapshot.generation) ||
                        !validCollaborationSpaceIncomingDemand(access, snapshot.demand))
            )
                return changed()
            return AppResult.Success(snapshot)
        }

        suspend fun current(): AppResult<Unit> {
            admission().let { if (it is AppResult.Failed) return it }
            val loaded = registrations.current(access, runtime.owner.parent.nativeSessionId)
            admission().let { if (it is AppResult.Failed) return it }
            if (loaded is AppResult.Failed) return loaded
            val value = (loaded as AppResult.Success).value ?: return changed()
            val platform =
                CollaborationNativePlatform.entries.firstOrNull {
                    it.name == build.platform.name.uppercase()
                } ?: return changed(FailureKind.Unavailable)
            registeredCollaborationSpaceOriginalIncomingSdk(
                runtime,
                requireNotNull(source),
                value,
                platform,
            ) ?: return changed()
            if (registration != null && registration != value) return changed()
            registration = value
            return AppResult.Success(Unit)
        }

        suspend fun admission(): AppResult<Unit> {
            localAdmission().let { if (it is AppResult.Failed) return it }
            val root = roots.current(account)
            localAdmission().let { if (it is AppResult.Failed) return it }
            if (root is AppResult.Failed) return root
            if ((root as AppResult.Success).value?.spaceId != access.spaceId) return changed()
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
            if (source != null && sync.incomingSource(runtime) != source)
                return changed(FailureKind.Unavailable)
            return AppResult.Success(Unit)
        }
    }

    private fun changed(kind: FailureKind = FailureKind.Conflict) =
        collaborationRecoveryFailure("client.collaboration_history_recovery_turn_changed", kind)
}
