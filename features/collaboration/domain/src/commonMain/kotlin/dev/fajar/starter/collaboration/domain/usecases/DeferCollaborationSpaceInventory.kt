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

/** Defers one original inventory cursor without extending a replay or replacing a newer loss. */
class DeferCollaborationSpaceInventory(
    private val demands: CollaborationSpaceIncomingDemandRepository,
    private val registrations: CollaborationSpaceRegistrationCommandRepository,
    private val sync: CollaborationSpaceSyncRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        runtime: CollaborationSpaceRuntimeReady,
        captured: CollaborationSpaceIncomingSourceProgress,
        failure: Failure? = null,
    ): AppResult<Boolean> {
        currentCoroutineContext().ensureActive()
        if (
            !validCollaborationSpaceIncomingSourceProgress(captured) ||
                captured.inventoryGeneration == null ||
                !validCollaborationOriginalConversationOwner(runtime.owner) ||
                !validCollaborationMessageId(runtime.deviceId) ||
                !validCollaborationMessageId(runtime.operationId)
        )
            return changed(FailureKind.Validation)
        if (
            isCollaborationSpaceIncomingQueueContention(failure) ||
                failure?.kind in
                    setOf(
                        FailureKind.Unauthorized,
                        FailureKind.AccessDenied,
                        FailureKind.Validation,
                        FailureKind.Conflict,
                    )
        )
            return AppResult.Failed(requireNotNull(failure))
        return withTimeoutOrNull(5_000) { Deferral(runtime, captured).run() }
            ?: changed(FailureKind.Timeout)
    }

    private inner class Deferral(
        val runtime: CollaborationSpaceRuntimeReady,
        val captured: CollaborationSpaceIncomingSourceProgress,
    ) {
        val access = runtime.owner.localDirectoryAccess()
        var registration: CollaborationSpaceRegistrationRecord? = null

        suspend fun run(): AppResult<Boolean> {
            current().let { if (it is AppResult.Failed) return it }
            val loaded = demands.current(access)
            val raw = (loaded as? AppResult.Success)?.value
            val previous = raw?.let(::captureCollaborationSpaceIncomingDemandSnapshot)
            current().let { if (it is AppResult.Failed) return it }
            if (loaded is AppResult.Failed) return loaded
            if (raw == null) return AppResult.Success(false)
            if (
                previous == null ||
                    !validCollaborationMessageId(previous.generation) ||
                    !validCollaborationSpaceIncomingDemand(access, previous.demand)
            )
                return changed()
            val source =
                previous.demand.sources.singleOrNull {
                    it.source.storeId == captured.source.storeId
                }
            if (source != null && source.source != captured.source) return changed()
            if (
                source == null ||
                    source.inventoryGeneration != captured.inventoryGeneration ||
                    source.inventoryAfterRoomId != captured.inventoryAfterRoomId
            )
                return AppResult.Success(false)
            val planned =
                deferCollaborationSpaceInventory(
                    previous.demand,
                    captured,
                    clock.now().toEpochMilliseconds(),
                )
            if (planned is AppResult.Failed) return planned
            val next = (planned as AppResult.Success).value
            if (next == previous.demand) return AppResult.Success(true)
            return retain(previous, next)
        }

        suspend fun retain(
            previous: CollaborationSpaceIncomingDemandSnapshot,
            next: CollaborationSpaceIncomingDemand,
        ): AppResult<Boolean> {
            val deferred = next.sources.single { it.source == captured.source }
            current().let { if (it is AppResult.Failed) return it }
            val saved = demands.replace(access, previous, next)
            val checked = current()
            if (checked is AppResult.Failed)
                return if (saved is AppResult.Failed)
                    AppResult.Failed(
                        retainCollaborationSpaceConversationFailure(saved.failure, checked.failure)
                    )
                else checked
            if (saved is AppResult.Failed && saved.failure.kind != FailureKind.Storage) return saved
            val loaded = demands.current(access)
            val retained =
                (loaded as? AppResult.Success)
                    ?.value
                    ?.let(::captureCollaborationSpaceIncomingDemandSnapshot)
            current().let { if (it is AppResult.Failed) return it }
            if (loaded is AppResult.Failed) return loaded
            if (
                retained == null ||
                    !validCollaborationMessageId(retained.generation) ||
                    !validCollaborationSpaceIncomingDemand(access, retained.demand)
            )
                return changed()
            val confirmed =
                confirmCollaborationSpaceInventoryDeferral(
                    captured,
                    deferred,
                    retained.demand,
                    saved is AppResult.Success && saved.value == null,
                )
            return if (confirmed is AppResult.Failed && saved is AppResult.Failed) saved
            else confirmed
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
            if (
                registeredCollaborationSpaceOriginalIncomingSdk(
                    runtime,
                    captured.source,
                    value,
                    platform,
                ) == null || (registration != null && registration != value)
            )
                return changed()
            registration = value
            return AppResult.Success(Unit)
        }

        suspend fun admission(): AppResult<Unit> {
            currentCoroutineContext().ensureActive()
            val gate = directory.admission(access)
            val parent = sessions.guardPushParent(server, build, runtime.owner.parent)
            currentCoroutineContext().ensureActive()
            if (gate is AppResult.Success) {
                if (!validCollaborationSpaceDirectoryAdmission(gate.value)) return changed()
                gate.value.denial?.let {
                    return AppResult.Failed(it)
                }
            }
            if (parent is AppResult.Failed) return parent
            if (gate is AppResult.Failed) return gate
            if (sync.incomingSource(runtime) != captured.source)
                return changed(FailureKind.Unavailable)
            return AppResult.Success(Unit)
        }
    }

    private fun changed(kind: FailureKind = FailureKind.Conflict) =
        collaborationRecoveryFailure("client.collaboration_inventory_defer_changed", kind)
}
