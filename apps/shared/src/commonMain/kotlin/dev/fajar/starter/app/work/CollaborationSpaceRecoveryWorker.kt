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

/** One bounded recovery processor; each turn attempts at most four original commands or rooms. */
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
    private val pendingMessages:
        suspend (CollaborationSpaceOwner) -> AppResult<List<CollaborationSpaceMessageRecord>>,
    private val message: LoadCollaborationSpaceRecoveryMessage,
    private val prepareRoom:
        suspend (CollaborationSpaceConversation) -> AppResult<CollaborationSpaceRoomBinding>,
    private val stageMessage:
        suspend (
            CollaborationSpaceRuntimeReady, CollaborationSpaceConversation, String,
        ) -> AppResult<CollaborationSpaceMessageRecord>,
    private val demand:
        suspend (CollaborationSpaceRuntimeReady, CollaborationSpaceConversation) -> AppResult<
                Boolean
            >,
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
        return sweep(runtime, snapshot)
    }

    /** Old claimed rooms resume first; fresh command work then precedes the remaining room scan. */
    private suspend fun sweep(
        runtime: CollaborationSpaceRuntimeReady,
        initial: CollaborationSpaceRecoverySnapshot,
    ): SyncResult {
        var snapshot = initial
        repeat(4) {
            currentCoroutineContext().ensureActive()
            val progress = requireNotNull(snapshot.progress?.sweep)
            var messageTurn = progress.pending == null && progress.messages?.reachedEnd != true
            if (messageTurn && progress.messages?.pending == null) {
                val local = pendingMessages(runtime.owner)
                if (local is AppResult.Failed) return failed(local)
                val claimed =
                    updateProgress(
                        snapshot,
                        CollaborationSpaceRecoveryChange.AttemptMessages(
                            (local as AppResult.Success).value
                        ),
                    )
                if (claimed is AppResult.Failed) return failed(claimed)
                snapshot = (claimed as AppResult.Success).value.snapshot
                if (snapshot.progress?.sweep?.messages?.reachedEnd == true) messageTurn = false
            }
            if (!messageTurn && snapshot.progress?.sweep?.reachedEnd == true)
                return finish(snapshot)
            if (!messageTurn && snapshot.progress?.sweep?.pending == null) {
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
                withTimeoutOrNull(50.seconds) {
                    if (messageTurn) recoverMessage(runtime, snapshot)
                    else recoverRoom(runtime, snapshot)
                } ?: AppResult.Failed(pending("room_timeout", FailureKind.Timeout))
            currentCoroutineContext().ensureActive()
            val change =
                if (messageTurn)
                    CollaborationSpaceRecoveryChange.CompleteMessage(result is AppResult.Failed)
                else CollaborationSpaceRecoveryChange.Complete(result is AppResult.Failed)
            val completed = updateProgress(snapshot, change)
            if (completed is AppResult.Failed) return failed(completed)
            snapshot = (completed as AppResult.Success).value.snapshot
            // Retain failures and advance the original cursor before scheduler backoff.
            if (result is AppResult.Failed) return failed(result)
        }
        val progress = requireNotNull(snapshot.progress?.sweep)
        return if (progress.reachedEnd && progress.messages?.reachedEnd == true) finish(snapshot)
        else continuePending()
    }

    private suspend fun recoverMessage(
        runtime: CollaborationSpaceRuntimeReady,
        snapshot: CollaborationSpaceRecoverySnapshot,
    ): AppResult<Unit> {
        val found = message(snapshot)
        if (found is AppResult.Failed) return found
        val claimed = (found as AppResult.Success).value ?: return AppResult.Success(Unit)
        val prepared = prepareRoom(claimed.conversation)
        if (prepared is AppResult.Failed) return prepared
        when (claimed.record.state) {
            CollaborationMessageState.Queued,
            is CollaborationMessageState.Staging -> {
                val staged = stageMessage(runtime, claimed.conversation, claimed.record.intent.id)
                if (staged is AppResult.Failed) return staged
                if (
                    (staged as AppResult.Success).value.state == CollaborationMessageState.Cancelled
                )
                    return AppResult.Success(Unit)
            }
            else -> Unit
        }
        val sent = deliver(runtime, claimed.conversation)
        if (sent is AppResult.Failed) return sent
        val report = (sent as AppResult.Success).value
        return if (
            claimed.record.intent.id in report.confirmed &&
                claimed.record.intent.id !in report.unresolved
        )
            AppResult.Success(Unit)
        else AppResult.Failed(pending("messages_pending"))
    }

    private suspend fun recoverRoom(
        runtime: CollaborationSpaceRuntimeReady,
        snapshot: CollaborationSpaceRecoverySnapshot,
    ): AppResult<Unit> {
        val found = room(snapshot)
        if (found is AppResult.Failed) return found
        val conversation = (found as AppResult.Success).value ?: return AppResult.Success(Unit)
        val needed = demand(runtime, conversation)
        if (needed is AppResult.Failed) return needed
        if (!(needed as AppResult.Success).value) return AppResult.Success(Unit)
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
