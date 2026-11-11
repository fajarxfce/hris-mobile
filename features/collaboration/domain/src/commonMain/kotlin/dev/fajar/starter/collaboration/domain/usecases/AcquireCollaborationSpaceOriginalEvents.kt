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

/** One bounded Original content batch. The incoming worker owns references and completion. */
class AcquireCollaborationSpaceOriginalEvents(
    private val demands: CollaborationSpaceIncomingDemandRepository,
    private val metadata: CollaborationSpaceOriginalRoomMetadataRepository,
    private val registrations: CollaborationSpaceRegistrationCommandRepository,
    private val sync: CollaborationSpaceSyncRepository,
    private val runtimes: CollaborationSpaceRuntimeRepository,
    private val events: CollaborationSpaceEventRepository,
    private val history: CollaborationHistoryRepository,
    private val edits: CollaborationMessageEditRepository,
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
        eventIds: List<String>,
    ): AppResult<CollaborationSpaceOriginalIncomingBatch> {
        currentCoroutineContext().ensureActive()
        if (eventIds.size !in 1..16)
            return originalIncomingFailure("invalid_request", FailureKind.Validation)
        val captured = eventIds.toList()
        if (
            !validCollaborationSpaceOriginalIncomingRoom(runtime, room) ||
                room.captured.phase != CollaborationSpaceIncomingPhase.Pending ||
                captured.distinct().size != captured.size ||
                captured.any { !validCollaborationEventId(it) }
        )
            return originalIncomingFailure("invalid_request", FailureKind.Validation)
        return Acquisition(runtime, room, captured).run()
    }

    /** Registration, source, raw events and retention plans belong to this invocation. */
    private inner class Acquisition(
        val runtime: CollaborationSpaceRuntimeReady,
        val room: CollaborationSpaceOriginalIncomingRoom,
        val ids: List<String>,
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
        val requestedAt = clock.now()
        val started = timeSource.markNow()
        var nativeDeadline: Instant? = null
        var restriction: Failure? = null
        var source: CollaborationSpaceIncomingSource? = null
        var registered: CollaborationSpaceRegistrationRecord? = null
        val acquired = mutableMapOf<String, CollaborationAcquiredEvent>()
        val plans = linkedMapOf<String, CollaborationIncomingHistoryPlan>()

        suspend fun run(): AppResult<CollaborationSpaceOriginalIncomingBatch> {
            try {
                return withTimeoutOrNull(15_000) {
                    captureOriginal().let {
                        if (it is AppResult.Failed) return@withTimeoutOrNull it
                    }
                    retain()
                } ?: failed(originalIncomingFailure("timeout", FailureKind.Timeout).failure)
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
                return originalIncomingFailure("source_changed", FailureKind.Unavailable)
            source = installed
            val loaded = readRegistration()
            if (loaded is AppResult.Failed) return loaded
            registered = (loaded as AppResult.Success).value
            return current()
        }

        suspend fun retain(): AppResult<CollaborationSpaceOriginalIncomingBatch> {
            for (id in ids) {
                val read = acquired[id]?.let { AppResult.Success(it) } ?: readEvent(id)
                if (read is AppResult.Failed) return read
                val planned = plan((read as AppResult.Success).value)
                if (planned is AppResult.Failed) return planned
                plans[id] = (planned as AppResult.Success).value
            }
            val originals =
                plans.values
                    .flatMap {
                        when (it) {
                            is CollaborationIncomingHistoryPlan.Retain -> it.originals
                            is CollaborationIncomingHistoryPlan.MissingOriginal ->
                                listOf(it.tombstone)
                            else -> emptyList()
                        }
                    }
                    .distinct()
            val replacements =
                plans.values
                    .filterIsInstance<CollaborationIncomingHistoryPlan.Retain>()
                    .flatMap { it.replacements }
                    .distinct()
            if (originals.size > 16 || replacements.size > 16)
                return originalIncomingFailure("bounds")
            if (originals.isNotEmpty()) {
                val saved = checked {
                    history.merge(
                        companyAccess,
                        position.conversationId,
                        expectedMetadata.room.roomId,
                        originals,
                    )
                }
                if (saved is AppResult.Failed) return saved
                if (!(saved as AppResult.Success).value)
                    return originalIncomingFailure("commit_changed")
            }
            if (replacements.isNotEmpty()) {
                val saved = checked {
                    edits.mergeEdits(
                        companyAccess,
                        position.conversationId,
                        expectedMetadata.room.roomId,
                        replacements,
                    )
                }
                if (saved is AppResult.Failed) return saved
                if (!(saved as AppResult.Success).value)
                    return originalIncomingFailure("commit_changed")
            }
            val latest = current()
            if (latest is AppResult.Failed) return latest
            return AppResult.Success(
                CollaborationSpaceOriginalIncomingBatch(
                    runtime,
                    room,
                    plans.map { (id, plan) ->
                        CollaborationIncomingHistoryResult(
                            id,
                            collaborationIncomingHistoryOutcome(plan),
                        )
                    },
                )
            )
        }

        suspend fun plan(
            event: CollaborationAcquiredEvent
        ): AppResult<CollaborationIncomingHistoryPlan> {
            val initial = planCollaborationIncomingHistory(event)
            if (initial is AppResult.Failed) return initial
            val next = (initial as AppResult.Success).value
            if (next !is CollaborationIncomingHistoryPlan.AcquireTarget) return initial
            if (event.content is CollaborationAcquiredEventContent.Replacement) {
                val roomId = expectedMetadata.room.roomId
                val message = checked {
                    when (
                        val read =
                            history.getByEvent(
                                companyAccess,
                                position.conversationId,
                                roomId,
                                next.eventId,
                            )
                    ) {
                        is AppResult.Failed -> read
                        is AppResult.Success ->
                            AppResult.Success(
                                read.value?.let { it.copy(eventIds = it.eventIds.toList()) }
                            )
                    }
                }
                if (message is AppResult.Failed) return message
                val fact = checked {
                    history.getEventFact(
                        companyAccess,
                        position.conversationId,
                        roomId,
                        next.eventId,
                    )
                }
                if (fact is AppResult.Failed) return fact
                val original = (message as AppResult.Success).value
                val alias = (fact as AppResult.Success).value
                if (original != null) {
                    if (alias == null) return originalIncomingFailure("local_original_changed")
                    return planCollaborationIncomingHistory(
                        event,
                        localOriginal =
                            CollaborationIncomingOriginal(
                                original.copy(eventIds = original.eventIds.toList()),
                                alias,
                            ),
                    )
                }
                if (alias != null && (!alias.redacted || alias.timestamp != null))
                    return originalIncomingFailure("local_original_changed")
            }
            val target =
                acquired[next.eventId]?.let { AppResult.Success(it) } ?: readEvent(next.eventId)
            if (target is AppResult.Failed) return target
            return planCollaborationIncomingHistory(event, (target as AppResult.Success).value)
        }

        suspend fun readEvent(id: String): AppResult<CollaborationAcquiredEvent> {
            current().let { if (it is AppResult.Failed) return it }
            val read = events.read(runtime, expectedMetadata.room.roomId, id)
            restrict(read)
            current().let { if (it is AppResult.Failed) return it }
            if (read is AppResult.Failed) return failed(read.failure)
            val snapshot = (read as AppResult.Success).value
            if (
                !validCollaborationSpaceOriginalIncomingSnapshot(
                    runtime,
                    room,
                    requireNotNull(source),
                    snapshot,
                    id,
                )
            )
                return originalIncomingFailure("event_owner_changed")
            acquired[id] = snapshot.physical.event
            return AppResult.Success(snapshot.physical.event)
        }

        suspend fun readRegistration(): AppResult<CollaborationSpaceRegistrationRecord> {
            val loaded = registrations.current(access, owner.parent.nativeSessionId)
            admission().let { if (it is AppResult.Failed) return it }
            if (loaded is AppResult.Failed) return loaded
            val value = (loaded as AppResult.Success).value
            val platform =
                CollaborationNativePlatform.entries.firstOrNull {
                    it.name == build.platform.name.uppercase()
                } ?: return originalIncomingFailure("unsupported_platform", FailureKind.Unavailable)
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
                return originalIncomingFailure("registration_changed")
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
                return originalIncomingFailure("demand_changed")
            return AppResult.Success(Unit)
        }

        suspend fun currentMetadata(): AppResult<Unit> {
            val loaded = metadata.getByConversation(access, position)
            admission().let { if (it is AppResult.Failed) return it }
            if (loaded is AppResult.Failed) return loaded
            if ((loaded as AppResult.Success).value != expectedMetadata)
                return originalIncomingFailure("metadata_changed")
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
            if (!(open as AppResult.Success).value)
                return originalIncomingFailure("runtime_changed")
            val registration = readRegistration()
            if (registration is AppResult.Failed) return registration
            if ((registration as AppResult.Success).value != registered)
                return originalIncomingFailure("registration_changed")
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
                return originalIncomingFailure("invalid_admission")
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
                return originalIncomingFailure("revalidation_required", FailureKind.Unavailable)
            source?.let {
                if (sync.incomingSource(runtime) != it)
                    return originalIncomingFailure("source_changed", FailureKind.Unavailable)
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

private fun originalIncomingFailure(reason: String, kind: FailureKind = FailureKind.Conflict) =
    collaborationRecoveryFailure("client.collaboration_original_incoming_$reason", kind)
