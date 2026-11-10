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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One admitted original-only writer. Never creates a room, key, message or replacement intent. */
class DeliverCollaborationSpaceOriginalMessages(
    private val bindings: CollaborationSpaceRoomBindingRepository,
    private val transport: CollaborationSpaceDirectTransportRepository,
    private val runtimes: CollaborationSpaceRuntimeRepository,
    private val history: CollaborationHistoryRepository,
    private val originalHistory: CollaborationSpaceOriginalHistoryRepository,
    private val messages: CollaborationMessageRepository,
    private val receipts: CollaborationReceiptRepository,
    private val completions: CollaborationMessageCompletionRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val clock: Clock,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private val execution = Mutex()

    suspend operator fun invoke(
        runtime: CollaborationSpaceRuntimeReady,
        conversation: CollaborationSpaceConversation,
    ): AppResult<CollaborationDeliveryReport> {
        currentCoroutineContext().ensureActive()
        if (!execution.tryLock()) return originalDeliveryFailure("busy")
        try {
            if (
                runtime.owner != conversation.owner ||
                    !validCollaborationSpaceConversation(conversation)
            )
                return originalDeliveryFailure("invalid_request", FailureKind.Validation)
            return Window(runtime, conversation).run()
        } finally {
            execution.unlock()
        }
    }

    /**
     * All mutable state belongs to this invocation, including late callback and cleanup ownership.
     */
    private inner class Window(
        val runtime: CollaborationSpaceRuntimeReady,
        val conversation: CollaborationSpaceConversation,
    ) {
        val owner = runtime.owner
        val requestedAt = clock.now()
        val started = timeSource.markNow()
        var nativeDeadline: Instant? = null
        var restriction: Failure? = null
        var writer: CollaborationSpaceOriginalQueueLease? = null
        lateinit var retained: CollaborationSpaceRoomBinding
        lateinit var observed: CollaborationSpaceDirectTransport
        lateinit var snapshot: CollaborationSpaceOriginalQueueSnapshot
        lateinit var original: CollaborationSpaceOriginalConversation
        lateinit var access: CollaborationLocalAccess
        var preparing = true
        val projection = Mutex()
        var records = emptyList<CollaborationMessageRecord>()
        var matched = emptyList<CollaborationMatchedOriginalMessage>()

        suspend fun run(): AppResult<CollaborationDeliveryReport> {
            var result: AppResult<CollaborationDeliveryReport>? = null
            try {
                result =
                    withTimeoutOrNull(15_000) { prepare() }
                        ?: failure("preparation_timeout", FailureKind.Timeout)
                preparing = false
                if (
                    result is AppResult.Success &&
                        observed.recipientReady &&
                        matched.any { it.completion == null }
                )
                    result =
                        withTimeoutOrNull(30_000) { deliver() }
                            ?: failure("delivery_timeout", FailureKind.Timeout)
            } finally {
                // close owns pause/drain before release. Do not truncate that ownership with the
                // preparation deadline or let cleanup replace a prior failure/cancellation.
                val closed =
                    try {
                        withContext(NonCancellable) { writer?.close() }
                    } catch (_: Exception) {
                        failure("cleanup_failed", FailureKind.Storage)
                    }
                if (restriction != null) {
                    withContext(NonCancellable) {
                        try {
                            withTimeoutOrNull(5_000) { runtimes.closeSpace(owner) }
                        } catch (_: Exception) {
                            /* Preserve the admitted denial. */
                        }
                    }
                }
                if (result is AppResult.Success && closed is AppResult.Failed) result = closed
            }
            return requireNotNull(result)
        }

        suspend fun prepare(): AppResult<CollaborationDeliveryReport> {
            val admitted = admission()
            if (admitted is AppResult.Failed) return admitted
            val binding = checked {
                bindings.getByConversation(owner.localDirectoryAccess(), conversation.id)
            }
            if (binding is AppResult.Failed) return binding
            retained = (binding as AppResult.Success).value ?: return failure("binding_missing")
            if (
                !validCollaborationSpaceRoomBinding(owner.localDirectoryAccess(), retained) ||
                    retained.conversation != conversation.localMetadata() ||
                    retained.original == null
            )
                return failure("binding_changed")
            val read = transport.read(conversation)
            restrictRemote(read)
            val afterTransport = admission()
            if (afterTransport is AppResult.Failed) return afterTransport
            if (read is AppResult.Failed) return read
            observed = (read as AppResult.Success).value
            val platform =
                CollaborationNativePlatform.entries.firstOrNull {
                    it.name == build.platform.name.uppercase()
                } ?: return failure("unsupported_platform", FailureKind.Unavailable)
            if (
                !validCollaborationSpaceDirectTransport(observed) ||
                    observed.conversation != conversation ||
                    observed.room != retained.room ||
                    observed.device.id != runtime.deviceId ||
                    observed.device.origin != CollaborationSpaceDeviceOrigin.LEGACY ||
                    observed.device.platform != platform ||
                    !validCollaborationOriginalQueueRequest(
                        runtime,
                        requireNotNull(retained.original).conversation.position,
                    )
            )
                return failure("transport_changed")
            val acquired = runtimes.acquireOriginalQueue(runtime, retained.room.roomId)
            if (acquired is AppResult.Success) writer = acquired.value
            val afterAcquire = admission()
            if (afterAcquire is AppResult.Failed) return afterAcquire
            if (acquired is AppResult.Failed) return acquired
            val queue = requireNotNull(writer)
            val captured = captureSnapshot()
            if (captured is AppResult.Failed) return captured
            snapshot = (captured as AppResult.Success).value
            val mapping =
                matchCollaborationOriginalTransport(
                    runtime,
                    conversation,
                    retained,
                    observed,
                    snapshot,
                    platform,
                )
            if (mapping is AppResult.Failed) return mapping
            original = (mapping as AppResult.Success).value
            if (
                queue.runtime != runtime ||
                    queue.binding != snapshot.original.binding ||
                    queue.device != snapshot.original.device
            )
                return failure("writer_changed")
            val bindingOwner = queue.binding
            access =
                CollaborationLocalAccess(
                    owner.parent.sessionId,
                    bindingOwner.audience,
                    bindingOwner.accountId,
                    bindingOwner.companyId,
                )
            val repaired = reconcile()
            if (repaired is AppResult.Failed) return repaired
            val recovered = recoverMissingReceipts()
            if (recovered is AppResult.Failed) return recovered
            val settled = settle()
            if (settled is AppResult.Failed) return settled
            return AppResult.Success(
                collaborationDeliveryReport(
                    records,
                    if (matched.isEmpty()) CollaborationQueueDeliveryReason.DRAINED
                    else CollaborationQueueDeliveryReason.STOPPED,
                )
            )
        }

        suspend fun admission(): AppResult<Unit> {
            currentCoroutineContext().ensureActive()
            val gate = directory.admission(owner.localDirectoryAccess())
            if (gate is AppResult.Success && validCollaborationSpaceDirectoryAdmission(gate.value))
                gate.value.denial?.let {
                    restriction = retainCollaborationSpaceConversationFailure(restriction, it)
                }
            val captured =
                sessions.captureCollaborationSpaceParent(owner.parent, server, build, clock)
            currentCoroutineContext().ensureActive()
            restriction?.let {
                return AppResult.Failed(it)
            }
            if (captured is AppResult.Failed) return captured
            if (gate is AppResult.Failed) return gate
            if (!validCollaborationSpaceDirectoryAdmission((gate as AppResult.Success).value))
                return failure("invalid_admission")
            val currentDeadline =
                Instant.fromEpochMilliseconds(
                    (captured as AppResult.Success).value.binding.expiresAtEpochMillis
                )
            nativeDeadline = nativeDeadline?.let { minOf(it, currentDeadline) } ?: currentDeadline
            val now = clock.now()
            val budget = if (preparing) 15.seconds else 45.seconds
            if (
                now < requestedAt ||
                    now >= requireNotNull(nativeDeadline) ||
                    now - requestedAt >= budget ||
                    started.elapsedNow() >=
                        minOf(budget, requireNotNull(nativeDeadline) - requestedAt)
            )
                return failure("revalidation_required", FailureKind.Unavailable)
            return AppResult.Success(Unit)
        }

        suspend fun current(): AppResult<Unit> {
            val admitted = admission()
            if (admitted is AppResult.Failed) return admitted
            val open = runtimes.isSpaceOpen(owner, runtime.deviceId, runtime.operationId)
            val afterOpen = admission()
            if (afterOpen is AppResult.Failed) return afterOpen
            if (open is AppResult.Failed) return open
            return if ((open as AppResult.Success).value) AppResult.Success(Unit)
            else failure("runtime_changed")
        }

        suspend fun <T> checked(operation: suspend () -> AppResult<T>): AppResult<T> {
            val value = operation()
            val latest = current()
            return if (latest is AppResult.Failed) latest else value
        }

        suspend fun restrictRemote(result: AppResult<*>) {
            if (
                result is AppResult.Failed &&
                    collaborationSpaceConversationResponseClosesDirectory(result.failure)
            ) {
                restriction =
                    retainCollaborationSpaceConversationFailure(restriction, result.failure)
                withContext(NonCancellable) {
                    withTimeoutOrNull(2_000) {
                        directory.restrict(owner.localDirectoryAccess(), result.failure)
                    }
                }
            }
        }

        suspend fun captureSnapshot(): AppResult<CollaborationSpaceOriginalQueueSnapshot> =
            checked {
                when (val read = requireNotNull(writer).snapshot()) {
                    is AppResult.Failed -> read
                    is AppResult.Success ->
                        if (read.value.original.messages.size > 128) failure("inventory_bounds")
                        else
                            AppResult.Success(
                                read.value.copy(
                                    original =
                                        read.value.original.copy(
                                            messages = read.value.original.messages.toList()
                                        )
                                )
                            )
                }
            }

        /** Read the whole bounded ledger before projecting; a journal may precede archival. */
        suspend fun reconcile(): AppResult<Unit> = projection.withLock { reconcileOriginals() }

        /** Recover only disappeared originals; ordinary queued sends never acquire history. */
        suspend fun recoverMissingReceipts(): AppResult<Unit> {
            val missing = collaborationSpaceMissingOriginalReceipts(snapshot, records)
            if (missing.isEmpty()) return AppResult.Success(Unit)
            if (
                missing.any {
                    val state = it.state as CollaborationMessageState.Staged
                    it.intent.owner != access.messageOwner() ||
                        it.intent.conversationId != original.conversation.position.conversationId ||
                        !validCollaborationMessageState(state) ||
                        state.origin?.sender != observed.matrixUserId
                }
            )
                return failure("history_command_changed")
            val recovered = mutableListOf<CollaborationMessageReceipt>()
            val absent = mutableListOf<CollaborationMessageRecord>()
            for (record in missing) {
                val state = record.state as CollaborationMessageState.Staged
                val local = checked {
                    when (
                        val read =
                            history.getByLogicalId(
                                access,
                                original.conversation.position.conversationId,
                                state.device.roomId,
                                requireNotNull(state.origin).sender,
                                record.intent.id,
                            )
                    ) {
                        is AppResult.Failed -> read
                        is AppResult.Success ->
                            if (read.value?.eventIds?.size?.let { it > 128 } == true)
                                failure("receipt_bounds")
                            else
                                AppResult.Success(
                                    read.value?.let { it.copy(eventIds = it.eventIds.toList()) }
                                )
                    }
                }
                if (local is AppResult.Failed) return local
                val found = (local as AppResult.Success).value
                if (found == null) absent += record
                else
                    recovered +=
                        recoverCollaborationOriginalReceipt(record, found)
                            ?: return failure("history_receipt_changed")
            }
            if (absent.isNotEmpty()) {
                val acquired = captureRecoveryHistory()
                if (acquired is AppResult.Failed) return acquired
                val observedHistory = (acquired as AppResult.Success).value
                if (!validCollaborationSpaceOriginalHistory(observedHistory, snapshot))
                    return failure("history_changed")
                val changes = observedHistory.window?.changes.orEmpty()
                for (record in absent) {
                    collaborationSpaceOriginalHistoryReceipt(record, changes)?.let {
                        recovered += it
                    }
                }
            }
            if (recovered.isEmpty()) return AppResult.Success(Unit)
            val retained =
                withContext(NonCancellable) {
                    withTimeoutOrNull(5_000) {
                        for (batch in recovered.chunked(32)) {
                            val saved =
                                receipts.append(
                                    access,
                                    original.conversation.position.conversationId,
                                    batch,
                                )
                            if (saved is AppResult.Failed) return@withTimeoutOrNull saved
                            if (!(saved as AppResult.Success).value)
                                return@withTimeoutOrNull failure("receipt_write_changed")
                        }
                        AppResult.Success(Unit)
                    } ?: failure("receipt_pending", FailureKind.Storage)
                }
            val latest = current()
            if (latest is AppResult.Failed) return latest
            if (retained is AppResult.Failed) return retained
            return reconcile()
        }

        suspend fun captureRecoveryHistory(): AppResult<CollaborationSpaceOriginalHistorySnapshot> =
            checked {
                when (val read = originalHistory.read(runtime, retained.room.roomId)) {
                    is AppResult.Failed -> read
                    is AppResult.Success -> {
                        val value = read.value
                        val window = value.window
                        if (
                            window != null &&
                                (window.changes.size > 128 ||
                                    window.unresolvedEventIds.size > 128 ||
                                    window.edits.size > 128)
                        )
                            failure("history_bounds")
                        else
                            AppResult.Success(
                                value.copy(
                                    window =
                                        window?.copy(
                                            changes = window.changes.toList(),
                                            unresolvedEventIds = window.unresolvedEventIds.toList(),
                                            edits = window.edits.toList(),
                                        )
                                )
                            )
                    }
                }
            }

        suspend fun reconcileOriginals(): AppResult<Unit> {
            val active = checked {
                when (
                    val read =
                        messages
                            .observe(access, original.conversation.position.conversationId)
                            .first()
                ) {
                    is AppResult.Failed -> read
                    is AppResult.Success ->
                        if (read.value.size > 128) failure("command_bounds")
                        else AppResult.Success(read.value.toList())
                }
            }
            if (active is AppResult.Failed) return active
            val journal = checked {
                when (
                    val read = receipts.read(access, original.conversation.position.conversationId)
                ) {
                    is AppResult.Failed -> read
                    is AppResult.Success ->
                        if (!validCollaborationReceiptLedger(read.value)) failure("receipt_bounds")
                        else
                            AppResult.Success(
                                read.value.map { it.copy(eventIds = it.eventIds.toList()) }
                            )
                }
            }
            if (journal is AppResult.Failed) return journal
            val known = (journal as AppResult.Success).value
            val retainedRecords = (active as AppResult.Success).value
            if (
                missingCollaborationReceiptIds(
                    access,
                    original.conversation.position.conversationId,
                    retainedRecords,
                    known,
                ) == null
            )
                return failure("receipt_owner")
            val all = retainedRecords.associateBy { it.intent.id }.toMutableMap()
            val ids =
                snapshot.original.messages.map { requireNotNull(it.logicalId) } +
                    known.map { it.intent.id }
            for (id in ids.distinct().filterNot { it in all }) {
                val read = checked { messages.get(access, id) }
                if (read is AppResult.Failed) return read
                val record = (read as AppResult.Success).value ?: return failure("command_missing")
                if (record.intent.id != id) return failure("command_changed")
                all[id] = record
            }
            val evidence = mutableMapOf<String, CollaborationMessageCompletion>()
            for (record in all.values.filter { it.state is CollaborationMessageState.Sent }) {
                val read = checked {
                    when (val value = completions.readCompletion(access, record.intent.id)) {
                        is AppResult.Failed -> value
                        is AppResult.Success ->
                            if (value.value?.eventIds?.size?.let { it > 128 } == true)
                                failure("receipt_bounds")
                            else
                                AppResult.Success(
                                    value.value?.let { it.copy(eventIds = it.eventIds.toList()) }
                                )
                    }
                }
                if (read is AppResult.Failed) return read
                val proof =
                    collaborationOriginalAcknowledgement(
                        record,
                        known.find { it.intent.id == record.intent.id },
                        (read as AppResult.Success).value,
                    ) ?: return failure("acknowledgement_missing")
                evidence[record.intent.id] = proof
            }
            val beforeProjection = matchCaptured(all.values.toList(), evidence.values.toList())
            if (beforeProjection is AppResult.Failed) return beforeProjection
            val projected = project(all, known, evidence)
            if (projected is AppResult.Failed) return projected
            records = all.values.toList()
            val matching = matchCaptured(records, evidence.values.toList())
            if (matching is AppResult.Failed) return matching
            matched = (matching as AppResult.Success).value
            return AppResult.Success(Unit)
        }

        fun matchCaptured(
            values: List<CollaborationMessageRecord>,
            proofs: List<CollaborationMessageCompletion>,
        ): AppResult<List<CollaborationMatchedOriginalMessage>> {
            val ids = snapshot.original.messages.map { it.logicalId }.toSet()
            return matchCollaborationOriginalMessages(
                runtime,
                original,
                observed.matrixUserId,
                snapshot,
                values.filter { it.intent.id in ids },
                proofs.filter { it.record.intent.id in ids },
            )
        }

        suspend fun project(
            all: MutableMap<String, CollaborationMessageRecord>,
            known: List<CollaborationMessageReceipt>,
            evidence: MutableMap<String, CollaborationMessageCompletion>,
        ): AppResult<Unit> {
            val changes =
                mutableListOf<Pair<CollaborationMessageRecord, CollaborationMessageState.Sent>>()
            for (receipt in known) {
                val record = all[receipt.intent.id] ?: return failure("command_missing")
                val state =
                    evidence[receipt.intent.id]?.let {
                        collaborationCompletedReceiptState(it, receipt)
                    }
                        ?: collaborationReceiptState(record, receipt)
                        ?: return failure("receipt_changed")
                changes += record to state
            }
            for ((record, state) in changes) {
                if (record.state == state) continue
                val saved = checked { messages.update(access, record, state) }
                // Read back a possibly committed CAS, including false or a lost write response.
                val read = checked { messages.get(access, record.intent.id) }
                if (read is AppResult.Failed) return read
                val confirmed = (read as AppResult.Success).value
                if (confirmed != record.copy(revision = record.revision + 1, state = state))
                    return if (saved is AppResult.Failed) saved else failure("projection_changed")
                all[record.intent.id] = confirmed
                evidence[record.intent.id] =
                    collaborationOriginalAcknowledgement(
                        confirmed,
                        known.first { it.intent.id == record.intent.id },
                        null,
                    ) ?: return failure("receipt_changed")
            }
            return AppResult.Success(Unit)
        }

        suspend fun settle(): AppResult<Unit> {
            val queue = requireNotNull(writer)
            for (value in matched.filter { it.completion != null }) {
                val event =
                    collaborationQueueCompletionEvent(
                        access,
                        original.conversation.position.conversationId,
                        queue.device,
                        value.original,
                        requireNotNull(value.completion),
                    ) ?: return failure("receipt_changed")
                val result = checked { queue.settleConfirmed(value.original, event) }
                if (result is AppResult.Failed) return result
            }
            val remaining = matched.filter { it.completion == null }
            val expected =
                snapshot.copy(
                    original = snapshot.original.copy(messages = remaining.map { it.original })
                )
            val captured = captureSnapshot()
            if (captured is AppResult.Failed) return captured
            val actual = (captured as AppResult.Success).value
            if (
                actual.runtime != expected.runtime ||
                    actual.original.binding != expected.original.binding ||
                    actual.original.device != expected.original.device ||
                    actual.original.messages.size != expected.original.messages.size ||
                    actual.original.messages.associateBy { it.transactionId } !=
                        expected.original.messages.associateBy { it.transactionId }
            )
                return failure("inventory_changed")
            snapshot = actual
            matched = remaining
            return AppResult.Success(Unit)
        }

        suspend fun deliver(): AppResult<CollaborationDeliveryReport> {
            val admitted = current()
            if (admitted is AppResult.Failed) return admitted
            val invocation = currentCoroutineContext()
            val mapped = matched.associate { it.original.transactionId to it.record }
            val queue = requireNotNull(writer)
            val captured = snapshot.original.messages.map { it.transactionId }.toSet()
            val result =
                queue.resume(snapshot) { progress ->
                    val saved = retainObserved(progress, mapped, captured)
                    if (saved is AppResult.Failed) return@resume saved
                    if (!invocation.isActive || !currentCoroutineContext().isActive)
                        return@resume AppResult.Success(Unit)
                    try {
                        withContext(invocation) {
                            val latest = current()
                            if (latest is AppResult.Failed) latest else reconcile()
                        }
                    } catch (cancelled: CancellationException) {
                        if (invocation.isActive) throw cancelled
                        AppResult.Success(Unit)
                    }
                }
            if (result is AppResult.Failed) {
                val latest = current()
                return if (latest is AppResult.Failed) latest else result
            }
            val outcome = (result as AppResult.Success).value
            val saved = retainObserved(outcome, mapped, captured)
            val latest = current()
            if (latest is AppResult.Failed) return latest
            if (saved is AppResult.Failed) return saved
            val repaired = reconcile()
            if (repaired is AppResult.Failed) return repaired
            return AppResult.Success(collaborationDeliveryReport(records, outcome.reason))
        }

        suspend fun retainObserved(
            progress: CollaborationQueueDelivery,
            mapped: Map<String, CollaborationMessageRecord>,
            captured: Set<String>,
        ): AppResult<Unit> {
            val batch =
                collaborationDeliveryReceipts(
                    progress,
                    mapped,
                    requireNotNull(writer).device,
                    captured,
                ) ?: return failure("receipt_changed")
            return withContext(NonCancellable) {
                withTimeoutOrNull(5_000) {
                    for (portion in batch.chunked(32)) {
                        val saved =
                            receipts.append(
                                access,
                                original.conversation.position.conversationId,
                                portion,
                            )
                        if (saved is AppResult.Failed) return@withTimeoutOrNull saved
                        if (!(saved as AppResult.Success).value)
                            return@withTimeoutOrNull failure("receipt_write_changed")
                    }
                    AppResult.Success(Unit)
                } ?: failure("receipt_pending", FailureKind.Storage)
            }
        }

        fun failure(code: String, kind: FailureKind = FailureKind.Conflict) =
            restriction?.let { AppResult.Failed(it) } ?: originalDeliveryFailure(code, kind)
    }
}

private fun originalDeliveryFailure(code: String, kind: FailureKind = FailureKind.Conflict) =
    collaborationSetupFailure("client.collaboration_original_$code", kind)
