package dev.fajar.starter.app.notifications

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.notifications.data.dto.ActionNotificationReference
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

/**
 * Process-owned reconciliation of bounded opaque journal references. This owner must be the sole
 * normal native writer. Candidate delivery, focused-thread registration and native callbacks are
 * separate integration steps; calling requestScan does not grant permission to publish content.
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
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
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
    private val slots = linkedMapOf<String, Slot>()

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
                }
                lifetime.cancel()
            }
        }
        owner.start()
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
        while (currentCoroutineContext().isActive) {
            wake.receive()
            while (currentCoroutineContext().isActive) {
                val state = observed.value
                if (state is CollaborationNotificationSurfaceState.Closed) return
                val ready = state as? CollaborationNotificationSurfaceState.Ready
                if (ready == null || ready.epoch !== epoch) {
                    releaseSlots()
                    epoch = ready?.epoch
                    needsScan = true
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
                    val result = perform(ready) { scan(ready.parent) }
                    if (result == null) continue
                    if (result is AppResult.Failed) {
                        owner.pause(ready.parent)
                        break
                    }
                    val updated =
                        perform(ready) {
                            withTimeoutOrNull(8_000) {
                                updateSlots(ready.parent, (result as AppResult.Success).value)
                            } ?: surfaceFailure(FailureKind.Timeout)
                        }
                    if (updated == null) continue
                    if (updated is AppResult.Failed) {
                        if (updated.failure.code != NOTIFICATION_SURFACE_BUSY)
                            owner.pause(ready.parent)
                        break
                    }
                    needsScan = false
                    continue
                }
                val slot =
                    slots.values.firstOrNull {
                        it.handled !== it.signal.value && it.busyRevision != ready.revision
                    } ?: break
                needsScan = settle(ready, slot)
            }
        }
    }

    private suspend fun settle(
        ready: CollaborationNotificationSurfaceState.Ready,
        slot: Slot,
    ): Boolean {
        val signal = slot.signal.value
        val reference = slot.reference
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
                                !it.state.isActivePublication() ||
                                it.parent.accountId != parent.accountId ||
                                it.parent.audience != parent.audience
                        }
                )
                    return@withTimeoutOrNull surfaceFailure(FailureKind.Unavailable)
                references += items
                after = page.nextAfter
            } while (after != null)
            AppResult.Success(references.toList())
        } ?: surfaceFailure(FailureKind.Timeout)

    private suspend fun updateSlots(
        parent: CollaborationNativeParent,
        references: List<CollaborationNotificationPublicationReference>,
    ): AppResult<Unit> {
        val incoming = references.associateBy { it.id }
        val obsolete =
            slots.values.filter { old ->
                val next = incoming[old.reference.id]
                next == null ||
                    next.target.companyId != old.reference.target.companyId ||
                    next.target.conversationId != old.reference.target.conversationId
            }
        // A journal may have been retired by another finite action. Forgetting the subscription
        // alone would leave its private native card behind. Exact cleanup preserves newer renders.
        for (old in obsolete) {
            val target = old.reference.target
            val removed =
                owner.withOwner(parent) {
                    remove(ActionNotificationReference(target.notificationId, target.renderId))
                }
            if (removed is AppResult.Failed) return removed
        }
        obsolete.forEach { it.observer?.cancel() }
        obsolete.mapNotNull { it.observer }.joinAll()
        obsolete.forEach { slots.remove(it.reference.id) }
        for (reference in references) {
            val existing = slots[reference.id]
            if (existing == null) {
                val slot = Slot(reference)
                slots[reference.id] = slot
                subscribe(parent, slot)
            } else {
                if (existing.contended != null && existing.contended != reference) {
                    existing.contended = null
                    existing.handled = null
                    existing.busyRevision = null
                }
                // Our own published ACK changes the reference without changing the source signal.
                existing.reference = reference
            }
        }
        return AppResult.Success(Unit)
    }

    private suspend fun restartSources(parent: CollaborationNativeParent) {
        slots.values.forEach { it.observer?.cancel() }
        slots.values.mapNotNull { it.observer }.joinAll()
        slots.values.forEach { slot ->
            slot.signal.value = Signal(surfaceFailure(FailureKind.Unavailable))
            slot.handled = null
            slot.busyRevision = null
            subscribe(parent, slot)
        }
    }

    private fun subscribe(parent: CollaborationNativeParent, slot: Slot) {
        val target = slot.reference.target
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
        operation: suspend () -> AppResult<T>,
    ): AppResult<T>? = supervisorScope {
        val refreshRevision = requested.value
        val pending = async(start = CoroutineStart.LAZY) { operation() }
        val work = Work(ready.epoch, slot, signal, refreshRevision, pending)
        active.value = work
        fun current(): Boolean {
            val now = observed.value as? CollaborationNotificationSurfaceState.Ready
            return now?.epoch === ready.epoch &&
                requested.value == refreshRevision &&
                (slot == null || slot.signal.value === signal)
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
        active.value?.job?.cancel()
        lifetime.cancel()
        owner.close()
    }

    suspend fun closeAndJoin() {
        close()
        lifetime.join()
        owner.closeAndJoin()
    }

    private class Signal(val result: AppResult<Unit>)

    private class Slot(var reference: CollaborationNotificationPublicationReference) {
        val signal = MutableStateFlow(Signal(surfaceFailure(FailureKind.Unavailable)))
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
        val job: Job,
    )

    private fun MutableStateFlow<Long>.advance() = update {
        if (it == Long.MAX_VALUE) 0 else it + 1
    }
}
