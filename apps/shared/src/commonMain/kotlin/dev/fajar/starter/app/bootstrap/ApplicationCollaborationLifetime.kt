package dev.fajar.starter.app.bootstrap

import dev.fajar.starter.app.work.recoveryParent
import dev.fajar.starter.collaboration.domain.entities.CollaborationActivationProgress
import dev.fajar.starter.collaboration.domain.entities.CollaborationNativeParent
import dev.fajar.starter.collaboration.domain.entities.CollaborationOwner
import dev.fajar.starter.collaboration.domain.usecases.MaintainCollaborationRuntimeOwnership
import dev.fajar.starter.collaboration.domain.usecases.QueueCollaborationText
import dev.fajar.starter.collaboration.domain.usecases.RequestCollaborationRecovery
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.push.entities.NativePushStatus
import dev.fajar.starter.observability.*
import dev.fajar.starter.sync.domain.repositories.SyncScheduleRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The application process owns observation independently of activities and chat routes. */
class ApplicationCollaborationLifetime(
    private val maintain: MaintainCollaborationRuntimeOwnership,
    private val schedules: SyncScheduleRepository,
    private val requestRecovery: RequestCollaborationRecovery,
    private val build: AppBuild,
    private val observePush: (String) -> Flow<AppResult<NativePushStatus>>,
    private val activate: (CollaborationOwner) -> Flow<AppResult<CollaborationActivationProgress>>,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val observeReadiness: () -> Flow<AppResult<Boolean>> = { emptyFlow() },
    private val observeCredentialRecovery: (CollaborationOwner) -> Flow<Unit> = { emptyFlow() },
) : AutoCloseable {
    private val lifetime = SupervisorJob()
    private val scope = CoroutineScope(lifetime + dispatcher)
    private val started = MutableStateFlow(false)
    private val mutableState =
        MutableStateFlow<ApplicationCollaborationState>(ApplicationCollaborationState.Pending)
    val state = mutableState.asStateFlow()
    private var activatedOwner: CollaborationOwner? = null
    private var activation: Job? = null
    private var credentialRecovery: Job? = null
    private val activationChanges = Mutex()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        // Subscribe before activation can start syncing. This child never opens another SDK.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            if (build.platform !in setOf(AppPlatform.Android, AppPlatform.Ios)) return@launch
            try {
                observeReadiness().collect { result ->
                    if ((result as? AppResult.Success)?.value == true)
                        Diagnostics.record(
                            Diagnostic(
                                DiagnosticArea.Worker,
                                DiagnosticKind.OperationCompleted,
                                "ChatReadinessScheduled",
                            )
                        )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Durable originals and startup/network recovery own a missed signal, not a timer.
            }
        }
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
                        retainActivation(null)
                    }
                    if (
                        current is ApplicationCollaborationState.Observed &&
                            result is AppResult.Success
                    )
                        retainActivation(result.value)
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
                withContext(NonCancellable) { retainActivation(null) }
                // Completion and failed observation cannot leave a previous parent published.
                stopped(if (lifetime.isActive) runtimeStopped() else null)
            }
        }
    }

    /** Ownership and admitted recovery are serialized; token age creates neither. */
    private suspend fun retainActivation(owner: CollaborationOwner?) =
        activationChanges.withLock {
            if (owner == activatedOwner) {
                // A new admitted publication can resume a retired acquisition. Keep its
                // credential observer so an unvalidated replacement cannot reset retry policy.
                if (owner != null && activation?.isCancelled == true) {
                    activation?.join()
                    activation = startActivation(owner)
                }
                return@withLock
            }
            activation?.cancelAndJoin()
            credentialRecovery?.cancelAndJoin()
            activatedOwner = owner
            credentialRecovery =
                owner?.let { retained ->
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        try {
                            observeCredentialRecovery(retained).collect {
                                activationChanges.withLock {
                                    if (activatedOwner == retained) {
                                        activation?.cancelAndJoin()
                                        activation = startActivation(retained)
                                    }
                                }
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            // An observation failure cannot manufacture another activation attempt.
                        }
                    }
                }
            activation = owner?.let(::startActivation)
        }

    private fun startActivation(owner: CollaborationOwner): Job =
        scope.launch {
            try {
                activate(owner).collect()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Another attempt requires an owned domain recovery signal.
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
