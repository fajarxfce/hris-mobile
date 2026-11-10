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

/** One admitted, bounded incoming batch. Its caller owns progress, serialization and retries. */
class AcquireCollaborationSpaceEvents(
    private val bindings: CollaborationSpaceRoomBindingRepository,
    private val transport: CollaborationSpaceDirectTransportRepository,
    private val runtimes: CollaborationSpaceRuntimeRepository,
    private val events: CollaborationSpaceEventRepository,
    private val history: CollaborationSpaceHistoryRepository,
    private val edits: CollaborationSpaceMessageEditRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    suspend operator fun invoke(
        runtime: CollaborationSpaceRuntimeReady,
        conversation: CollaborationSpaceConversation,
        eventIds: List<String>,
    ): AppResult<CollaborationSpaceIncomingBatch> {
        currentCoroutineContext().ensureActive()
        if (eventIds.size !in 1..16)
            return incomingFailure("invalid_request", FailureKind.Validation)
        val captured = eventIds.toList()
        if (
            !validCollaborationSpaceConversation(conversation, runtime.owner) ||
                !validCollaborationSpaceConversationId(runtime.deviceId) ||
                !validCollaborationSpaceConversationId(runtime.operationId) ||
                captured.distinct().size != captured.size ||
                captured.any { !validCollaborationEventId(it) }
        )
            return incomingFailure("invalid_request", FailureKind.Validation)
        return Acquisition(runtime, conversation, captured).run()
    }

    /** Mutable admission, raw snapshots and retention plans never outlive this invocation. */
    private inner class Acquisition(
        val runtime: CollaborationSpaceRuntimeReady,
        val conversation: CollaborationSpaceConversation,
        val ids: List<String>,
    ) {
        val owner = runtime.owner
        val access = owner.localDirectoryAccess()
        val requestedAt = clock.now()
        val started = timeSource.markNow()
        var nativeDeadline: Instant? = null
        var restriction: Failure? = null
        var retained: CollaborationSpaceRoomBinding? = null
        var observed: CollaborationSpaceDirectTransport? = null
        var transportStarted: TimeMark? = null
        var physicalIdentity: Pair<CollaborationOwner, String>? = null
        val acquired = mutableMapOf<String, CollaborationAcquiredEvent>()
        val plans = linkedMapOf<String, CollaborationIncomingHistoryPlan>()

        suspend fun run(): AppResult<CollaborationSpaceIncomingBatch> {
            try {
                return withTimeoutOrNull(15_000) { retain() }
                    ?: failed(incomingFailure("timeout", FailureKind.Timeout).failure)
            } finally {
                if (restriction != null)
                    withContext(NonCancellable) {
                        withTimeoutOrNull(2_000) { runCatching { runtimes.closeSpace(owner) } }
                    }
            }
        }

        suspend fun prepare(): AppResult<Unit> {
            val admitted = current()
            if (admitted is AppResult.Failed) return admitted
            val loaded = checked { bindings.getByConversation(access, conversation.id) }
            if (loaded is AppResult.Failed) return loaded
            val binding =
                (loaded as AppResult.Success).value ?: return incomingFailure("binding_missing")
            if (
                !validCollaborationSpaceRoomBinding(access, binding) ||
                    binding.conversation != conversation.localMetadata()
            )
                return incomingFailure("binding_changed")
            retained = binding
            current().let { if (it is AppResult.Failed) return it }
            transportStarted = timeSource.markNow()
            val received = transport.read(conversation)
            restrict(received, conversationControl = true)
            current().let { if (it is AppResult.Failed) return it }
            if (received is AppResult.Failed) return failed(received.failure)
            val value = (received as AppResult.Success).value
            val platform =
                CollaborationNativePlatform.entries.firstOrNull {
                    it.name == build.platform.name.uppercase()
                } ?: return incomingFailure("unsupported_platform", FailureKind.Unavailable)
            if (
                !validCollaborationSpaceDirectTransport(value) ||
                    value.conversation != conversation ||
                    value.room != binding.room ||
                    value.device.id != runtime.deviceId ||
                    value.device.origin != CollaborationSpaceDeviceOrigin.LEGACY ||
                    value.device.platform != platform
            )
                return incomingFailure("transport_changed")
            observed = value
            return current()
        }

        suspend fun retain(): AppResult<CollaborationSpaceIncomingBatch> {
            prepare().let { if (it is AppResult.Failed) return it }
            val binding = requireNotNull(retained)
            for (id in ids) {
                val read = readEvent(id)
                if (read is AppResult.Failed) return read
                val planned = plan((read as AppResult.Success).value)
                if (planned is AppResult.Failed) return planned
                plans[id] = (planned as AppResult.Success).value
            }
            val originals =
                plans.values.flatMap {
                    when (it) {
                        is CollaborationIncomingHistoryPlan.Retain -> it.originals
                        is CollaborationIncomingHistoryPlan.MissingOriginal -> listOf(it.tombstone)
                        else -> emptyList()
                    }
                }
            val replacements =
                plans.values.filterIsInstance<CollaborationIncomingHistoryPlan.Retain>().flatMap {
                    it.replacements
                }
            if (originals.size > 16 || replacements.size > 16) return incomingFailure("bounds")
            if (originals.isNotEmpty()) {
                val saved = checked {
                    history.merge(access, conversation.id, binding.room.roomId, originals)
                }
                if (saved is AppResult.Failed) return saved
                if (!(saved as AppResult.Success).value) return incomingFailure("commit_changed")
            }
            if (replacements.isNotEmpty()) {
                val saved = checked {
                    edits.mergeEdits(access, conversation.id, binding.room.roomId, replacements)
                }
                if (saved is AppResult.Failed) return saved
                if (!(saved as AppResult.Success).value) return incomingFailure("commit_changed")
            }
            val latest = current()
            if (latest is AppResult.Failed) return latest
            return AppResult.Success(
                CollaborationSpaceIncomingBatch(
                    runtime,
                    conversation.id,
                    binding.room.roomId,
                    plans.map { (id, plan) ->
                        CollaborationIncomingHistoryResult(id, outcome(plan))
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
                val roomId = requireNotNull(retained).room.roomId
                val message = checked {
                    when (
                        val read = history.getByEvent(access, conversation.id, roomId, next.eventId)
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
                    history.getEventFact(access, conversation.id, roomId, next.eventId)
                }
                if (fact is AppResult.Failed) return fact
                val original = (message as AppResult.Success).value
                val alias = (fact as AppResult.Success).value
                if (original != null) {
                    if (alias == null) return incomingFailure("local_original_changed")
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
                    return incomingFailure("local_original_changed")
            }
            val target =
                acquired[next.eventId]?.let { AppResult.Success(it) } ?: readEvent(next.eventId)
            if (target is AppResult.Failed) return target
            return planCollaborationIncomingHistory(event, (target as AppResult.Success).value)
        }

        suspend fun readEvent(id: String): AppResult<CollaborationAcquiredEvent> {
            val binding = requireNotNull(retained)
            val read = checked(remote = true) { events.read(runtime, binding.room.roomId, id) }
            if (read is AppResult.Failed) return read
            val value = (read as AppResult.Success).value
            if (
                !validCollaborationSpaceIncomingSnapshot(
                    runtime,
                    binding,
                    requireNotNull(observed),
                    value,
                    id,
                )
            )
                return incomingFailure("event_owner_changed")
            val identity = value.physical.owner to value.physical.deviceId
            if (physicalIdentity != null && physicalIdentity != identity)
                return incomingFailure("event_owner_changed")
            physicalIdentity = identity
            acquired[id] = value.physical.event
            return AppResult.Success(value.physical.event)
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
                return incomingFailure("invalid_admission")
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
                return incomingFailure("revalidation_required", FailureKind.Unavailable)
            observed?.let {
                val elapsed = requireNotNull(transportStarted).elapsedNow()
                if (
                    elapsed < Duration.ZERO ||
                        elapsed >=
                            requireNotNull(it.device.gateway).expiresAt - it.device.observedAt
                )
                    return incomingFailure("revalidation_required", FailureKind.Unavailable)
            }
            return AppResult.Success(Unit)
        }

        suspend fun current(): AppResult<Unit> {
            admission().let { if (it is AppResult.Failed) return it }
            val expected = retained
            if (expected != null) {
                val binding = bindings.getByConversation(access, conversation.id)
                admission().let { if (it is AppResult.Failed) return it }
                if (binding is AppResult.Failed) return binding
                if ((binding as AppResult.Success).value != expected)
                    return incomingFailure("binding_changed")
            }
            val open = runtimes.isSpaceOpen(owner, runtime.deviceId, runtime.operationId)
            restrict(open)
            admission().let { if (it is AppResult.Failed) return it }
            if (open is AppResult.Failed) return failed(open.failure)
            if (!(open as AppResult.Success).value) return incomingFailure("runtime_changed")
            return AppResult.Success(Unit)
        }

        suspend fun <T> checked(
            remote: Boolean = false,
            operation: suspend () -> AppResult<T>,
        ): AppResult<T> {
            current().let { if (it is AppResult.Failed) return it }
            val result = operation()
            if (remote) restrict(result)
            current().let { if (it is AppResult.Failed) return it }
            return if (result is AppResult.Failed) failed(result.failure) else result
        }

        suspend fun restrict(result: AppResult<*>, conversationControl: Boolean = false) {
            if (
                result is AppResult.Failed &&
                    (if (conversationControl)
                        collaborationSpaceConversationResponseClosesDirectory(result.failure)
                    else collaborationSpaceDirectoryResponseClosesRead(result.failure))
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

private fun outcome(plan: CollaborationIncomingHistoryPlan): CollaborationIncomingHistoryOutcome =
    when (plan) {
        is CollaborationIncomingHistoryPlan.Retain ->
            CollaborationIncomingHistoryOutcome.Retained(
                plan.originals.map { it.eventId },
                plan.replacements.map { it.eventId },
            )
        is CollaborationIncomingHistoryPlan.AwaitDecryption ->
            CollaborationIncomingHistoryOutcome.AwaitDecryption(plan.eventId, plan.reason)
        is CollaborationIncomingHistoryPlan.AwaitRedaction ->
            CollaborationIncomingHistoryOutcome.AwaitRedaction(plan.eventId)
        is CollaborationIncomingHistoryPlan.MissingOriginal ->
            CollaborationIncomingHistoryOutcome.MissingOriginal(plan.tombstone.eventId)
        CollaborationIncomingHistoryPlan.Unsupported ->
            CollaborationIncomingHistoryOutcome.Unsupported
        is CollaborationIncomingHistoryPlan.AcquireTarget ->
            error("Target planning must finish before retention")
    }

private fun incomingFailure(reason: String, kind: FailureKind = FailureKind.Conflict) =
    collaborationRecoveryFailure("client.collaboration_incoming_$reason", kind)
