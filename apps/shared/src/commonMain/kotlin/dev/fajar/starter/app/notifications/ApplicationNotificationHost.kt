package dev.fajar.starter.app.notifications

import dev.fajar.starter.app.bootstrap.ApplicationCollaborationState
import dev.fajar.starter.app.work.recoveryParent
import dev.fajar.starter.collaboration.domain.entities.CollaborationNativeParent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** One application lifetime; selected-company changes do not replace its native parent. */
class ApplicationNotificationHost(
    private val states: Flow<ApplicationCollaborationState>,
    private val delivery: CollaborationNotificationDelivery,
    private val create: (StateFlow<CollaborationNativeParent?>) -> CollaborationNotificationProcess,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : AutoCloseable {
    private val lifetime = SupervisorJob()
    private val scope = CoroutineScope(lifetime + dispatcher)
    private val started = MutableStateFlow(false)
    private val parents = MutableStateFlow<CollaborationNativeParent?>(null)
    private val ready = MutableStateFlow(false)

    fun start() {
        if (!lifetime.isActive || !started.compareAndSet(false, true)) return
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var process: CollaborationNotificationProcess? = null
            var transferred = false
            try {
                ensureActive()
                process = create(parents.asStateFlow())
                ensureActive()
                transferred = delivery.start(process, ready.asStateFlow())
                if (!transferred) return@launch
                ensureActive()
                states.collect {
                    ensureActive()
                    ready.value = false
                    parents.value = it.recoveryParent()
                    ready.value = it !is ApplicationCollaborationState.Pending
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Source failure closes this host; it cannot retain a previous native parent.
            } finally {
                ready.value = false
                parents.value = null
                withContext(NonCancellable) {
                    if (transferred) delivery.closeAndJoin() else process?.closeAndJoin()
                }
                lifetime.cancel()
            }
        }
    }

    override fun close() {
        ready.value = false
        parents.value = null
        lifetime.cancel()
    }

    suspend fun closeAndJoin() {
        close()
        lifetime.join()
    }
}
