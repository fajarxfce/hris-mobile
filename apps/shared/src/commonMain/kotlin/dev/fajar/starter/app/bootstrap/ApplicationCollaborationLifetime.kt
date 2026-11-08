package dev.fajar.starter.app.bootstrap

import dev.fajar.starter.app.work.recoveryParent
import dev.fajar.starter.collaboration.domain.entities.CollaborationNativeParent
import dev.fajar.starter.collaboration.domain.entities.CollaborationOwner
import dev.fajar.starter.collaboration.domain.usecases.MaintainCollaborationRuntimeOwnership
import dev.fajar.starter.collaboration.domain.usecases.QueueCollaborationText
import dev.fajar.starter.collaboration.domain.usecases.RequestCollaborationRecovery
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.push.entities.NativePushStatus
import dev.fajar.starter.sync.domain.repositories.SyncScheduleRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** The application process owns observation independently of activities and chat routes. */
class ApplicationCollaborationLifetime(
    private val maintain: MaintainCollaborationRuntimeOwnership,
    private val schedules: SyncScheduleRepository,
    private val requestRecovery: RequestCollaborationRecovery,
    private val build: AppBuild,
    private val observePush: (String) -> Flow<AppResult<NativePushStatus>>,
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
            if (build.platform !in setOf(AppPlatform.Android, AppPlatform.Ios)) return@launch
            try {
                wakeCollaborationOnPushRegistration(state, observePush) { requestRecovery() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Failed observation cannot keep an old registration active or start a retry loop.
            }
        }
        scope.launch {
            var lastParent: CollaborationNativeParent? = null
            var scheduledOwner: CollaborationOwner? = null
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
                        scheduledOwner = null
                        if (
                            parent != null &&
                                build.platform in setOf(AppPlatform.Android, AppPlatform.Ios)
                        ) {
                            currentCoroutineContext().ensureActive()
                            withTimeoutOrNull(1_500) { requestRecovery() }
                        }
                    }
                    // Expiring live admission does not create new delivery intent. Its renewal
                    // may reopen SDK resources, but must not restart blocked message work.
                    // Other failures/selection changes retain the normal recovery wake.
                    val revalidating =
                        result is AppResult.Failed &&
                            result.failure.kind == FailureKind.Unavailable &&
                            result.failure.code in
                                setOf(
                                    "client.collaboration_revalidation_required",
                                    "client.workspace_expired",
                                    "client.company_policy_expired",
                                )
                    if (!revalidating && (result !is AppResult.Success || result.value == null)) {
                        scheduledOwner = null
                    }
                    if (
                        current is ApplicationCollaborationState.Observed &&
                            result is AppResult.Success &&
                            result.value != null &&
                            result.value != scheduledOwner
                    ) {
                        currentCoroutineContext().ensureActive()
                        // The process and selected owner own recovery; routes do not. A failed
                        // registration remains eligible on the next admitted publication.
                        val requested =
                            withTimeoutOrNull(1_500) {
                                schedules.request(QueueCollaborationText.DELIVERY_KEY)
                            }
                        if (requested is AppResult.Success) scheduledOwner = result.value
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
