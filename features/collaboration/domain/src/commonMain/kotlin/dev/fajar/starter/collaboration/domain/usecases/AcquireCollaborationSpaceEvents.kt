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
    private val registrations: CollaborationSpaceRegistrationCommandRepository,
    private val sync: CollaborationSpaceSyncRepository,
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
        return when (
            val result =
                Acquisition(runtime, SpaceEventOrigin.Incoming(conversation), captured).run()
        ) {
            is AppResult.Failed -> result
            is AppResult.Success ->
                AppResult.Success(
                    CollaborationSpaceIncomingBatch(
                        runtime,
                        conversation.id,
                        result.value.roomId,
                        result.value.items,
                        requireNotNull(result.value.provider),
                    )
                )
        }
    }

    /** Captured historical references retain Space ownership, including Original room aliases. */
    suspend operator fun invoke(
        runtime: CollaborationSpaceRuntimeReady,
        history: CollaborationSpaceKeyHistory,
        references: CollaborationHistoryReferences,
    ): AppResult<CollaborationSpaceKeyPageBatch> {
        currentCoroutineContext().ensureActive()
        if (references.events.size > 16)
            return incomingFailure("invalid_request", FailureKind.Validation)
        val captured = references.copy(events = references.events.toList())
        if (!validCollaborationSpaceKeyHistoryReferences(runtime, history, captured))
            return incomingFailure("invalid_request", FailureKind.Validation)
        return when (
            val result =
                Acquisition(
                        runtime,
                        SpaceEventOrigin.KeyHistory(history, captured),
                        captured.events.map { it.event.eventId },
                    )
                    .run()
        ) {
            is AppResult.Failed -> result
            is AppResult.Success ->
                AppResult.Success(
                    CollaborationSpaceKeyPageBatch(runtime, history, captured, result.value.items)
                )
        }
    }

    /** Mutable admission, raw snapshots and retention plans never outlive this invocation. */
    private inner class Acquisition(
        val runtime: CollaborationSpaceRuntimeReady,
        val origin: SpaceEventOrigin,
        val ids: List<String>,
    ) {
        val owner = runtime.owner
        val access = owner.localDirectoryAccess()
        val conversation = origin.conversation
        val requestedAt = clock.now()
        val started = timeSource.markNow()
        var nativeDeadline: Instant? = null
        var restriction: Failure? = null
        var retained: CollaborationSpaceRoomBinding? = null
        var observed: CollaborationSpaceDirectTransport? = null
        var transportStarted: TimeMark? = null
        var physicalIdentity: Pair<CollaborationOwner, String>? = null
        var registered: CollaborationSpaceRegistrationRecord? = null
        val acquired = mutableMapOf<String, CollaborationAcquiredEvent>()
        val plans = linkedMapOf<String, CollaborationIncomingHistoryPlan>()
        val retainedOutcomes = mutableMapOf<String, CollaborationIncomingHistoryOutcome>()

        suspend fun run(): AppResult<AcquiredContent> {
            try {
                return withTimeoutOrNull(15_000) {
                    if (origin is SpaceEventOrigin.KeyHistory) {
                        admission().let { if (it is AppResult.Failed) return@withTimeoutOrNull it }
                        val registration = readRegistration()
                        if (registration is AppResult.Failed) return@withTimeoutOrNull registration
                        registered = (registration as AppResult.Success).value
                    }
                    retain()
                } ?: failed(incomingFailure("timeout", FailureKind.Timeout).failure)
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
                    binding.conversation != conversation ||
                    (origin is SpaceEventOrigin.KeyHistory && binding != origin.history.binding)
            )
                return incomingFailure("binding_changed")
            retained = binding
            current().let { if (it is AppResult.Failed) return it }
            if (origin is SpaceEventOrigin.KeyHistory) return AppResult.Success(Unit)
            val incoming = (origin as SpaceEventOrigin.Incoming).value
            transportStarted = timeSource.markNow()
            val received = transport.read(incoming)
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
                    value.conversation != incoming ||
                    value.room != binding.room ||
                    value.device.id != runtime.deviceId ||
                    value.device.origin != CollaborationSpaceDeviceOrigin.LEGACY ||
                    value.device.platform != platform
            )
                return incomingFailure("transport_changed")
            observed = value
            return current()
        }

        suspend fun retain(): AppResult<AcquiredContent> {
            prepare().let { if (it is AppResult.Failed) return it }
            val binding = requireNotNull(retained)
            for (id in ids) {
                val local = retainedReference(id)
                if (local is AppResult.Failed) return local
                val outcome = (local as AppResult.Success).value
                if (outcome != null) {
                    retainedOutcomes[id] = outcome
                    continue
                }
                val read =
                    if (origin is SpaceEventOrigin.KeyHistory)
                        acquired[id]?.let { AppResult.Success(it) } ?: readEvent(id)
                    else readEvent(id)
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
                    .let { if (origin is SpaceEventOrigin.KeyHistory) it.distinct() else it }
            val replacements =
                plans.values
                    .filterIsInstance<CollaborationIncomingHistoryPlan.Retain>()
                    .flatMap { it.replacements }
                    .let { if (origin is SpaceEventOrigin.KeyHistory) it.distinct() else it }
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
                AcquiredContent(
                    binding.room.roomId,
                    ids.map { id ->
                        CollaborationIncomingHistoryResult(
                            id,
                            retainedOutcomes[id]
                                ?: collaborationIncomingHistoryOutcome(plans.getValue(id)),
                        )
                    },
                    physicalIdentity?.let {
                        CollaborationHistoryProviderIdentity(it.first.companyId, it.second)
                    },
                )
            )
        }

        suspend fun retainedReference(id: String): AppResult<CollaborationIncomingHistoryOutcome?> {
            val page =
                (origin as? SpaceEventOrigin.KeyHistory)?.references
                    ?: return AppResult.Success(null)
            val reference = page.events.single { it.event.eventId == id }
            val roomId = requireNotNull(retained).room.roomId
            val found = checked { history.getEventFact(access, conversation.id, roomId, id) }
            if (found is AppResult.Failed) return found
            val fact = (found as AppResult.Success).value
            if (fact != null) {
                if (!matchesCollaborationRetainedOriginal(reference.event, fact))
                    return incomingFailure("local_original_changed")
                return AppResult.Success(
                    CollaborationIncomingHistoryOutcome.Retained(listOf(id), emptyList())
                )
            }
            val foundEdit = checked {
                edits.getReplacementFact(access, conversation.id, roomId, id)
            }
            if (foundEdit is AppResult.Failed) return foundEdit
            val edit = (foundEdit as AppResult.Success).value ?: return AppResult.Success(null)
            if (!matchesCollaborationRetainedReplacement(reference.event, edit))
                return incomingFailure("local_replacement_changed")
            return AppResult.Success(
                CollaborationIncomingHistoryOutcome.Retained(emptyList(), listOf(id))
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
            val valid =
                when (origin) {
                    is SpaceEventOrigin.Incoming ->
                        validCollaborationSpaceIncomingSnapshot(
                            runtime,
                            binding,
                            requireNotNull(observed),
                            value,
                            id,
                        )
                    is SpaceEventOrigin.KeyHistory ->
                        validCollaborationSpaceKeyHistorySnapshot(
                            runtime,
                            origin.history,
                            requireNotNull(registered),
                            requireNotNull(
                                CollaborationNativePlatform.entries.firstOrNull {
                                    it.name == build.platform.name.uppercase()
                                }
                            ),
                            value,
                            id,
                        )
                }
            if (!valid) return incomingFailure("event_owner_changed")
            val reference =
                (origin as? SpaceEventOrigin.KeyHistory)?.references?.events?.singleOrNull {
                    it.event.eventId == id
                }
            if (
                reference != null &&
                    (value.physical.event.sender != reference.event.sender ||
                        value.physical.event.timestamp != reference.event.timestamp)
            )
                return incomingFailure("event_source_changed")
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
            if (
                origin is SpaceEventOrigin.KeyHistory &&
                    sync.incomingSource(runtime) != origin.history.source
            )
                return incomingFailure("source_changed", FailureKind.Unavailable)
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
            val expected = retained ?: (origin as? SpaceEventOrigin.KeyHistory)?.history?.binding
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
            if (origin is SpaceEventOrigin.KeyHistory) {
                val registration = readRegistration()
                if (registration is AppResult.Failed) return registration
                if ((registration as AppResult.Success).value != registered)
                    return incomingFailure("registration_changed")
            }
            return AppResult.Success(Unit)
        }

        suspend fun readRegistration(): AppResult<CollaborationSpaceRegistrationRecord> {
            val keyHistory = (origin as SpaceEventOrigin.KeyHistory).history
            val loaded = registrations.current(access, owner.parent.nativeSessionId)
            admission().let { if (it is AppResult.Failed) return it }
            if (loaded is AppResult.Failed) return loaded
            val value =
                (loaded as AppResult.Success).value
                    ?: return incomingFailure("registration_changed")
            val platform =
                CollaborationNativePlatform.entries.firstOrNull {
                    it.name == build.platform.name.uppercase()
                } ?: return incomingFailure("unsupported_platform", FailureKind.Unavailable)
            if (
                registeredCollaborationSpaceOriginalIncomingSdk(
                    runtime,
                    keyHistory.source,
                    value,
                    platform,
                ) == null || value.command.provider.serverName != keyHistory.binding.room.serverName
            )
                return incomingFailure("registration_changed")
            return AppResult.Success(value)
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

    private sealed interface SpaceEventOrigin {
        val conversation: CollaborationSpaceConversationMetadata

        data class Incoming(val value: CollaborationSpaceConversation) : SpaceEventOrigin {
            override val conversation = value.localMetadata()
        }

        data class KeyHistory(
            val history: CollaborationSpaceKeyHistory,
            val references: CollaborationHistoryReferences,
        ) : SpaceEventOrigin {
            override val conversation = history.binding.conversation
        }
    }

    private data class AcquiredContent(
        val roomId: String,
        val items: List<CollaborationIncomingHistoryResult>,
        val provider: CollaborationHistoryProviderIdentity?,
    )
}

private fun incomingFailure(reason: String, kind: FailureKind = FailureKind.Conflict) =
    collaborationRecoveryFailure("client.collaboration_incoming_$reason", kind)
