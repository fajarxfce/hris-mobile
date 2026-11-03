package dev.fajar.starter.app.notifications

import dev.fajar.starter.collaboration.domain.entities.CollaborationNativeParent
import dev.fajar.starter.common.result.*
import dev.fajar.starter.notifications.data.datasources.ActionNotificationDisplaySource
import dev.fajar.starter.notifications.data.errors.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-owned serialization, independent of an Activity or selected company. Parent epochs and
 * raw cleanup are not HRIS company/source admission. The coordinator must validate those facts
 * within each finite request, immediately before native submission and journal settlement.
 */
class CollaborationNotificationSurfaceOwner(
    private val parents: StateFlow<CollaborationNativeParent?>,
    private val source: ActionNotificationDisplaySource,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : AutoCloseable {
    private val lifetime = SupervisorJob()
    private val scope = CoroutineScope(lifetime + dispatcher)
    private val started = MutableStateFlow(false)
    private val phase = MutableStateFlow<Phase>(Phase.Dormant)
    private val active = MutableStateFlow<Job?>(null)
    private val execution = Mutex()

    fun start() {
        if (!lifetime.isActive || !started.compareAndSet(false, true)) return
        scope.launch {
            try {
                parents.collect { replace(it) }
            } finally {
                phase.value = Phase.Closed
                withContext(NonCancellable) {
                    active.value?.cancelAndJoin()
                    execution.withLock { clear() }
                }
            }
        }
    }

    /**
     * No queue of private payloads; a busy or unprepared owner asks the caller to retry explicitly.
     */
    suspend fun <T> withOwner(
        parent: CollaborationNativeParent,
        operation: suspend CollaborationNotificationSurfaceLease.() -> AppResult<T>,
    ): AppResult<T> {
        currentCoroutineContext().ensureActive()
        val ready = phase.value as? Phase.Ready ?: return surfaceFailure(FailureKind.Unavailable)
        if (ready.parent != parent || !current(ready))
            return surfaceFailure(FailureKind.Unauthorized)
        if (!execution.tryLock()) return surfaceFailure(FailureKind.Unavailable)
        return try {
            if (!current(ready)) return surfaceFailure(FailureKind.Unauthorized)
            request(ready, operation)
        } finally {
            execution.unlock()
        }
    }

    /** Only an explicitly retried failed cleanup can reopen the same epoch's blocked gate. */
    suspend fun reconcile(): AppResult<Unit> {
        currentCoroutineContext().ensureActive()
        val captured = phase.value
        if (captured is Phase.Ready && current(captured)) return AppResult.Success(Unit)
        if (captured !is Phase.Blocked || !lifetime.isActive)
            return surfaceFailure(FailureKind.Unavailable)
        if (!execution.tryLock()) return surfaceFailure(FailureKind.Unavailable)
        return try {
            if (phase.value !== captured) return surfaceFailure(FailureKind.Unavailable)
            val cleared = clear()
            if (cleared is AppResult.Failed) return cleared
            currentCoroutineContext().ensureActive()
            val parent = parents.value
            if (!lifetime.isActive || parent == null || parent != captured.parent)
                return surfaceFailure(FailureKind.Unauthorized)
            if (!phase.compareAndSet(captured, Phase.Ready(parent)))
                surfaceFailure(FailureKind.Unauthorized)
            else AppResult.Success(Unit)
        } finally {
            execution.unlock()
        }
    }

    private suspend fun replace(parent: CollaborationNativeParent?) {
        val changing = Phase.Changing()
        phase.value = changing
        active.value?.cancelAndJoin()
        execution.withLock {
            val cleared = clear()
            currentCoroutineContext().ensureActive()
            if (!lifetime.isActive || parents.value != parent) return@withLock
            val next =
                if (cleared is AppResult.Success && parent != null) Phase.Ready(parent)
                else Phase.Blocked(parent)
            phase.compareAndSet(changing, next)
        }
    }

    private suspend fun <T> request(
        ready: Phase.Ready,
        operation: suspend CollaborationNotificationSurfaceLease.() -> AppResult<T>,
    ): AppResult<T> = supervisorScope {
        val lease = CollaborationNotificationSurfaceLease(source) { current(ready) }
        val pending =
            async(start = CoroutineStart.LAZY) {
                withContext(NotificationSurfaceExecution(lease)) { lease.operation() }
            }
        active.value = pending
        var successful = false
        try {
            // Invalidation may run between mutex acquisition and child registration.
            if (!current(ready)) return@supervisorScope surfaceFailure(FailureKind.Unauthorized)
            val result =
                withTimeoutOrNull(10_000) {
                    pending.start()
                    pending.await()
                } ?: surfaceFailure(FailureKind.Timeout)
            currentCoroutineContext().ensureActive()
            if (!current(ready)) return@supervisorScope surfaceFailure(FailureKind.Unauthorized)
            successful = result is AppResult.Success
            result
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            if (current(ready)) throw cancelled
            surfaceFailure(FailureKind.Unauthorized)
        } catch (failure: Exception) {
            AppResult.Failed(notificationFailure(failure))
        } finally {
            lease.close()
            withContext(NonCancellable) {
                pending.cancelAndJoin()
                if (!successful) {
                    val clean = withTimeoutOrNull(2_000) { lease.compensate() } ?: false
                    if (!clean) phase.compareAndSet(ready, Phase.Blocked(ready.parent))
                }
                active.compareAndSet(pending, null)
            }
        }
    }

    private fun current(ready: Phase.Ready) =
        lifetime.isActive && phase.value === ready && parents.value == ready.parent

    private suspend fun clear(): AppResult<Unit> =
        withTimeoutOrNull(2_000) { safeNotificationCall { source.clear() } }
            ?: surfaceFailure(FailureKind.Timeout)

    override fun close() {
        phase.value = Phase.Closed
        active.value?.cancel()
        lifetime.cancel()
    }

    suspend fun closeAndJoin() {
        close()
        lifetime.join()
    }

    private sealed interface Phase {
        data object Dormant : Phase

        class Changing : Phase

        class Ready(val parent: CollaborationNativeParent) : Phase

        class Blocked(val parent: CollaborationNativeParent?) : Phase

        data object Closed : Phase
    }
}
