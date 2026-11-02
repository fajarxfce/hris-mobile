package dev.fajar.starter.app.bootstrap

import dev.fajar.starter.app.work.recoveryParent
import dev.fajar.starter.collaboration.domain.entities.CollaborationNativeParent
import dev.fajar.starter.collaboration.domain.usecases.MaintainCollaborationRuntimeOwnership
import dev.fajar.starter.collaboration.domain.usecases.QueueCollaborationText
import dev.fajar.starter.collaboration.domain.usecases.RequestCollaborationRecovery
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.sync.domain.repositories.SyncScheduleRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** The application process owns observation independently of activities and chat routes. */
class ApplicationCollaborationLifetime(
    private val maintain: MaintainCollaborationRuntimeOwnership,
    private val schedules: SyncScheduleRepository,
    private val requestRecovery: RequestCollaborationRecovery,
    private val build: AppBuild,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : AutoCloseable {
    private val lifetime = SupervisorJob()
    private val scope = CoroutineScope(lifetime + dispatcher)
    private val started = MutableStateFlow(false)
    private val mutableState =
        MutableStateFlow<ApplicationCollaborationState>(ApplicationCollaborationState.Pending)
    val state = mutableState.asStateFlow()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            var lastParent: CollaborationNativeParent? = null
            try {
                maintain.publications().collect { publication ->
                    val current =
                        mutableState.updateAndGet { previous ->
                            if (previous is ApplicationCollaborationState.Stopped) previous
                            else ApplicationCollaborationState.Observed(publication)
                        }
                    val result = (publication as? AppResult.Success)?.value?.result
                    val parent = current.recoveryParent()
                    if (parent != lastParent) {
                        lastParent = parent
                        if (
                            parent != null &&
                                build.platform in setOf(AppPlatform.Android, AppPlatform.Ios)
                        ) {
                            currentCoroutineContext().ensureActive()
                            withTimeoutOrNull(1_500) { requestRecovery() }
                        }
                    }
                    if (
                        current is ApplicationCollaborationState.Observed &&
                            result is AppResult.Success &&
                            result.value != null
                    ) {
                        currentCoroutineContext().ensureActive()
                        // Durable commands survive startup and ownership renewal. The worker
                        // rechecks current scope; this wakeup carries no account or message body.
                        withTimeoutOrNull(1_500) {
                            schedules.request(QueueCollaborationText.DELIVERY_KEY)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                stopped(runtimeStopped())
            } finally {
                // Completion and failed observation cannot leave a previous parent published.
                stopped(if (lifetime.isActive) runtimeStopped() else null)
            }
        }
    }

    private fun stopped(failure: Failure?) {
        mutableState.update { previous ->
            if (previous is ApplicationCollaborationState.Stopped) previous
            else ApplicationCollaborationState.Stopped(failure)
        }
    }

    private fun runtimeStopped() =
        Failure(
            FailureKind.Unexpected,
            "Chat connection stopped.",
            code = "client.collaboration_runtime_stopped",
        )

    override fun close() {
        stopped(null)
        lifetime.cancel()
    }

    suspend fun closeAndJoin() {
        close()
        lifetime.join()
    }
}
