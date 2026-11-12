package dev.fajar.starter.app.bootstrap

import dev.fajar.starter.app.work.recoveryParent
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.observability.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The process owns both incoming collectors; foreground entry is a fact, never a retry timer. */
class ApplicationCollaborationIncomingSources(
    private val observeStartup:
        (CollaborationNativeParent) -> Flow<AppResult<CollaborationSpaceIncomingStartup?>>,
    private val observePublication:
        (CollaborationSpaceRuntimeReady) -> Flow<AppResult<CollaborationSpaceRuntimePublication?>>,
    private val retainKeys: (CollaborationSpaceRuntimeReady) -> Flow<AppResult<Boolean>>,
    private val retainSync: (CollaborationSpaceRuntimeReady) -> Flow<AppResult<Boolean>>,
    private val build: AppBuild,
) {
    private val collection = Mutex()
    private val foregroundRevision = MutableStateFlow(0L)

    /** Called once by the application foreground owner, never by a chat route. */
    fun foregroundEntered() {
        foregroundRevision.update { it + 1 }
    }

    /**
     * The caller's process lifetime cancels and joins this child. It owns no native close handle.
     */
    suspend fun collect(states: StateFlow<ApplicationCollaborationState>) {
        if (build.platform !in setOf(AppPlatform.Android, AppPlatform.Ios)) return
        collection.withLock {
            combine(
                    states.map { it.recoveryParent() }.distinctUntilChanged(),
                    foregroundRevision,
                ) { parent, revision ->
                    parent to revision
                }
                .collectLatest { (parent, _) ->
                    if (parent != null) {
                        try {
                            collectParent(parent)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            Diagnostics.record(
                                Diagnostic(
                                    DiagnosticArea.Application,
                                    DiagnosticKind.OperationFailed,
                                    "ChatIncomingObservationStopped",
                                )
                            )
                        }
                    }
                }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun collectParent(parent: CollaborationNativeParent) {
        observeStartup(parent)
            .map { result ->
                if (result is AppResult.Success && result.value?.owner?.parent != parent)
                    AppResult.Success(null)
                else result
            }
            .onCompletion { cause -> if (cause == null) emit(AppResult.Success(null)) }
            .buffer(0)
            .flatMapLatest<
                AppResult<CollaborationSpaceIncomingStartup?>,
                AppResult<CollaborationSpaceRuntimePublication?>,
            > { result ->
                val startup = (result as? AppResult.Success)?.value
                if (startup == null) flowOf(AppResult.Success(null))
                else {
                    val runtime =
                        CollaborationSpaceRuntimeReady(
                            startup.owner,
                            startup.deviceId,
                            startup.operationId,
                        )
                    observePublication(runtime)
                        .map { published ->
                            if (
                                published is AppResult.Success &&
                                    published.value?.runtime != runtime
                            )
                                AppResult.Success(null)
                            else published
                        }
                        .onCompletion { cause -> if (cause == null) emit(AppResult.Success(null)) }
                }
            }
            .buffer(0)
            .distinctUntilChanged()
            .collectLatest { result ->
                val publication = (result as? AppResult.Success)?.value ?: return@collectLatest
                supervisorScope {
                    launch(start = CoroutineStart.UNDISPATCHED) { collectKeys(publication.runtime) }
                    launch(start = CoroutineStart.UNDISPATCHED) { collectSync(publication.runtime) }
                    awaitCancellation()
                }
            }
    }

    /** A finite key-consumer failure leaves its durable receipt for the next admitted entry. */
    private suspend fun collectKeys(runtime: CollaborationSpaceRuntimeReady) {
        try {
            retainKeys(runtime).collect { outcome ->
                if (outcome is AppResult.Failed)
                    Diagnostics.record(
                        Diagnostic(
                            DiagnosticArea.Application,
                            DiagnosticKind.OperationFailed,
                            "ChatIncomingKeys_${outcome.failure.kind.name}",
                        )
                    )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Diagnostics.record(
                Diagnostic(
                    DiagnosticArea.Application,
                    DiagnosticKind.OperationFailed,
                    "ChatIncomingKeysStopped",
                )
            )
        }
    }

    /** Independent sync batches continue; duplicate failure diagnostics do not flood the log. */
    private suspend fun collectSync(runtime: CollaborationSpaceRuntimeReady) {
        var previousFailure: FailureKind? = null
        try {
            retainSync(runtime).collect { outcome ->
                val failure = (outcome as? AppResult.Failed)?.failure?.kind
                if (failure != null && failure != previousFailure)
                    Diagnostics.record(
                        Diagnostic(
                            DiagnosticArea.Application,
                            DiagnosticKind.OperationFailed,
                            "ChatIncomingSync_${failure.name}",
                        )
                    )
                previousFailure = failure
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Diagnostics.record(
                Diagnostic(
                    DiagnosticArea.Application,
                    DiagnosticKind.OperationFailed,
                    "ChatIncomingSyncStopped",
                )
            )
        }
    }
}
