package dev.fajar.starter.app.work

import dev.fajar.starter.app.bootstrap.ApplicationCollaborationState
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.sync.domain.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock

/** One original group sweep processor, one bounded waiter, and four raw pages per turn. */
class CollaborationSpaceRecoveryWorker(
    private val states: Lazy<StateFlow<ApplicationCollaborationState>>,
    private val ensureWake: EnsureCollaborationSpaceRecoveryWake,
    private val readWake: ReadCollaborationSpaceRecoveryWake,
    private val completeWake: CompleteCollaborationSpaceRecoveryWake,
    private val resume: Lazy<ResumeCollaborationSpaceRecovery>,
    private val device:
        suspend (CollaborationNativeParent) -> AppResult<CollaborationSpaceRecoveryDevice?>,
    private val adopt:
        suspend (CollaborationSpaceRecoveryDevice) -> AppResult<CollaborationSpaceRuntimeReady>,
    private val sync: suspend (CollaborationSpaceRuntimeReady) -> AppResult<Unit>,
    private val readProgress: ReadCollaborationSpaceRecoveryProgress,
    private val updateProgress: UpdateCollaborationSpaceRecoveryProgress,
    private val page: LoadCollaborationSpaceRecoveryPage,
    private val room: LoadCollaborationSpaceRecoveryRoom,
    private val finishSweep: FinishCollaborationSpaceRecovery,
    private val deliver:
        suspend (CollaborationSpaceRuntimeReady, CollaborationSpaceConversation) -> AppResult<
                CollaborationDeliveryReport
            >,
    private val build: AppBuild,
) : SyncTask {
    override val key = CollaborationSpaceRecoveryWake.WORK_KEY
    private val admission = Semaphore(2)
    private val processing = Mutex()

    override suspend fun invoke(): SyncResult = invoke(SyncTrigger.Requested)

    override suspend fun invoke(trigger: SyncTrigger): SyncResult {
        currentCoroutineContext().ensureActive()
        if (build.platform !in setOf(AppPlatform.Android, AppPlatform.Ios))
            return SyncResult.Complete
        if (!admission.tryAcquire()) return SyncResult.Retry(pending("busy"))
        try {
            return withTimeoutOrNull(180.seconds) {
                processing.withLock {
                    withTimeoutOrNull(90.seconds) { currentParent(trigger) }
                        ?: SyncResult.Retry(pending("timeout", FailureKind.Timeout))
                }
            } ?: SyncResult.Retry(pending("timeout", FailureKind.Timeout))
        } finally {
            admission.release()
        }
    }

    private suspend fun currentParent(trigger: SyncTrigger): SyncResult {
        val published = states.value
        val current =
            withTimeoutOrNull(5.seconds) {
                published.first { it !is ApplicationCollaborationState.Pending }
            } ?: return SyncResult.Retry(pending("parent_pending"))
        val parent =
            current.recoveryParent()
                ?: return SyncResult.Blocked(collaborationRecoveryParentChanged())
        return withCollaborationRecoveryParent(published, parent) { process(parent, trigger) }
    }

    private suspend fun process(
        parent: CollaborationNativeParent,
        trigger: SyncTrigger,
    ): SyncResult {
        val requested = if (trigger == SyncTrigger.Scheduled) ensureWake() else readWake()
        if (requested is AppResult.Failed) return failed(requested)
        val wake = (requested as AppResult.Success).value ?: return SyncResult.Complete
        val local = device(parent)
        if (local is AppResult.Failed) return failed(local)
        val original = (local as AppResult.Success).value ?: return acknowledge(wake)
        val adopted = adopt(original)
        if (adopted is AppResult.Failed) return failed(adopted)
        val runtime = (adopted as AppResult.Success).value
        val synced = sync(runtime)
        if (synced is AppResult.Failed) return failed(synced)
        val read = readProgress(original.owner)
        if (read is AppResult.Failed) return failed(read)
        var snapshot = (read as AppResult.Success).value
        if (snapshot.progress?.sweep == null) {
            val begun = updateProgress(snapshot, CollaborationSpaceRecoveryChange.Begin(wake))
            if (begun is AppResult.Failed) return failed(begun)
            snapshot = (begun as AppResult.Success).value.snapshot
        }
        repeat(4) {
            currentCoroutineContext().ensureActive()
            if (snapshot.progress?.sweep?.reachedEnd == true) return finish(snapshot)
            if (snapshot.progress?.sweep?.pending == null) {
                val next = page(snapshot)
                if (next is AppResult.Failed) return failed(next)
                val claimed =
                    updateProgress(
                        snapshot,
                        CollaborationSpaceRecoveryChange.Attempt((next as AppResult.Success).value),
                    )
                if (claimed is AppResult.Failed) return failed(claimed)
                snapshot = (claimed as AppResult.Success).value.snapshot
            }
            val result =
                withTimeoutOrNull(50.seconds) { recoverRoom(runtime, snapshot) }
                    ?: AppResult.Failed(pending("room_timeout", FailureKind.Timeout))
            currentCoroutineContext().ensureActive()
            val completed =
                updateProgress(
                    snapshot,
                    CollaborationSpaceRecoveryChange.Complete(result is AppResult.Failed),
                )
            if (completed is AppResult.Failed) return failed(completed)
            snapshot = (completed as AppResult.Success).value.snapshot
            // Faults retain the wake and use the scheduler's backoff. They are not immediate
            // healthy continuations, even when the native queue returned an ordinary report.
            if (result is AppResult.Failed) return failed(result)
        }
        return if (snapshot.progress?.sweep?.reachedEnd == true) finish(snapshot)
        else continuePending()
    }

    private suspend fun recoverRoom(
        runtime: CollaborationSpaceRuntimeReady,
        snapshot: CollaborationSpaceRecoverySnapshot,
    ): AppResult<Unit> {
        val found = room(snapshot)
        if (found is AppResult.Failed) return found
        val conversation = (found as AppResult.Success).value ?: return AppResult.Success(Unit)
        val result = deliver(runtime, conversation)
        if (result is AppResult.Failed) return result
        return if ((result as AppResult.Success).value.unresolved.isEmpty()) AppResult.Success(Unit)
        else AppResult.Failed(pending("messages_pending"))
    }

    private suspend fun finish(snapshot: CollaborationSpaceRecoverySnapshot): SyncResult {
        val result = finishSweep(snapshot)
        if (result is AppResult.Failed) return failed(result)
        val updated = (result as AppResult.Success).value
        val wake = updated.completedWake
        if (wake != null) return acknowledge(wake)
        return if (snapshot.progress?.sweep?.retryRequired == true)
            SyncResult.Retry(pending("sweep_pending"))
        else continuePending()
    }

    private suspend fun acknowledge(wake: CollaborationSpaceRecoveryWake): SyncResult {
        val result = completeWake(wake)
        if (result is AppResult.Failed) return failed(result)
        val remaining = readWake()
        if (remaining is AppResult.Failed) return failed(remaining)
        return if ((remaining as AppResult.Success).value == null) SyncResult.Complete
        else continuePending()
    }

    private suspend fun continuePending(): SyncResult {
        val scheduled = resume.value()
        if (scheduled is AppResult.Failed) return failed(scheduled)
        return SyncResult.Complete
    }

    private fun failed(result: AppResult.Failed): SyncResult =
        when (result.failure.kind) {
            FailureKind.Unauthorized,
            FailureKind.AccessDenied,
            FailureKind.Validation,
            FailureKind.Conflict -> SyncResult.Blocked(result.failure)
            else -> SyncResult.Retry(result.failure)
        }

    private fun pending(suffix: String, kind: FailureKind = FailureKind.Unavailable) =
        Failure(
            kind,
            "Chat recovery is pending.",
            code = "client.collaboration_space_recovery_$suffix",
            retryAfterSeconds = 30,
        )
}
