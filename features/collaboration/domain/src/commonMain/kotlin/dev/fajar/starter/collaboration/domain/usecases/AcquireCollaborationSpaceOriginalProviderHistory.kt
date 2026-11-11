package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlin.time.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*

/** One original Company page through the installed Group source; the worker owns scheduling. */
class AcquireCollaborationSpaceOriginalProviderHistory(
    private val demands: CollaborationSpaceIncomingDemandRepository,
    private val metadata: CollaborationSpaceOriginalRoomMetadataRepository,
    private val registrations: CollaborationSpaceRegistrationCommandRepository,
    private val sync: CollaborationSpaceSyncRepository,
    private val runtimes: CollaborationSpaceRuntimeRepository,
    private val provider: CollaborationSpaceProviderHistoryRepository,
    private val history: CollaborationHistoryTraversalRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    suspend operator fun invoke(
        runtime: CollaborationSpaceRuntimeReady,
        room: CollaborationSpaceOriginalIncomingRoom,
    ): AppResult<CollaborationSpaceOriginalProviderHistoryAcquisition> {
        currentCoroutineContext().ensureActive()
        if (
            !validCollaborationSpaceOriginalIncomingRoom(runtime, room) ||
                room.captured.phase == CollaborationSpaceIncomingPhase.Pending
        )
            return originalPageFailure("invalid_request", FailureKind.Validation)
        return Acquisition(runtime, room).run()
    }

    /** The captured source, registration, deadline and possible denial live for one invocation. */
    private inner class Acquisition(
        val runtime: CollaborationSpaceRuntimeReady,
        val room: CollaborationSpaceOriginalIncomingRoom,
    ) {
        val owner = runtime.owner
        val access = owner.localDirectoryAccess()
        val position = room.conversation.conversation.position
        val companyAccess =
            CollaborationLocalAccess(
                access.sessionId,
                access.audience,
                access.accountId,
                position.companyId,
            )
        val expectedMetadata =
            CollaborationSpaceOriginalRoomMetadata(
                room.conversation.conversation,
                requireNotNull(room.conversation.room),
            )
        val origin =
            if (room.captured.phase == CollaborationSpaceIncomingPhase.Latest)
                CollaborationHistoryPageOrigin.Latest
            else CollaborationHistoryPageOrigin.SavedPosition
        val requestedAt = clock.now()
        val started = timeSource.markNow()
        var nativeDeadline: Instant? = null
        var restriction: Failure? = null
        var source: CollaborationSpaceIncomingSource? = null
        var registered: CollaborationSpaceRegistrationRecord? = null

        suspend fun run(): AppResult<CollaborationSpaceOriginalProviderHistoryAcquisition> {
            try {
                return withTimeoutOrNull(15_000) {
                    captureOriginal().let {
                        if (it is AppResult.Failed) return@withTimeoutOrNull it
                    }
                    acquire()
                } ?: failed(originalPageFailure("timeout", FailureKind.Timeout).failure)
            } finally {
                if (restriction != null)
                    withContext(NonCancellable) {
                        withTimeoutOrNull(2_000) { runCatching { runtimes.closeSpace(owner) } }
                    }
            }
        }

        suspend fun captureOriginal(): AppResult<Unit> {
            admission().let { if (it is AppResult.Failed) return it }
            val installed = sync.incomingSource(runtime)
            if (
                installed == null ||
                    !validCollaborationSpaceIncomingSource(installed) ||
                    installed.storeId != room.captured.sourceId
            )
                return originalPageFailure("source_changed", FailureKind.Unavailable)
            source = installed
            val loaded = readRegistration()
            if (loaded is AppResult.Failed) return loaded
            registered = (loaded as AppResult.Success).value
            return current()
        }

        suspend fun acquire(): AppResult<CollaborationSpaceOriginalProviderHistoryAcquisition> {
            val loaded = checked { history.progress(companyAccess, room.traversal) }
            if (loaded is AppResult.Failed) return loaded
            val previous = (loaded as AppResult.Success).value
            if (
                previous != null &&
                    (!validCollaborationHistoryProgress(previous) ||
                        previous.provider != null ||
                        previous.incoming == null)
            )
                return originalPageFailure("invalid_progress")
            if (
                origin == CollaborationHistoryPageOrigin.SavedPosition &&
                    previous?.exhausted == true
            )
                return AppResult.Success(
                    CollaborationSpaceOriginalProviderHistoryAcquisition(
                        runtime,
                        room,
                        origin,
                        previous,
                        previous,
                        0,
                    )
                )
            val from = if (origin == CollaborationHistoryPageOrigin.Latest) null else previous?.next
            val until = collaborationHistoryUntilPosition(room.traversal, previous, origin)
            current().let { if (it is AppResult.Failed) return it }
            val received = provider.readPage(runtime, room.traversal.roomId, from, until)
            val snapshot =
                (received as? AppResult.Success)?.value?.let {
                    if (it.physical.events.size > 20) null
                    else it.copy(physical = it.physical.copy(events = it.physical.events.toList()))
                }
            restrict(received)
            current().let { if (it is AppResult.Failed) return it }
            if (received is AppResult.Failed) return failed(received.failure)
            if (
                snapshot == null ||
                    !validCollaborationSpaceOriginalProviderPage(
                        runtime,
                        room,
                        requireNotNull(source),
                        snapshot,
                        previous,
                        origin,
                    )
            )
                return originalPageFailure("invalid_page")
            return retainPage(previous, snapshot)
        }

        suspend fun retainPage(
            previous: CollaborationHistoryProgress?,
            page: CollaborationSpaceProviderHistoryPage,
        ): AppResult<CollaborationSpaceOriginalProviderHistoryAcquisition> {
            val saved = checked {
                history.stage(companyAccess, room.traversal, previous, page.physical, origin)
            }
            if (saved is AppResult.Success && !saved.value)
                return originalPageFailure("progress_changed")
            // Only an uncertain local storage reply may be confirmed; confirmed denials remain
            // closed.
            if (saved is AppResult.Failed && saved.failure.kind != FailureKind.Storage) return saved
            val loaded = checked { history.progress(companyAccess, room.traversal) }
            if (loaded is AppResult.Failed) return loaded
            val progress = (loaded as AppResult.Success).value
            if (progress == null)
                return (saved as? AppResult.Failed) ?: originalPageFailure("progress_changed")
            val receipt =
                CollaborationSpaceOriginalProviderHistoryAcquisition(
                    runtime,
                    room,
                    origin,
                    previous,
                    progress,
                    page.physical.events.size,
                )
            if (
                !validCollaborationSpaceOriginalProviderAcquisition(receipt) ||
                    progress.next != page.physical.next ||
                    progress.incoming !=
                        collaborationIncomingHistoryPosition(
                            room.traversal,
                            previous,
                            page.physical,
                            origin,
                        )
            )
                return (saved as? AppResult.Failed) ?: originalPageFailure("progress_changed")
            if (saved is AppResult.Failed) {
                val confirmed = confirmStoredSources(progress, page.physical.events)
                if (confirmed is AppResult.Failed) return confirmed
                if (!(confirmed as AppResult.Success).value) return saved
            }
            return AppResult.Success(receipt)
        }

        /**
         * At most twenty point reads confirm an uncertain commit without another provider request.
         */
        suspend fun confirmStoredSources(
            progress: CollaborationHistoryProgress,
            events: List<CollaborationProviderHistoryEvent>,
        ): AppResult<Boolean> {
            for (event in events) {
                val loaded = checked {
                    history.source(companyAccess, room.traversal, event.eventId)
                }
                if (loaded is AppResult.Failed) return loaded
                val stored = (loaded as AppResult.Success).value
                val reference = stored.reference
                if (
                    stored.progress != progress ||
                        reference == null ||
                        reference.event != event ||
                        reference.sequence !in 0 until progress.references
                )
                    return AppResult.Success(false)
            }
            return AppResult.Success(true)
        }

        suspend fun readRegistration(): AppResult<CollaborationSpaceRegistrationRecord> {
            val loaded = registrations.current(access, owner.parent.nativeSessionId)
            admission().let { if (it is AppResult.Failed) return it }
            if (loaded is AppResult.Failed) return loaded
            val value = (loaded as AppResult.Success).value
            val platform =
                CollaborationNativePlatform.entries.firstOrNull {
                    it.name == build.platform.name.uppercase()
                } ?: return originalPageFailure("unsupported_platform", FailureKind.Unavailable)
            if (
                value == null ||
                    !validCollaborationSpaceOriginalIncomingRegistration(
                        runtime,
                        room.captured,
                        requireNotNull(source),
                        value,
                        platform,
                    ) ||
                    value.command.legacy?.deviceId != room.traversal.deviceId ||
                    value.command.provider.serverName != expectedMetadata.room.serverName
            )
                return originalPageFailure("registration_changed")
            return AppResult.Success(value)
        }

        suspend fun currentDemand(): AppResult<Unit> {
            val loaded = demands.current(access)
            val observed = (loaded as? AppResult.Success)?.value
            val snapshot = observed?.let(::captureCollaborationSpaceIncomingDemandSnapshot)
            admission().let { if (it is AppResult.Failed) return it }
            if (loaded is AppResult.Failed) return loaded
            if (
                snapshot == null ||
                    !validCollaborationMessageId(snapshot.generation) ||
                    !validCollaborationSpaceIncomingDemand(access, snapshot.demand) ||
                    snapshot.demand.rooms.singleOrNull { it.target == room.captured.target } !=
                        room.captured ||
                    snapshot.demand.sources.none { it.source == source }
            )
                return originalPageFailure("demand_changed")
            return AppResult.Success(Unit)
        }

        suspend fun currentMetadata(): AppResult<Unit> {
            val loaded = metadata.getByConversation(access, position)
            admission().let { if (it is AppResult.Failed) return it }
            if (loaded is AppResult.Failed) return loaded
            if ((loaded as AppResult.Success).value != expectedMetadata)
                return originalPageFailure("metadata_changed")
            return AppResult.Success(Unit)
        }

        suspend fun current(): AppResult<Unit> {
            admission().let { if (it is AppResult.Failed) return it }
            currentDemand().let { if (it is AppResult.Failed) return it }
            currentMetadata().let { if (it is AppResult.Failed) return it }
            val open = runtimes.isSpaceOpen(owner, runtime.deviceId, runtime.operationId)
            restrict(open)
            admission().let { if (it is AppResult.Failed) return it }
            if (open is AppResult.Failed) return failed(open.failure)
            if (!(open as AppResult.Success).value) return originalPageFailure("runtime_changed")
            val registration = readRegistration()
            if (registration is AppResult.Failed) return registration
            if ((registration as AppResult.Success).value != registered)
                return originalPageFailure("registration_changed")
            return AppResult.Success(Unit)
        }

        suspend fun admission(): AppResult<Unit> {
            currentCoroutineContext().ensureActive()
            val gate = directory.admission(access)
            if (gate is AppResult.Success && validCollaborationSpaceDirectoryAdmission(gate.value))
                gate.value.denial?.let {
                    restriction = retainCollaborationSpaceConversationFailure(restriction, it)
                }
            val parent =
                sessions.captureCollaborationSpaceParent(owner.parent, server, build, clock)
            currentCoroutineContext().ensureActive()
            restriction?.let {
                return AppResult.Failed(it)
            }
            if (parent is AppResult.Failed) return parent
            if (gate is AppResult.Failed) return gate
            if (!validCollaborationSpaceDirectoryAdmission((gate as AppResult.Success).value))
                return originalPageFailure("invalid_admission")
            val expiry =
                Instant.fromEpochMilliseconds(
                    (parent as AppResult.Success).value.binding.expiresAtEpochMillis
                )
            nativeDeadline = nativeDeadline?.let { minOf(it, expiry) } ?: expiry
            val now = clock.now()
            if (
                now < requestedAt ||
                    now >= requireNotNull(nativeDeadline) ||
                    now - requestedAt >= 15.seconds ||
                    started.elapsedNow() >=
                        minOf(15.seconds, requireNotNull(nativeDeadline) - requestedAt)
            )
                return originalPageFailure("revalidation_required", FailureKind.Unavailable)
            source?.let {
                if (sync.incomingSource(runtime) != it)
                    return originalPageFailure("source_changed", FailureKind.Unavailable)
            }
            return AppResult.Success(Unit)
        }

        suspend fun <T> checked(operation: suspend () -> AppResult<T>): AppResult<T> {
            current().let { if (it is AppResult.Failed) return it }
            val result = operation()
            current().let { if (it is AppResult.Failed) return it }
            return if (result is AppResult.Failed) failed(result.failure) else result
        }

        suspend fun restrict(result: AppResult<*>) {
            if (
                result is AppResult.Failed &&
                    collaborationSpaceDirectoryResponseClosesRead(result.failure)
            ) {
                restriction =
                    retainCollaborationSpaceConversationFailure(restriction, result.failure)
                withContext(NonCancellable) {
                    withTimeoutOrNull(2_000) {
                        runCatching { directory.restrict(access, result.failure) }
                    }
                }
            }
        }

        fun failed(value: Failure) = AppResult.Failed(restriction ?: value)
    }
}

private fun originalPageFailure(reason: String, kind: FailureKind = FailureKind.Conflict) =
    collaborationRecoveryFailure("client.collaboration_original_provider_history_$reason", kind)
