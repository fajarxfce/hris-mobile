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

/** Retains an original Space intent before a single paused enqueue; never resumes delivery. */
class StageCollaborationSpaceText(
    private val messages: CollaborationSpaceMessageRepository,
    private val companyMessages: CollaborationMessageRepository,
    private val receipts: CollaborationSpaceReceiptRepository,
    private val companyReceipts: CollaborationReceiptRepository,
    private val completions: CollaborationSpaceMessageCompletionRepository,
    private val companyCompletions: CollaborationMessageCompletionRepository,
    private val bindings: CollaborationSpaceRoomBindingRepository,
    private val transport: CollaborationSpaceDirectTransportRepository,
    private val queues: CollaborationSpaceMessageQueueRepository,
    private val runtimes: CollaborationSpaceRuntimeRepository,
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
        messageId: String,
    ): AppResult<CollaborationSpaceMessageRecord> {
        currentCoroutineContext().ensureActive()
        if (
            !validCollaborationSpaceConversation(conversation, runtime.owner) ||
                !validCollaborationSpaceConversationId(messageId)
        )
            return stageFailure("invalid_request", FailureKind.Validation)
        if (!execution.tryLock())
            return collaborationSetupFailure("client.collaboration_queue_busy")
        try {
            return Window(runtime, conversation, messageId).run()
        } finally {
            execution.unlock()
        }
    }

    /** Every mutable fact and native child belongs to this one admitted invocation. */
    private inner class Window(
        val runtime: CollaborationSpaceRuntimeReady,
        val conversation: CollaborationSpaceConversation,
        val messageId: String,
    ) {
        val owner = runtime.owner
        val access = owner.localDirectoryAccess()
        val requestedAt = clock.now()
        val started = timeSource.markNow()
        var live = false
        var nativeDeadline: Instant? = null
        var restriction: Failure? = null
        var writer: CollaborationSpaceMessageQueueLease? = null
        lateinit var binding: CollaborationSpaceRoomBinding
        lateinit var observed: CollaborationSpaceDirectTransport
        lateinit var snapshot: CollaborationSpaceOriginalQueueSnapshot
        var companyAccess: CollaborationLocalAccess? = null
        var known = emptyList<CollaborationSpaceMessageReceipt>()
        var oldKnown = emptyList<CollaborationMessageReceipt>()

        suspend fun run(): AppResult<CollaborationSpaceMessageRecord> {
            var result: AppResult<CollaborationSpaceMessageRecord>? = null
            try {
                result =
                    withTimeoutOrNull(15_000) { prepare() }
                        ?: failure("timeout", FailureKind.Timeout)
            } finally {
                val closed =
                    try {
                        withContext(NonCancellable) { writer?.close() }
                    } catch (_: Exception) {
                        failure("cleanup_failed", FailureKind.Storage)
                    }
                if (restriction != null)
                    withContext(NonCancellable) {
                        try {
                            withTimeoutOrNull(5_000) { runtimes.closeSpace(owner) }
                        } catch (_: Exception) {
                            /* Preserve the original denial or cancellation. */
                        }
                    }
                if (result is AppResult.Success && closed is AppResult.Failed) result = closed
            }
            return requireNotNull(result)
        }

        suspend fun prepare(): AppResult<CollaborationSpaceMessageRecord> {
            val admitted = current()
            if (admitted is AppResult.Failed) return admitted
            val read = checked {
                when (val value = messages.get(access, messageId)) {
                    is AppResult.Failed -> value
                    is AppResult.Success ->
                        AppResult.Success(
                            value.value?.let { record ->
                                val state = record.state
                                if (state is CollaborationMessageState.Staging)
                                    record.copy(state = state.copy(before = state.before.toSet()))
                                else record
                            }
                        )
                }
            }
            if (read is AppResult.Failed) return read
            val record = (read as AppResult.Success).value ?: return failure("message_missing")
            if (
                !validCollaborationSpaceMessageRecord(access, record) ||
                    record.intent.id != messageId ||
                    record.intent.conversationId != conversation.id
            )
                return failure("message_changed")
            if (
                record.state is CollaborationMessageState.Staged ||
                    record.state is CollaborationMessageState.Sent ||
                    record.state == CollaborationMessageState.Cancelled
            )
                return AppResult.Success(record)
            live = true
            val native = current()
            if (native is AppResult.Failed) return native
            val retained = checked { bindings.getByConversation(access, conversation.id) }
            if (retained is AppResult.Failed) return retained
            binding = (retained as AppResult.Success).value ?: return failure("binding_missing")
            if (
                !validCollaborationSpaceRoomBinding(access, binding) ||
                    binding.conversation != conversation.localMetadata() ||
                    record.intent.editTarget?.roomId?.let { it != binding.room.roomId } == true
            )
                return failure("binding_changed")
            val remote = transport.read(conversation)
            restrictRemote(remote)
            val afterTransport = current()
            if (afterTransport is AppResult.Failed) return afterTransport
            if (remote is AppResult.Failed) return remote
            observed = (remote as AppResult.Success).value
            val platform =
                CollaborationNativePlatform.entries.firstOrNull {
                    it.name == build.platform.name.uppercase()
                } ?: return failure("unsupported_platform", FailureKind.Unavailable)
            if (
                !validCollaborationSpaceDirectTransport(observed) ||
                    observed.conversation != conversation ||
                    observed.room != binding.room ||
                    observed.device.id != runtime.deviceId ||
                    observed.device.origin != CollaborationSpaceDeviceOrigin.LEGACY ||
                    observed.device.platform != platform
            )
                return failure("transport_changed")
            if (record.state == CollaborationMessageState.Queued && !observed.recipientReady)
                return collaborationSetupFailure(
                    "client.collaboration_recipient_unavailable",
                    FailureKind.Conflict,
                )
            val acquired = queues.acquireSpaceMessageQueue(runtime, binding.room.roomId)
            if (acquired is AppResult.Success) writer = acquired.value
            val afterAcquire = current()
            if (afterAcquire is AppResult.Failed) return afterAcquire
            if (acquired is AppResult.Failed) return acquired
            val captured = capture()
            if (captured is AppResult.Failed) return captured
            snapshot = (captured as AppResult.Success).value
            val mapped =
                matchCollaborationSpaceQueueTransport(
                    runtime,
                    conversation,
                    binding,
                    observed,
                    snapshot,
                    platform,
                )
            if (mapped is AppResult.Failed) return mapped
            val scope =
                collaborationMixedQueueCompanyAccess(runtime, conversation, binding, snapshot)
            if (scope is AppResult.Failed) return scope
            companyAccess = (scope as AppResult.Success).value
            val inventory = inspectCommands(record)
            if (inventory is AppResult.Failed) return inventory
            return stage(record)
        }

        suspend fun admission(): AppResult<Unit> {
            currentCoroutineContext().ensureActive()
            val local = sessions.guardSpaceRecoveryOwner(server, owner)
            if (local is AppResult.Failed) return local
            val gate = directory.admission(access)
            currentCoroutineContext().ensureActive()
            if (gate is AppResult.Failed) return gate
            val value = (gate as AppResult.Success).value
            if (!validCollaborationSpaceDirectoryAdmission(value))
                return failure("invalid_admission")
            value.denial?.let {
                restriction = retainCollaborationSpaceConversationFailure(restriction, it)
            }
            restriction?.let {
                return AppResult.Failed(it)
            }
            val parent = sessions.guardSpaceRecoveryOwner(server, owner)
            if (parent is AppResult.Failed) return parent
            if (!live) return AppResult.Success(Unit)
            val admitted =
                sessions.captureCollaborationSpaceParent(owner.parent, server, build, clock)
            if (admitted is AppResult.Failed) return admitted
            val deadline =
                Instant.fromEpochMilliseconds(
                    (admitted as AppResult.Success).value.binding.expiresAtEpochMillis
                )
            nativeDeadline = nativeDeadline?.let { minOf(it, deadline) } ?: deadline
            val now = clock.now()
            if (
                now < requestedAt ||
                    now >= requireNotNull(nativeDeadline) ||
                    now - requestedAt >= 15.seconds ||
                    started.elapsedNow() >=
                        minOf(15.seconds, requireNotNull(nativeDeadline) - requestedAt)
            )
                return failure("revalidation_required", FailureKind.Unavailable)
            return AppResult.Success(Unit)
        }

        suspend fun current(): AppResult<Unit> {
            val before = admission()
            if (before is AppResult.Failed || !live) return before
            val open = runtimes.isSpaceOpen(owner, runtime.deviceId, runtime.operationId)
            val after = admission()
            if (after is AppResult.Failed) return after
            if (open is AppResult.Failed) return open
            return if ((open as AppResult.Success).value) AppResult.Success(Unit)
            else failure("runtime_changed")
        }

        suspend fun <T> checked(operation: suspend () -> AppResult<T>): AppResult<T> {
            val before = current()
            if (before is AppResult.Failed) return before
            val result = operation()
            val admitted = current()
            return if (admitted is AppResult.Failed) admitted else result
        }

        suspend fun restrictRemote(result: AppResult<*>) {
            if (
                result is AppResult.Failed &&
                    collaborationSpaceConversationResponseClosesDirectory(result.failure)
            ) {
                restriction =
                    retainCollaborationSpaceConversationFailure(restriction, result.failure)
                withContext(NonCancellable) {
                    withTimeoutOrNull(2_000) { directory.restrict(access, result.failure) }
                }
            }
        }

        suspend fun capture(): AppResult<CollaborationSpaceOriginalQueueSnapshot> = checked {
            val queue = requireNotNull(writer)
            when (val read = queue.snapshot()) {
                is AppResult.Failed -> read
                is AppResult.Success -> {
                    val value = read.value
                    if (
                        queue.runtime != runtime ||
                            value.runtime != runtime ||
                            value.original.binding != queue.binding ||
                            value.original.device != queue.device ||
                            value.original.messages.size > 128
                    )
                        failure("writer_changed")
                    else
                        AppResult.Success(
                            value.copy(
                                original =
                                    value.original.copy(messages = value.original.messages.toList())
                            )
                        )
                }
            }
        }

        suspend fun inspectCommands(target: CollaborationSpaceMessageRecord): AppResult<Unit> {
            val listed = checked {
                when (val read = messages.observe(access, conversation.id).first()) {
                    is AppResult.Failed -> read
                    is AppResult.Success ->
                        if (read.value.size > 128) failure("command_bounds")
                        else
                            AppResult.Success(
                                read.value.map { record ->
                                    val state = record.state
                                    if (state is CollaborationMessageState.Staging)
                                        record.copy(
                                            state = state.copy(before = state.before.toSet())
                                        )
                                    else record
                                }
                            )
                }
            }
            if (listed is AppResult.Failed) return listed
            val active = (listed as AppResult.Success).value
            if (
                active.map { it.intent.id }.distinct().size != active.size ||
                    active.any {
                        !validCollaborationSpaceMessageRecord(access, it) ||
                            it.intent.conversationId != conversation.id
                    } ||
                    active.singleOrNull { it.intent.id == messageId } != target ||
                    active.any {
                        it.intent.id != messageId &&
                            (it.state as? CollaborationMessageState.Staging)?.device ==
                                snapshot.original.device
                    }
            )
                return failure("command_inventory_changed")
            val journal = checked {
                when (val read = receipts.read(access, conversation.id)) {
                    is AppResult.Failed -> read
                    is AppResult.Success ->
                        if (
                            !validCollaborationSpaceReceiptLedger(
                                access,
                                conversation.id,
                                read.value,
                            )
                        )
                            failure("receipt_bounds")
                        else
                            AppResult.Success(
                                read.value.map { it.copy(eventIds = it.eventIds.toList()) }
                            )
                }
            }
            if (journal is AppResult.Failed) return journal
            known = (journal as AppResult.Success).value
            val company = inspectOriginalCompanyCommands()
            if (company is AppResult.Failed) return company
            val aliases = known.flatMap { it.eventIds } + oldKnown.flatMap { it.eventIds }
            val receivedTransactions =
                known.map { Triple(it.device, it.device.roomId, it.transactionId) } +
                    oldKnown.map { Triple(it.device, it.device.roomId, it.transactionId) }
            if (
                known.size + oldKnown.size > 128 ||
                    aliases.size > 512 ||
                    aliases.distinct().size != aliases.size ||
                    receivedTransactions.distinct().size != receivedTransactions.size ||
                    known.any { it.intent.id == messageId } ||
                    oldKnown.any { it.intent.id == messageId }
            )
                return failure("receipt_changed")
            val recovery =
                when (target.state) {
                    CollaborationMessageState.Queued ->
                        if (snapshot.original.messages.any { it.logicalId == messageId })
                            CollaborationQueueRecovery.Ambiguous
                        else CollaborationQueueRecovery.Missing
                    is CollaborationMessageState.Staging ->
                        recoverCollaborationSpaceStaging(access, target, snapshot.original)
                    else -> return failure("message_changed")
                }
            if (
                recovery !is CollaborationQueueRecovery.Missing &&
                    recovery !is CollaborationQueueRecovery.Found
            )
                return failure("queue_unresolved")
            val matched = matchOtherOriginals()
            if (matched is AppResult.Failed) return matched
            val retained = checked { bindings.getByConversation(access, conversation.id) }
            if (retained is AppResult.Failed) return retained
            return if ((retained as AppResult.Success).value == binding) AppResult.Success(Unit)
            else failure("binding_changed")
        }

        /** LEGACY rooms alone have a company command namespace and its own receipt journal. */
        suspend fun inspectOriginalCompanyCommands(): AppResult<Unit> {
            val oldAccess = companyAccess ?: return AppResult.Success(Unit)
            val collision = checked { companyMessages.get(oldAccess, messageId) }
            if (collision is AppResult.Failed) return collision
            if ((collision as AppResult.Success).value != null) return failure("command_ambiguous")
            val oldId = requireNotNull(binding.original).conversation.position.conversationId
            val old = checked {
                when (val read = companyMessages.observe(oldAccess, oldId).first()) {
                    is AppResult.Failed -> read
                    is AppResult.Success ->
                        if (read.value.size > 128) failure("command_bounds")
                        else
                            AppResult.Success(
                                read.value.map { record ->
                                    val state = record.state
                                    if (state is CollaborationMessageState.Staging)
                                        record.copy(
                                            state = state.copy(before = state.before.toSet())
                                        )
                                    else record
                                }
                            )
                }
            }
            if (old is AppResult.Failed) return old
            val oldJournal = checked {
                when (val read = companyReceipts.read(oldAccess, oldId)) {
                    is AppResult.Failed -> read
                    is AppResult.Success ->
                        if (!validCollaborationReceiptLedger(read.value)) failure("receipt_bounds")
                        else
                            AppResult.Success(
                                read.value.map { it.copy(eventIds = it.eventIds.toList()) }
                            )
                }
            }
            if (oldJournal is AppResult.Failed) return oldJournal
            oldKnown = (oldJournal as AppResult.Success).value
            val oldActive = (old as AppResult.Success).value
            if (
                missingCollaborationReceiptIds(oldAccess, oldId, oldActive, oldKnown) == null ||
                    oldActive.any {
                        !validCollaborationMessageState(it.state) ||
                            (it.state as? CollaborationMessageState.Staging)?.device ==
                                snapshot.original.device
                    }
            )
                return failure("command_inventory_changed")
            return AppResult.Success(Unit)
        }

        suspend fun matchOtherOriginals(): AppResult<Unit> {
            val company = mutableListOf<CollaborationMessageRecord>()
            val space = mutableListOf<CollaborationSpaceMessageRecord>()
            val companyProofs = mutableListOf<CollaborationMessageCompletion>()
            val spaceProofs = mutableListOf<CollaborationSpaceMessageCompletion>()
            val others = snapshot.original.messages.filter { it.logicalId != messageId }
            for (original in others) {
                val id = requireNotNull(original.logicalId)
                val old =
                    companyAccess?.let { oldAccess ->
                        checked { companyMessages.get(oldAccess, id) }
                    } ?: AppResult.Success(null)
                if (old is AppResult.Failed) return old
                val next = checked { messages.get(access, id) }
                if (next is AppResult.Failed) return next
                val oldRecord = (old as AppResult.Success).value
                val record = (next as AppResult.Success).value
                if ((oldRecord == null) == (record == null))
                    return failure("command_missing_or_ambiguous")
                if (oldRecord != null) {
                    if (oldRecord.intent.id != id) return failure("message_changed")
                    company += oldRecord
                    if (oldRecord.state is CollaborationMessageState.Sent) {
                        val proof = checked {
                            when (
                                val read =
                                    companyCompletions.readCompletion(
                                        requireNotNull(companyAccess),
                                        id,
                                    )
                            ) {
                                is AppResult.Failed -> read
                                is AppResult.Success ->
                                    if (read.value?.eventIds?.size?.let { it > 128 } == true)
                                        failure("receipt_bounds")
                                    else
                                        AppResult.Success(
                                            read.value?.let {
                                                it.copy(eventIds = it.eventIds.toList())
                                            }
                                        )
                            }
                        }
                        if (proof is AppResult.Failed) return proof
                        companyProofs +=
                            collaborationOriginalAcknowledgement(
                                oldRecord,
                                oldKnown.find { it.intent.id == id },
                                (proof as AppResult.Success).value,
                            ) ?: return failure("acknowledgement_missing")
                    }
                }
                if (record != null) {
                    if (record.intent.id != id) return failure("message_changed")
                    space += record
                    if (record.state is CollaborationMessageState.Sent) {
                        val proof = checked {
                            when (val read = completions.readCompletion(access, id)) {
                                is AppResult.Failed -> read
                                is AppResult.Success ->
                                    if (read.value?.eventIds?.size?.let { it > 128 } == true)
                                        failure("receipt_bounds")
                                    else
                                        AppResult.Success(
                                            read.value?.let {
                                                it.copy(eventIds = it.eventIds.toList())
                                            }
                                        )
                            }
                        }
                        if (proof is AppResult.Failed) return proof
                        spaceProofs +=
                            collaborationSpaceOriginalAcknowledgement(
                                access,
                                record,
                                known.find { it.intent.id == id },
                                (proof as AppResult.Success).value,
                            ) ?: return failure("acknowledgement_missing")
                    }
                }
            }
            val matched =
                matchCollaborationMixedOriginalQueue(
                    runtime,
                    conversation,
                    binding,
                    snapshot.copy(original = snapshot.original.copy(messages = others)),
                    company,
                    companyProofs,
                    space,
                    spaceProofs,
                )
            return when (matched) {
                is AppResult.Failed -> matched
                is AppResult.Success -> AppResult.Success(Unit)
            }
        }

        suspend fun stage(
            original: CollaborationSpaceMessageRecord
        ): AppResult<CollaborationSpaceMessageRecord> {
            val queue = requireNotNull(writer)
            val prepared = checked {
                queue.prepareText(
                    messageId,
                    original.intent.text,
                    original.intent.replyToEventId,
                    original.intent.forwarded,
                    original.intent.editTarget,
                )
            }
            if (prepared is AppResult.Failed) return prepared
            val origin = (prepared as AppResult.Success).value
            if (!validCollaborationMessageOrigin(origin) || origin.sender != observed.matrixUserId)
                return failure("invalid_origin")
            val pending =
                when (val state = original.state) {
                    CollaborationMessageState.Queued -> {
                        if (snapshot.original.messages.size == 128)
                            return failure("queue_full", FailureKind.Storage)
                        val retained =
                            commit(
                                original,
                                CollaborationMessageState.Staging(
                                    queue.device,
                                    snapshot.original.messages.map { it.transactionId }.toSet(),
                                    origin,
                                ),
                            )
                        if (retained is AppResult.Failed) return retained
                        (retained as AppResult.Success).value
                    }
                    is CollaborationMessageState.Staging -> {
                        if (state.origin != origin || state.device != queue.device)
                            return failure("origin_changed")
                        original
                    }
                    else -> return failure("message_changed")
                }
            val before = recoverCollaborationSpaceStaging(access, pending, snapshot.original)
            val transaction =
                when (before) {
                    is CollaborationQueueRecovery.Found -> before.transactionId
                    CollaborationQueueRecovery.Missing -> {
                        if (!observed.recipientReady)
                            return collaborationSetupFailure(
                                "client.collaboration_recipient_unavailable",
                                FailureKind.Conflict,
                            )
                        val sent =
                            queue.enqueue(
                                snapshot,
                                messageId,
                                pending.intent.text,
                                origin,
                                pending.intent.replyToEventId,
                                pending.intent.forwarded,
                                pending.intent.editTarget,
                            )
                        val admitted = current()
                        if (admitted is AppResult.Failed) return admitted
                        // Failed response is not absence: inspect the same paused original after
                        // drain.
                        val captured = capture()
                        if (captured is AppResult.Failed)
                            return if (sent is AppResult.Failed) sent else captured
                        val after = (captured as AppResult.Success).value
                        val recovery =
                            recoverCollaborationSpaceStaging(access, pending, after.original)
                        if (recovery !is CollaborationQueueRecovery.Found)
                            return if (sent is AppResult.Failed) sent
                            else failure("queue_unresolved")
                        recovery.transactionId
                    }
                    else -> return failure("queue_unresolved")
                }
            return commit(
                pending,
                CollaborationMessageState.Staged(queue.device, transaction, origin),
            )
        }

        suspend fun commit(
            expected: CollaborationSpaceMessageRecord,
            next: CollaborationMessageState,
        ): AppResult<CollaborationSpaceMessageRecord> {
            if (
                expected.revision == 9_007_199_254_740_991L ||
                    !collaborationMessageTransition(expected.state, next)
            )
                return failure("message_changed")
            val replacement = expected.copy(revision = expected.revision + 1, state = next)
            if (!validCollaborationSpaceMessageRecord(access, replacement))
                return failure("message_changed")
            val changed = checked { messages.update(access, expected, next) }
            val read = checked { messages.get(access, messageId) }
            if (read is AppResult.Failed) return read
            val retained = (read as AppResult.Success).value
            if (retained == replacement) return AppResult.Success(replacement)
            return if (changed is AppResult.Failed) changed else failure("message_changed")
        }

        fun failure(code: String, kind: FailureKind = FailureKind.Conflict) =
            restriction?.let { AppResult.Failed(it) } ?: stageFailure(code, kind)
    }
}

private fun stageFailure(code: String, kind: FailureKind = FailureKind.Conflict) =
    collaborationSetupFailure("client.collaboration_space_stage_$code", kind)
