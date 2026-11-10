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
    private val spaceMessages: CollaborationSpaceMessageRepository,
    private val spaceReceipts: CollaborationSpaceReceiptRepository,
    private val spaceCompletions: CollaborationSpaceMessageCompletionRepository,
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
        var companyAccess: CollaborationLocalAccess? = null
        var preparing = true
        val projection = Mutex()
        val receiptRetention = Mutex()
        var observedReceipts = CollaborationMixedDeliveryReceipts(emptyList(), emptyList())
        var company: CompanyLedger? = null
        lateinit var space: SpaceLedger
        lateinit var matched: CollaborationMixedOriginalQueueMatch

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
                        matched.messages.any {
                            when (it) {
                                is CollaborationMatchedQueueMessage.Company -> it.completion == null
                                is CollaborationMatchedQueueMessage.Space -> it.completion == null
                            }
                        }
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
                    retained.conversation != conversation.localMetadata()
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
                    observed.device.platform != platform
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
                matchCollaborationSpaceQueueTransport(
                    runtime,
                    conversation,
                    retained,
                    observed,
                    snapshot,
                    platform,
                )
            if (mapping is AppResult.Failed) return mapping
            val scope =
                collaborationMixedQueueCompanyAccess(runtime, conversation, retained, snapshot)
            if (scope is AppResult.Failed) return scope
            companyAccess = (scope as AppResult.Success).value
            if (
                queue.runtime != runtime ||
                    queue.binding != snapshot.original.binding ||
                    queue.device != snapshot.original.device
            )
                return failure("writer_changed")
            val repaired = reconcile()
            if (repaired is AppResult.Failed) return repaired
            val recovered = recoverMissingReceipts()
            if (recovered is AppResult.Failed) return recovered
            val settled = settle()
            if (settled is AppResult.Failed) return settled
            return AppResult.Success(
                report(
                    if (matched.messages.isEmpty()) CollaborationQueueDeliveryReason.DRAINED
                    else CollaborationQueueDeliveryReason.STOPPED
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
            val before = current()
            if (before is AppResult.Failed) return before
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

        /** One bounded original history acquisition, only for disappeared retained transactions. */
        suspend fun recoverMissingReceipts(): AppResult<Unit> {
            val old = company
            val missingCompany =
                collaborationSpaceMissingOriginalReceipts(
                    snapshot,
                    old?.all.orEmpty().values.toList(),
                )
            val queued = snapshot.original.messages.mapNotNull { it.logicalId }.toSet()
            val missingSpace =
                space.all.values.filter {
                    val state = it.state as? CollaborationMessageState.Staged
                    it.intent.id !in queued &&
                        it.intent.editTarget == null &&
                        state != null &&
                        state.device == snapshot.original.device &&
                        state.origin != null
                }
            if (missingCompany.isEmpty() && missingSpace.isEmpty()) return AppResult.Success(Unit)
            if (
                (missingCompany.map { it.state.origin } + missingSpace.map { it.state.origin })
                    .any { it?.sender != observed.matrixUserId }
            )
                return failure("history_command_changed")
            val recoveredCompany = mutableListOf<CollaborationMessageReceipt>()
            val recoveredSpace = mutableListOf<CollaborationSpaceMessageReceipt>()
            val absentCompany = mutableListOf<CollaborationMessageRecord>()
            for (record in missingCompany) {
                val state = record.state as CollaborationMessageState.Staged
                val local = checked {
                    history.getByLogicalId(
                        requireNotNull(old).access,
                        old.conversationId,
                        state.device.roomId,
                        requireNotNull(state.origin).sender,
                        record.intent.id,
                    )
                }
                if (local is AppResult.Failed) return local
                val found = (local as AppResult.Success).value
                if (found == null) absentCompany += record
                else {
                    if (found.eventIds.size > 128) return failure("receipt_bounds")
                    recoveredCompany +=
                        recoverCollaborationOriginalReceipt(
                            record,
                            found.copy(eventIds = found.eventIds.toList()),
                        ) ?: return failure("history_receipt_changed")
                }
            }
            if (absentCompany.isNotEmpty() || missingSpace.isNotEmpty()) {
                val acquired = captureRecoveryHistory()
                if (acquired is AppResult.Failed) return acquired
                val observedHistory = (acquired as AppResult.Success).value
                if (!validCollaborationSpaceOriginalHistory(observedHistory, snapshot))
                    return failure("history_changed")
                val changes = observedHistory.window?.changes.orEmpty()
                for (record in absentCompany) collaborationSpaceOriginalHistoryReceipt(
                        record,
                        changes,
                    )
                    ?.let { recoveredCompany += it }
                for (record in missingSpace) collaborationSpaceMessageHistoryReceipt(
                        space.access,
                        record,
                        changes,
                    )
                    ?.let { recoveredSpace += it }
            }
            if (recoveredCompany.isEmpty() && recoveredSpace.isEmpty())
                return AppResult.Success(Unit)
            val retained =
                retainBatch(CollaborationMixedDeliveryReceipts(recoveredCompany, recoveredSpace))
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

        /** Both complete ledgers are validated and planned before either can change. */
        suspend fun reconcileOriginals(): AppResult<Unit> {
            val old =
                companyAccess?.let {
                    CompanyLedger(
                        it,
                        requireNotNull(retained.original).conversation.position.conversationId,
                    )
                }
            val next = SpaceLedger()
            val oldRead = old?.readPending()
            if (oldRead is AppResult.Failed) return oldRead
            val nextRead = next.readPending()
            if (nextRead is AppResult.Failed) return nextRead
            val ids =
                (snapshot.original.messages.map { requireNotNull(it.logicalId) } +
                        old?.all.orEmpty().keys +
                        next.all.keys +
                        old?.known.orEmpty().map { it.intent.id } +
                        next.known.map { it.intent.id })
                    .toSet()
            if (ids.size > 128) return failure("command_bounds")
            val oldLoaded = old?.load(ids)
            if (oldLoaded is AppResult.Failed) return oldLoaded
            val nextLoaded = next.load(ids)
            if (nextLoaded is AppResult.Failed) return nextLoaded
            if (old?.all.orEmpty().keys.intersect(next.all.keys).isNotEmpty())
                return failure("command_owner")
            val states =
                old?.all.orEmpty().values.map { it.state } + next.all.values.map { it.state }
            if (states.any { blocksCollaborationPhysicalQueue(it, snapshot.original.device) })
                return failure("staging_unresolved")
            val oldPlan = old?.plan()
            if (oldPlan is AppResult.Failed) return oldPlan
            val nextPlan = next.plan()
            if (nextPlan is AppResult.Failed) return nextPlan
            if (!validCollaborationMixedReceiptOwnership(old?.effective.orEmpty(), next.effective))
                return failure("receipt_owner")
            val retainedProof =
                receiptRetention.withLock {
                    mergeCollaborationMixedDeliveryReceipts(
                            observedReceipts,
                            CollaborationMixedDeliveryReceipts(
                                old?.effective.orEmpty(),
                                next.effective,
                            ),
                        )
                        ?.also { observedReceipts = it }
                }
            if (retainedProof == null) return failure("receipt_owner")
            val before = matchCaptured(old, next)
            if (before is AppResult.Failed) return before
            val oldSaved = old?.applyProjection()
            if (oldSaved is AppResult.Failed) return oldSaved
            val nextSaved = next.applyProjection()
            if (nextSaved is AppResult.Failed) return nextSaved
            val after = matchCaptured(old, next)
            if (after is AppResult.Failed) return after
            company = old
            space = next
            matched = (after as AppResult.Success).value
            return AppResult.Success(Unit)
        }

        fun matchCaptured(
            old: CompanyLedger?,
            next: SpaceLedger,
        ): AppResult<CollaborationMixedOriginalQueueMatch> {
            val ids = snapshot.original.messages.map { it.logicalId }.toSet()
            return matchCollaborationMixedOriginalQueue(
                runtime,
                conversation,
                retained,
                snapshot,
                old?.all.orEmpty().values.filter { it.intent.id in ids },
                old?.evidence.orEmpty().values.filter { it.record.intent.id in ids },
                next.all.values.filter { it.intent.id in ids },
                next.evidence.values.filter { it.record.intent.id in ids },
            )
        }

        /** Captured original-company storage scope, never allocated for a SPACE-only room. */
        inner class CompanyLedger(
            val access: CollaborationLocalAccess,
            val conversationId: String,
        ) {
            val all = linkedMapOf<String, CollaborationMessageRecord>()
            var known = emptyList<CollaborationMessageReceipt>()
            val evidence = linkedMapOf<String, CollaborationMessageCompletion>()
            val changes =
                mutableListOf<Pair<CollaborationMessageRecord, CollaborationMessageState.Sent>>()
            var effective = emptyList<CollaborationMessageReceipt>()

            suspend fun readPending(): AppResult<Unit> {
                val pending = checked {
                    when (val read = messages.observe(access, conversationId).first()) {
                        is AppResult.Failed -> read
                        is AppResult.Success ->
                            if (read.value.size > 128) failure("command_bounds")
                            else
                                AppResult.Success(
                                    read.value.map { record ->
                                        record.copy(
                                            state =
                                                (record.state as? CollaborationMessageState.Staging)
                                                    ?.let { it.copy(before = it.before.toSet()) }
                                                    ?: record.state
                                        )
                                    }
                                )
                    }
                }
                if (pending is AppResult.Failed) return pending
                val journal = checked {
                    when (val read = receipts.read(access, conversationId)) {
                        is AppResult.Failed -> read
                        is AppResult.Success ->
                            if (read.value.size > 128 || read.value.any { it.eventIds.size > 128 })
                                failure("receipt_bounds")
                            else
                                AppResult.Success(
                                    read.value.map { it.copy(eventIds = it.eventIds.toList()) }
                                )
                    }
                }
                if (journal is AppResult.Failed) return journal
                val records = (pending as AppResult.Success).value
                val values = (journal as AppResult.Success).value
                if (missingCollaborationReceiptIds(access, conversationId, records, values) == null)
                    return failure("receipt_owner")
                if (records.any { !validRecord(it) }) return failure("command_changed")
                for (record in records) all[record.intent.id] =
                    record.copy(
                        state =
                            (record.state as? CollaborationMessageState.Staging)?.let {
                                it.copy(before = it.before.toSet())
                            } ?: record.state
                    )
                known = values.map { it.copy(eventIds = it.eventIds.toList()) }
                return AppResult.Success(Unit)
            }

            suspend fun load(ids: Set<String>): AppResult<Unit> {
                for (id in ids.filterNot { it in all }) {
                    val read = checked { messages.get(access, id) }
                    if (read is AppResult.Failed) return read
                    val record = (read as AppResult.Success).value ?: continue
                    if (record.intent.id != id || !validRecord(record))
                        return failure("command_changed")
                    all[id] =
                        record.copy(
                            state =
                                (record.state as? CollaborationMessageState.Staging)?.let {
                                    it.copy(before = it.before.toSet())
                                } ?: record.state
                        )
                }
                return AppResult.Success(Unit)
            }

            fun validRecord(record: CollaborationMessageRecord) =
                record.intent.owner == access.messageOwner() &&
                    record.intent.conversationId == conversationId &&
                    record.revision in 0..9_007_199_254_740_991L &&
                    validCollaborationEditRecord(record) &&
                    validCollaborationMessageState(record.state)

            suspend fun plan(): AppResult<Unit> {
                for (record in all.values.filter { it.state is CollaborationMessageState.Sent }) {
                    val read = checked { completions.readCompletion(access, record.intent.id) }
                    if (read is AppResult.Failed) return read
                    val archived = (read as AppResult.Success).value
                    if (archived?.eventIds?.size?.let { it > 128 } == true)
                        return failure("receipt_bounds")
                    evidence[record.intent.id] =
                        collaborationOriginalAcknowledgement(
                            record,
                            known.find { it.intent.id == record.intent.id },
                            archived?.copy(eventIds = archived.eventIds.toList()),
                        ) ?: return failure("acknowledgement_missing")
                }
                for (receipt in known) {
                    val record = all[receipt.intent.id] ?: return failure("command_missing")
                    val state =
                        evidence[receipt.intent.id]?.let {
                            collaborationCompletedReceiptState(it, receipt)
                        }
                            ?: collaborationReceiptState(record, receipt)
                            ?: return failure("receipt_changed")
                    if (state != record.state && record.revision == 9_007_199_254_740_991L)
                        return failure("command_bounds")
                    changes += record to state
                }
                effective =
                    known.filterNot { it.intent.id in evidence } +
                        evidence.values.map {
                            collaborationCompletionReceipt(it) ?: return failure("receipt_changed")
                        }
                if (!validCollaborationReceiptLedger(effective)) return failure("receipt_owner")
                return AppResult.Success(Unit)
            }

            suspend fun applyProjection(): AppResult<Unit> {
                for ((record, state) in changes) {
                    if (record.state == state) continue
                    val saved = checked { messages.update(access, record, state) }
                    val read = checked { messages.get(access, record.intent.id) }
                    if (read is AppResult.Failed) return read
                    val confirmed = (read as AppResult.Success).value
                    if (confirmed != record.copy(revision = record.revision + 1, state = state))
                        return if (saved is AppResult.Failed) saved
                        else failure("projection_changed")
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
        }

        /** Captured Space scope; its journal, active commands and archive never use company I/O. */
        inner class SpaceLedger {
            val access = owner.localDirectoryAccess()
            val all = linkedMapOf<String, CollaborationSpaceMessageRecord>()
            var known = emptyList<CollaborationSpaceMessageReceipt>()
            val evidence = linkedMapOf<String, CollaborationSpaceMessageCompletion>()
            var changes =
                emptyList<Pair<CollaborationSpaceMessageRecord, CollaborationMessageState.Sent>>()
            var effective = emptyList<CollaborationSpaceMessageReceipt>()

            suspend fun readPending(): AppResult<Unit> {
                val pending = checked {
                    when (val read = spaceMessages.observe(access, conversation.id).first()) {
                        is AppResult.Failed -> read
                        is AppResult.Success ->
                            if (read.value.size > 128) failure("command_bounds")
                            else
                                AppResult.Success(
                                    read.value.map { record ->
                                        record.copy(
                                            state =
                                                (record.state as? CollaborationMessageState.Staging)
                                                    ?.let { it.copy(before = it.before.toSet()) }
                                                    ?: record.state
                                        )
                                    }
                                )
                    }
                }
                if (pending is AppResult.Failed) return pending
                val journal = checked {
                    when (val read = spaceReceipts.read(access, conversation.id)) {
                        is AppResult.Failed -> read
                        is AppResult.Success ->
                            if (read.value.size > 128 || read.value.any { it.eventIds.size > 128 })
                                failure("receipt_bounds")
                            else
                                AppResult.Success(
                                    read.value.map { it.copy(eventIds = it.eventIds.toList()) }
                                )
                    }
                }
                if (journal is AppResult.Failed) return journal
                val records = (pending as AppResult.Success).value
                val values = (journal as AppResult.Success).value
                if (
                    records.size > 128 ||
                        records.map { it.intent.id }.distinct().size != records.size ||
                        records.any { !validRecord(it) } ||
                        !validCollaborationSpaceReceiptLedger(access, conversation.id, values)
                )
                    return failure("receipt_owner")
                for (record in records) all[record.intent.id] =
                    record.copy(
                        state =
                            (record.state as? CollaborationMessageState.Staging)?.let {
                                it.copy(before = it.before.toSet())
                            } ?: record.state
                    )
                known = values.map { it.copy(eventIds = it.eventIds.toList()) }
                return AppResult.Success(Unit)
            }

            suspend fun load(ids: Set<String>): AppResult<Unit> {
                for (id in ids.filterNot { it in all }) {
                    val read = checked { spaceMessages.get(access, id) }
                    if (read is AppResult.Failed) return read
                    val record = (read as AppResult.Success).value ?: continue
                    if (record.intent.id != id || !validRecord(record))
                        return failure("command_changed")
                    all[id] =
                        record.copy(
                            state =
                                (record.state as? CollaborationMessageState.Staging)?.let {
                                    it.copy(before = it.before.toSet())
                                } ?: record.state
                        )
                }
                return AppResult.Success(Unit)
            }

            fun validRecord(record: CollaborationSpaceMessageRecord) =
                validCollaborationSpaceMessageRecord(access, record) &&
                    record.intent.conversationId == conversation.id

            suspend fun plan(): AppResult<Unit> {
                for (record in all.values.filter { it.state is CollaborationMessageState.Sent }) {
                    val read = checked { spaceCompletions.readCompletion(access, record.intent.id) }
                    if (read is AppResult.Failed) return read
                    val archived = (read as AppResult.Success).value
                    if (archived?.eventIds?.size?.let { it > 128 } == true)
                        return failure("receipt_bounds")
                    evidence[record.intent.id] =
                        collaborationSpaceOriginalAcknowledgement(
                            access,
                            record,
                            known.find { it.intent.id == record.intent.id },
                            archived?.copy(eventIds = archived.eventIds.toList()),
                        ) ?: return failure("acknowledgement_missing")
                }
                val records = known.map { all[it.intent.id] ?: return failure("command_missing") }
                val ids = records.map { it.intent.id }.toSet()
                changes =
                    collaborationSpaceReceiptProjection(
                        access,
                        conversation.id,
                        records,
                        known,
                        evidence.values.filter { it.record.intent.id in ids },
                    ) ?: return failure("receipt_changed")
                effective =
                    known.filterNot { it.intent.id in evidence } +
                        evidence.values.map {
                            collaborationSpaceCompletionReceipt(access, it)
                                ?: return failure("receipt_changed")
                        }
                if (!validCollaborationSpaceReceiptLedger(access, conversation.id, effective))
                    return failure("receipt_owner")
                return AppResult.Success(Unit)
            }

            suspend fun applyProjection(): AppResult<Unit> {
                for ((record, state) in changes) {
                    if (record.state == state) continue
                    val saved = checked { spaceMessages.update(access, record, state) }
                    val read = checked { spaceMessages.get(access, record.intent.id) }
                    if (read is AppResult.Failed) return read
                    val confirmed = (read as AppResult.Success).value
                    if (confirmed != record.copy(revision = record.revision + 1, state = state))
                        return if (saved is AppResult.Failed) saved
                        else failure("projection_changed")
                    all[record.intent.id] = confirmed
                    evidence[record.intent.id] =
                        collaborationSpaceOriginalAcknowledgement(
                            access,
                            confirmed,
                            known.first { it.intent.id == record.intent.id },
                            null,
                        ) ?: return failure("receipt_changed")
                }
                return AppResult.Success(Unit)
            }
        }

        suspend fun settle(): AppResult<Unit> {
            val queue = requireNotNull(writer)
            val remaining = mutableListOf<CollaborationMatchedQueueMessage>()
            for (value in matched.messages) {
                val event =
                    when (value) {
                        is CollaborationMatchedQueueMessage.Company -> {
                            val proof = value.completion
                            if (proof == null) {
                                remaining += value
                                continue
                            }
                            val old = requireNotNull(company)
                            collaborationQueueCompletionEvent(
                                old.access,
                                old.conversationId,
                                queue.device,
                                value.original,
                                proof,
                            )
                        }
                        is CollaborationMatchedQueueMessage.Space -> {
                            val proof = value.completion
                            if (proof == null) {
                                remaining += value
                                continue
                            }
                            collaborationSpaceQueueCompletionEvent(
                                space.access,
                                conversation.id,
                                queue.device,
                                value.original,
                                proof,
                            )
                        }
                    } ?: return failure("receipt_changed")
                val settled = checked { queue.settleConfirmed(value.original, event) }
                if (settled is AppResult.Failed) return settled
            }
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
            matched = matched.copy(messages = remaining.toList())
            return AppResult.Success(Unit)
        }

        suspend fun deliver(): AppResult<CollaborationDeliveryReport> {
            val admitted = current()
            if (admitted is AppResult.Failed) return admitted
            val binding = checked {
                bindings.getByConversation(owner.localDirectoryAccess(), conversation.id)
            }
            if (binding is AppResult.Failed) return binding
            if ((binding as AppResult.Success).value != retained) return failure("binding_changed")
            val invocation = currentCoroutineContext()
            val captured = matched
            val result =
                requireNotNull(writer).resume(snapshot) { progress ->
                    val saved = retainObserved(progress, captured)
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
            val saved = retainObserved(outcome, captured)
            val latest = current()
            if (latest is AppResult.Failed) return latest
            if (saved is AppResult.Failed) return saved
            val repaired = reconcile()
            if (repaired is AppResult.Failed) return repaired
            return AppResult.Success(report(outcome.reason))
        }

        suspend fun retainObserved(
            progress: CollaborationQueueDelivery,
            captured: CollaborationMixedOriginalQueueMatch,
        ): AppResult<Unit> {
            val batch =
                collaborationMixedDeliveryReceipts(retained, captured, progress)
                    ?: return failure("receipt_changed")
            return retainBatch(batch)
        }

        /**
         * Factual callback storage retains its original scope even after the caller is cancelled.
         */
        suspend fun retainBatch(batch: CollaborationMixedDeliveryReceipts): AppResult<Unit> =
            withContext(NonCancellable) {
                withTimeoutOrNull(5_000) {
                    receiptRetention.withLock {
                        val merged =
                            mergeCollaborationMixedDeliveryReceipts(observedReceipts, batch)
                                ?: return@withLock failure("receipt_owner")
                        // Keep factual ownership even if only the first journal commits before
                        // response loss.
                        observedReceipts = merged
                        val old = company
                        if (batch.company.isNotEmpty() && old == null)
                            return@withLock failure("receipt_owner")
                        for (portion in batch.company.chunked(32)) {
                            val saved =
                                receipts.append(
                                    requireNotNull(old).access,
                                    old.conversationId,
                                    portion,
                                )
                            if (saved is AppResult.Failed) return@withLock saved
                            if (!(saved as AppResult.Success).value)
                                return@withLock failure("receipt_write_changed")
                        }
                        for (portion in batch.space.chunked(32)) {
                            val saved =
                                spaceReceipts.append(
                                    owner.localDirectoryAccess(),
                                    conversation.id,
                                    portion,
                                )
                            if (saved is AppResult.Failed) return@withLock saved
                            if (!(saved as AppResult.Success).value)
                                return@withLock failure("receipt_write_changed")
                        }
                        AppResult.Success(Unit)
                    }
                } ?: failure("receipt_pending", FailureKind.Storage)
            }

        fun report(reason: CollaborationQueueDeliveryReason?) =
            collaborationMixedDeliveryReport(
                company?.all.orEmpty().values.toList(),
                space.all.values.toList(),
                reason,
            )

        fun failure(code: String, kind: FailureKind = FailureKind.Conflict) =
            restriction?.let { AppResult.Failed(it) } ?: originalDeliveryFailure(code, kind)
    }
}

private fun originalDeliveryFailure(code: String, kind: FailureKind = FailureKind.Conflict) =
    collaborationSetupFailure("client.collaboration_original_$code", kind)
