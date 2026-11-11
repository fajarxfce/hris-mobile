@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.coroutines.*

/** One inventory page inside the existing incoming worker; no independent collector or retry. */
class RetainCollaborationSpaceRoomInventory(
    private val inventories: CollaborationSpaceRoomInventoryRepository,
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
    ): AppResult<CollaborationSpaceInventoryOutcome> {
        currentCoroutineContext().ensureActive()
        if (
            !validCollaborationSpaceIncomingSourceProgress(captured) ||
                captured.inventoryGeneration == null ||
                !validCollaborationOriginalConversationOwner(runtime.owner) ||
                !validCollaborationMessageId(runtime.deviceId) ||
                !validCollaborationMessageId(runtime.operationId)
        )
            return changed(FailureKind.Validation)
        val now = clock.now().toEpochMilliseconds()
        if (now !in 0..9_007_199_254_440_991L) return changed(FailureKind.Validation)
        if (
            captured.inventoryRetry?.let {
                now >= it.attemptedAtEpochMillis && now < it.eligibleAtEpochMillis
            } == true
        )
            return AppResult.Success(CollaborationSpaceInventoryOutcome.Deferred)
        return withTimeoutOrNull(5_000) { Acquisition(runtime, captured).run() }
            ?: changed(FailureKind.Timeout)
    }

    /** The original source, registration and captured cursor live for exactly one bounded page. */
    private inner class Acquisition(
        val runtime: CollaborationSpaceRuntimeReady,
        val captured: CollaborationSpaceIncomingSourceProgress,
    ) {
        val access = runtime.owner.localDirectoryAccess()
        var registration: CollaborationSpaceRegistrationRecord? = null

        suspend fun run(): AppResult<CollaborationSpaceInventoryOutcome> {
            current().let { if (it is AppResult.Failed) return it }
            val initial = selectedSnapshot()
            if (initial is AppResult.Failed) return initial
            if ((initial as AppResult.Success).value == null)
                return AppResult.Success(CollaborationSpaceInventoryOutcome.Obsolete)
            val received =
                inventories.readInventory(
                    runtime,
                    captured.source,
                    captured.inventoryAfterRoomId,
                    COLLABORATION_SPACE_INVENTORY_PAGE_LIMIT,
                )
            val page =
                (received as? AppResult.Success)
                    ?.value
                    ?.let(::captureCollaborationSpaceInventoryPage)
            val checked = current()
            if (checked is AppResult.Failed)
                return if (received is AppResult.Failed)
                    AppResult.Failed(
                        retainCollaborationSpaceConversationFailure(
                            received.failure,
                            checked.failure,
                        )
                    )
                else checked
            if (received is AppResult.Failed) return received
            if (page == null || page.runtime != runtime) return changed()
            val loaded = selectedSnapshot()
            if (loaded is AppResult.Failed) return loaded
            val previous =
                (loaded as AppResult.Success).value
                    ?: return AppResult.Success(CollaborationSpaceInventoryOutcome.Obsolete)
            val planned =
                retainCollaborationSpaceInventoryPage(
                    previous.demand,
                    captured,
                    page,
                    List(page.rooms.size) { Uuid.random().toString() },
                )
            if (planned is AppResult.Failed) return planned
            val next = (planned as AppResult.Success).value
            if (next.demand == previous.demand) return AppResult.Success(next.outcome)
            return retain(previous, next)
        }

        /**
         * Local capture ignores ordinary credential age; exact original registration stays fenced.
         */
        suspend fun selectedSnapshot(): AppResult<CollaborationSpaceIncomingDemandSnapshot?> {
            val loaded = demands.current(access)
            val raw = (loaded as? AppResult.Success)?.value
            val snapshot = raw?.let(::captureCollaborationSpaceIncomingDemandSnapshot)
            current().let { if (it is AppResult.Failed) return it }
            if (loaded is AppResult.Failed) return loaded
            if (raw == null) return AppResult.Success(null)
            if (
                snapshot == null ||
                    !validCollaborationMessageId(snapshot.generation) ||
                    !validCollaborationSpaceIncomingDemand(access, snapshot.demand)
            )
                return changed()
            val source =
                snapshot.demand.sources.singleOrNull {
                    it.source.storeId == captured.source.storeId
                }
            if (source != null && source.source != captured.source) return changed()
            return AppResult.Success(
                snapshot.takeIf {
                    source != null &&
                        source.inventoryGeneration == captured.inventoryGeneration &&
                        source.inventoryAfterRoomId == captured.inventoryAfterRoomId &&
                        source.inventoryRetry == captured.inventoryRetry
                }
            )
        }

        /** One CAS, then one readback. A cursor witnesses its atomic room/wake retention. */
        suspend fun retain(
            previous: CollaborationSpaceIncomingDemandSnapshot,
            next: CollaborationSpaceInventoryRetention,
        ): AppResult<CollaborationSpaceInventoryOutcome> {
            current().let { if (it is AppResult.Failed) return it }
            val saved =
                demands.replace(
                    access,
                    previous,
                    next.demand,
                    CollaborationSpaceIncomingWake(Uuid.random().toString()),
                )
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
            val expected = next.demand.sources.single { it.source == captured.source }
            val progress = retained.demand.sources.singleOrNull { it.source == captured.source }
            val confirmed =
                progress != null &&
                    progress.inventoryGeneration == expected.inventoryGeneration &&
                    progress.inventoryAfterRoomId == expected.inventoryAfterRoomId &&
                    progress.inventoryRetry == expected.inventoryRetry
            if (confirmed) return AppResult.Success(next.outcome)
            if (saved is AppResult.Failed) return saved
            // A definite CAS miss performs no inline write/read retry. New producer demand
            // survives.
            if ((saved as AppResult.Success).value == null)
                return collaborationSpaceIncomingQueueContention()
            return changed()
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
        collaborationRecoveryFailure("client.collaboration_inventory_changed", kind)
}
