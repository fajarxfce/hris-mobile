package dev.fajar.starter.app.notifications

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.notifications.data.dto.ActionNotificationInput
import dev.fajar.starter.notifications.data.dto.ActionNotificationReference
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

/**
 * Process-owned reconciliation of bounded opaque journal references. This owner must be the sole
 * normal native writer. Focused-thread registration and native callbacks are separate integration
 * steps; calling requestScan does not grant permission to publish content.
 */
class CollaborationNotificationProcess(
    private val owner: CollaborationNotificationSurfaceOwner,
    private val read:
        suspend (CollaborationNativeParent, String?) -> AppResult<
                CollaborationNotificationPublicationReferences
            >,
    private val observe: (CollaborationNativeParent, String, String) -> Flow<AppResult<Unit>>,
    private val reconcile:
        suspend (
            CollaborationNativeParent, CollaborationNotificationPublicationReference, Failure?,
        ) -> AppResult<CollaborationNotificationReconciliationResult>,
    private val publishCandidate:
        suspend (
            CollaborationNativeParent, CollaborationNotificationCandidate, Boolean,
        ) -> AppResult<CollaborationNotificationPublicationResult>,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val actions: CollaborationNotificationActions? = null,
) : AutoCloseable {
    private val lifetime = SupervisorJob()
    private val scope = CoroutineScope(lifetime + dispatcher)
    private val started = MutableStateFlow(false)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val observed =
        MutableStateFlow<CollaborationNotificationSurfaceState>(
            CollaborationNotificationSurfaceState.Pending
        )
    private val requested = MutableStateFlow(0L)
    private val retried = MutableStateFlow(0L)
    private val active = MutableStateFlow<Work?>(null)
    // Only the actor mutates this bounded map. Source callbacks own their signal, never the map.
    private val slots = linkedMapOf<NotificationSourceKey, Slot>()
    private val candidates = CollaborationNotificationCandidateQueue()

    fun start() {
        if (!lifetime.isActive || !started.compareAndSet(false, true)) return
        scope.launch {
            try {
                owner.states.collect { state ->
                    observed.value = state
                    val work = active.value
                    if (
                        state !is CollaborationNotificationSurfaceState.Ready ||
                            state.epoch !== work?.epoch
                    )
                        work?.job?.cancel()
                    wake.trySend(Unit)
                }
            } finally {
                observed.value = CollaborationNotificationSurfaceState.Closed
                active.value?.job?.cancel()
                wake.trySend(Unit)
            }
        }
        scope.launch {
            try {
                run()
            } finally {
                withContext(NonCancellable) {
                    active.value?.job?.cancelAndJoin()
                    releaseSlots()
                    owner.closeAndJoin()
                    candidates.close(surfaceFailure(FailureKind.Unavailable))
                }
                lifetime.cancel()
            }
        }
        owner.start()
    }

    /** Finite caller-owned request. The process retains only opaque IDs while awaiting sources. */
    suspend fun publish(
        parent: CollaborationNativeParent,
        candidate: CollaborationNotificationCandidate,
        allowAlert: Boolean = false,
    ): AppResult<CollaborationNotificationPublicationResult> {
        currentCoroutineContext().ensureActive()
        if (!validNotificationCandidate(parent, candidate))
            return surfaceFailure(FailureKind.Validation)
        return withTimeoutOrNull(8_000) {
            coroutineScope {
                val request =
                    NotificationCandidateRequest(
                        parent,
                        candidate,
                        allowAlert,
                        CompletableDeferred(currentCoroutineContext()[Job]),
                    )
                try {
                    if (!lifetime.isActive || !candidates.offer(request))
                        return@coroutineScope surfaceFailure(FailureKind.Unavailable)
                    wake.trySend(Unit)
                    request.result.await()
                } finally {
                    request.result.cancel()
                    candidates.remove(request)
                    withContext(NonCancellable) {
                        active.value?.takeIf { it.request === request }?.job?.cancelAndJoin()
                    }
                    wake.trySend(Unit)
                }
            }
        } ?: surfaceFailure(FailureKind.Timeout)
    }

    suspend fun act(
        input: ActionNotificationInput
    ): AppResult<CollaborationNotificationActionOutcome> = withActions { invoke(input) }

    suspend fun acknowledgeReply(token: CollaborationNotificationActionToken): AppResult<Unit> =
        withActions {
            acknowledgeReply(token)
        }

    private suspend fun <T> withActions(
        operation: suspend CollaborationNotificationActions.() -> AppResult<T>
    ): AppResult<T> {
        currentCoroutineContext().ensureActive()
        val handler = actions ?: return surfaceFailure(FailureKind.Unavailable)
        if (!lifetime.isActive || !started.value) return surfaceFailure(FailureKind.Unavailable)
        return try {
            val result =
                withTimeoutOrNull(8_000) {
                    // Only contention before lease acquisition is retried. No uncertain mutation or
                    // transport result is replayed by a native callback.
                    var applied: AppResult<T>
                    do {
                        val ready =
                            owner.states.first {
                                it !is CollaborationNotificationSurfaceState.Pending &&
                                    (it !is CollaborationNotificationSurfaceState.Ready ||
                                        it.isIdle)
                            }
                        if (
                            !lifetime.isActive ||
                                ready !is CollaborationNotificationSurfaceState.Ready
                        )
                            return@withTimeoutOrNull surfaceFailure(FailureKind.Unavailable)
                        applied = handler.operation()
                        if (
                            applied is AppResult.Failed &&
                                applied.failure.code == NOTIFICATION_SURFACE_BUSY
                        )
                            yield()
                    } while (
                        applied is AppResult.Failed &&
                            applied.failure.code == NOTIFICATION_SURFACE_BUSY
                    )
                    applied
                } ?: surfaceFailure(FailureKind.Timeout)
            currentCoroutineContext().ensureActive()
            if (lifetime.isActive) result else surfaceFailure(FailureKind.Unavailable)
        } finally {
            // The action has released its lease before discovery can resume a Dismissing intent.
            requestScan()
        }
    }

    /** A caller that changes the journal requests discovery, not direct concurrent native I/O. */
    fun requestScan(): Boolean {
        if (!lifetime.isActive) return false
        requested.advance()
        active.value?.takeIf { it.refreshRevision != requested.value }?.job?.cancel()
        wake.trySend(Unit)
        return true
    }

    /** Explicit recovery may reopen failed source subscriptions; no timer restarts them. */
    suspend fun retry(): AppResult<Unit> {
        currentCoroutineContext().ensureActive()
        if (!lifetime.isActive) return surfaceFailure(FailureKind.Unavailable)
        val result = owner.reconcile()
        if (result is AppResult.Failed) return result
        retried.advance()
        return if (requestScan()) AppResult.Success(Unit)
        else surfaceFailure(FailureKind.Unavailable)
    }

    private suspend fun run() {
        var epoch: Any? = null
        var scanRequest = -1L
        var retryRequest = -1L
        var needsScan = true
        var preferCandidate = false
        while (currentCoroutineContext().isActive) {
            wake.receive()
            while (currentCoroutineContext().isActive) {
                val state = observed.value
                if (state is CollaborationNotificationSurfaceState.Closed) return
                val ready = state as? CollaborationNotificationSurfaceState.Ready
                if (ready == null || ready.epoch !== epoch) {
                    releaseSlots()
                    epoch = ready?.epoch
                    if (ready != null)
                        candidates
                            .pending()
                            .filter { it.parent != ready.parent }
                            .forEach { complete(it, surfaceFailure(FailureKind.Unauthorized)) }
                    needsScan = true
                }
                if (state is CollaborationNotificationSurfaceState.Blocked) {
                    candidates.pending().forEach {
                        complete(it, surfaceFailure(FailureKind.Unavailable))
                    }
                }
                if (ready == null || !ready.isIdle) break
                if (scanRequest != requested.value) {
                    scanRequest = requested.value
                    needsScan = true
                }
                if (retryRequest != retried.value) {
                    retryRequest = retried.value
                    restartSources(ready.parent)
                }
                if (needsScan) {
                    val result = scanAndUpdate(ready)
                    if (result == null) continue
                    if (result is AppResult.Failed) {
                        if (result.failure.code != NOTIFICATION_SURFACE_BUSY)
                            owner.pause(ready.parent)
                        break
                    }
                    needsScan = false
                    continue
                }
                synchronizeCandidates(ready.parent)
                val candidate =
                    candidates.pending().firstOrNull {
                        it.parent == ready.parent &&
                            it.busyRevision != ready.revision &&
                            slots[it.key]?.signal?.value?.observed == true
                    }
                val selected = candidate?.let { slots[it.key] }
                val dirty =
                    slots.values.firstOrNull {
                        it.reference != null &&
                            it.handled !== it.signal.value &&
                            it.busyRevision != ready.revision
                    }
                val failedCurrent =
                    selected?.takeIf {
                        it.reference != null &&
                            it.handled !== it.signal.value &&
                            it.busyRevision != ready.revision &&
                            it.signal.value.result is AppResult.Failed
                    }
                if (
                    candidate != null &&
                        selected != null &&
                        failedCurrent == null &&
                        (preferCandidate || dirty == null)
                ) {
                    needsScan = deliver(ready, selected, candidate)
                    preferCandidate = false
                } else if (failedCurrent != null || dirty != null) {
                    needsScan = settle(ready, requireNotNull(failedCurrent ?: dirty))
                    preferCandidate = true
                } else break
            }
        }
    }

    private suspend fun scanAndUpdate(
        ready: CollaborationNotificationSurfaceState.Ready
    ): AppResult<Unit>? {
        val result = perform(ready) { scan(ready.parent) } ?: return null
        if (result is AppResult.Failed) return result
        return perform(ready) {
            withTimeoutOrNull(8_000) {
                updateSlots(ready.parent, (result as AppResult.Success).value)
            } ?: surfaceFailure(FailureKind.Timeout)
        }
    }

    private suspend fun synchronizeCandidates(parent: CollaborationNativeParent) {
        candidates
            .pending()
            .filter { it.parent != parent }
            .forEach { complete(it, surfaceFailure(FailureKind.Unauthorized)) }
        val requests = candidates.pending()
        val keys = requests.map { it.key }.toSet()
        val unused = slots.values.filter { it.reference == null && it.key !in keys }
        removeSlots(unused)
        for (request in requests) {
            if (request.key in slots) continue
            if (slots.size >= 64) {
                complete(request, surfaceFailure(FailureKind.Unavailable))
                continue
            }
            val slot = Slot(request.key)
            slots[request.key] = slot
            subscribe(parent, slot)
        }
    }

    private suspend fun deliver(
        ready: CollaborationNotificationSurfaceState.Ready,
        slot: Slot,
        request: NotificationCandidateRequest,
    ): Boolean {
        val signal = slot.signal.value
        val result =
            (signal.result as? AppResult.Failed)
                ?: perform(ready, slot, signal, request) {
                    publishCandidate(ready.parent, request.candidate, request.allowAlert)
                }
        if (result == null) {
            // Cancellation may follow committed preparation. Keep its journal recoverable, but
            // do not immediately replay the same source behind the cancelled caller's back.
            if (!request.result.isActive && slot.signal.value === signal) slot.handled = signal
            return true
        }
        if (result is AppResult.Failed && result.failure.code == NOTIFICATION_SURFACE_BUSY) {
            request.busyRevision = ready.revision
            return false
        }
        // A suppressed/older candidate must not acknowledge a dirty, unrelated current card.
        if (
            slot.reference == null ||
                result == AppResult.Success(CollaborationNotificationPublicationResult.Submitted) ||
                result ==
                    AppResult.Success(
                        CollaborationNotificationPublicationResult.SuppressionRecorded
                    )
        )
            slot.handled = signal
        complete(request, result)
        return true
    }

    private fun complete(
        request: NotificationCandidateRequest,
        result: AppResult<CollaborationNotificationPublicationResult>,
    ) {
        request.result.complete(result)
        candidates.remove(request)
    }

    private suspend fun settle(
        ready: CollaborationNotificationSurfaceState.Ready,
        slot: Slot,
    ): Boolean {
        val signal = slot.signal.value
        val reference = slot.reference ?: return false
        val result =
            perform(ready, slot, signal) {
                reconcile(ready.parent, reference, (signal.result as? AppResult.Failed)?.failure)
            } ?: return true
        if (result is AppResult.Failed && result.failure.code == NOTIFICATION_SURFACE_BUSY) {
            // Capture the attempt's revision, not a later completion we may already have seen.
            slot.busyRevision = ready.revision
            return false
        }
        slot.busyRevision = null
        slot.handled = signal
        slot.contended =
            if (
                result == AppResult.Success(CollaborationNotificationReconciliationResult.Contended)
            )
                reference
            else null
        return true
    }

    private suspend fun scan(
        parent: CollaborationNativeParent
    ): AppResult<List<CollaborationNotificationPublicationReference>> =
        withTimeoutOrNull(8_000) {
            val references = mutableListOf<CollaborationNotificationPublicationReference>()
            var after: String? = null
            do {
                val result = read(parent, after)
                currentCoroutineContext().ensureActive()
                if (result is AppResult.Failed) return@withTimeoutOrNull result
                val page = (result as AppResult.Success).value
                if (
                    page.parent != parent ||
                        page.references.size > 4 ||
                        references.size + page.references.size > 64
                )
                    return@withTimeoutOrNull surfaceFailure(FailureKind.Unavailable)
                val items = page.references.toList()
                if (items.size > 4 || references.size + items.size > 64)
                    return@withTimeoutOrNull surfaceFailure(FailureKind.Unavailable)
                val ids = items.map { it.id }
                val cursor = after
                if (
                    ids != ids.distinct().sorted() ||
                        ids.any { cursor != null && it <= cursor } ||
                        (page.nextAfter != null &&
                            (items.isEmpty() || page.nextAfter != ids.last())) ||
                        items.any {
                            !validCollaborationNotificationReference(it) ||
                                !it.state.isPendingPublication() ||
                                it.parent.accountId != parent.accountId ||
                                it.parent.audience != parent.audience
                        }
                )
                    return@withTimeoutOrNull surfaceFailure(FailureKind.Unavailable)
                references += items
                after = page.nextAfter
            } while (after != null)
            if (references.map { it.key() }.distinct().size != references.size)
                return@withTimeoutOrNull surfaceFailure(FailureKind.Unavailable)
            AppResult.Success(references.toList())
        } ?: surfaceFailure(FailureKind.Timeout)

    private suspend fun updateSlots(
        parent: CollaborationNativeParent,
        references: List<CollaborationNotificationPublicationReference>,
    ): AppResult<Unit> {
        val incoming = references.associateBy { it.key() }
        val requests = candidates.pending().filter { it.parent == parent }
        val provisional =
            requests
                .map { it.key }
                .distinct()
                .filter { it !in incoming }
                .take(64 - incoming.size)
                .toSet()
        requests
            .filter { it.key !in incoming && it.key !in provisional }
            .forEach { complete(it, surfaceFailure(FailureKind.Unavailable)) }
        // Exact cleanup precedes forgetting a journal reference, even when its source survives
        // for another queued candidate. A changed native ID must not orphan the previous card.
        for (slot in slots.values) {
            val old = slot.reference ?: continue
            val next = incoming[slot.key]
            if (
                next != null &&
                    next.id == old.id &&
                    next.target.notificationId == old.target.notificationId
            )
                continue
            val target = old.target
            val removed =
                owner.withOwner(parent) {
                    remove(ActionNotificationReference(target.notificationId, target.renderId))
                }
            if (removed is AppResult.Failed) return removed
        }
        removeSlots(slots.values.filter { it.key !in incoming && it.key !in provisional })
        for (slot in slots.values) if (slot.key !in incoming) {
            slot.reference = null
            slot.contended = null
            slot.busyRevision = null
        }
        for (reference in references) {
            val key = reference.key()
            val existing = slots[key]
            if (existing == null) {
                val slot = Slot(key, reference)
                slots[key] = slot
                subscribe(parent, slot)
            } else {
                if (existing.contended != null && existing.contended != reference) {
                    existing.contended = null
                    existing.handled = null
                    existing.busyRevision = null
                }
                // Promotion and our own ACK keep the same admitted source subscription.
                existing.reference = reference
            }
        }
        return AppResult.Success(Unit)
    }

    private suspend fun removeSlots(obsolete: List<Slot>) {
        obsolete.forEach { it.observer?.cancel() }
        obsolete.mapNotNull { it.observer }.joinAll()
        obsolete.forEach { slots.remove(it.key) }
    }

    private fun CollaborationNotificationPublicationReference.key() =
        NotificationSourceKey(target.companyId, target.conversationId)

    private suspend fun restartSources(parent: CollaborationNativeParent) {
        slots.values.forEach { it.observer?.cancel() }
        slots.values.mapNotNull { it.observer }.joinAll()
        slots.values.forEach { slot ->
            slot.signal.value = Signal(surfaceFailure(FailureKind.Unavailable), observed = false)
            slot.handled = null
            slot.busyRevision = null
            subscribe(parent, slot)
        }
    }

    private fun subscribe(parent: CollaborationNativeParent, slot: Slot) {
        val target = slot.key
        slot.observer =
            scope.launch {
                try {
                    observe(parent, target.companyId, target.conversationId).collect {
                        changed(slot, it)
                    }
                    changed(slot, surfaceFailure(FailureKind.Unavailable))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    changed(slot, surfaceFailure(FailureKind.Unexpected))
                }
            }
    }

    private fun changed(slot: Slot, value: AppResult<Unit>) {
        slot.signal.value = Signal(value)
        active.value?.takeIf { it.slot === slot && it.signal !== slot.signal.value }?.job?.cancel()
        wake.trySend(Unit)
    }

    private suspend fun <T> perform(
        ready: CollaborationNotificationSurfaceState.Ready,
        slot: Slot? = null,
        signal: Signal? = null,
        request: NotificationCandidateRequest? = null,
        operation: suspend () -> AppResult<T>,
    ): AppResult<T>? = supervisorScope {
        val refreshRevision = requested.value
        val pending = async(start = CoroutineStart.LAZY) { operation() }
        val work = Work(ready.epoch, slot, signal, refreshRevision, request, pending)
        active.value = work
        fun current(): Boolean {
            val now = observed.value as? CollaborationNotificationSurfaceState.Ready
            return now?.epoch === ready.epoch &&
                requested.value == refreshRevision &&
                (slot == null || slot.signal.value === signal) &&
                (request == null || request.result.isActive)
        }
        try {
            if (!current()) return@supervisorScope null
            pending.start()
            val result = pending.await()
            currentCoroutineContext().ensureActive()
            if (current()) result else null
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            null
        } catch (_: Exception) {
            surfaceFailure(FailureKind.Unexpected)
        } finally {
            withContext(NonCancellable) { pending.cancelAndJoin() }
            active.compareAndSet(work, null)
        }
    }

    private suspend fun releaseSlots() {
        slots.values.forEach { it.observer?.cancel() }
        slots.values.mapNotNull { it.observer }.joinAll()
        slots.clear()
    }

    override fun close() {
        candidates.close(surfaceFailure(FailureKind.Unavailable))
        active.value?.job?.cancel()
        lifetime.cancel()
        owner.close()
    }

    suspend fun closeAndJoin() {
        close()
        lifetime.join()
        owner.closeAndJoin()
    }

    private class Signal(val result: AppResult<Unit>, val observed: Boolean = true)

    private class Slot(
        val key: NotificationSourceKey,
        var reference: CollaborationNotificationPublicationReference? = null,
    ) {
        val signal =
            MutableStateFlow(Signal(surfaceFailure(FailureKind.Unavailable), observed = false))
        var handled: Signal? = null
        var contended: CollaborationNotificationPublicationReference? = null
        var busyRevision: Long? = null
        var observer: Job? = null
    }

    private class Work(
        val epoch: Any,
        val slot: Slot?,
        val signal: Signal?,
        val refreshRevision: Long,
        val request: NotificationCandidateRequest?,
        val job: Job,
    )

    private fun MutableStateFlow<Long>.advance() = update {
        if (it == Long.MAX_VALUE) 0 else it + 1
    }
}
