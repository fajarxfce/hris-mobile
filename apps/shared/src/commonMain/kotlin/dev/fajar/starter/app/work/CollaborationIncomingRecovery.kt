package dev.fajar.starter.app.work

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.localListAccess
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.sync.domain.*
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex

/** One admitted incoming room per turn, independent of pending outgoing commands. */
class CollaborationIncomingRecovery(
    private val setup: LoadCollaborationSetup,
    private val refresh: RefreshCollaborationList,
    private val readProgress: ReadCollaborationRoomRecovery,
    private val updateProgress: UpdateCollaborationRoomRecovery,
    private val readRoom: ReadCollaborationConversations,
    private val acquireRuntime: AcquireCollaborationBackgroundRuntime,
    private val observeAccess: ObserveCollaborationAccess,
    private val enter: EnterCollaborationConversation,
    private val recoverHistory: CollaborationIncomingHistoryRecovery,
    private val clock: Clock = Clock.System,
) {
    private val execution = Mutex()

    suspend operator fun invoke(admission: CollaborationBackgroundAdmission): SyncResult {
        currentCoroutineContext().ensureActive()
        if (!execution.tryLock()) return SyncResult.Retry(failure("busy", FailureKind.Unavailable))
        try {
            val budget =
                minOf(
                    15.seconds,
                    admission.expiresAt - clock.now(),
                    -admission.monotonicDeadline.elapsedNow(),
                )
            if (budget <= Duration.ZERO) return SyncResult.Retry(failure("timeout"))
            return withTimeoutOrNull(budget) { process(admission) }
                ?: SyncResult.Retry(failure("timeout"))
        } finally {
            execution.unlock()
        }
    }

    private suspend fun process(admission: CollaborationBackgroundAdmission): SyncResult {
        val scope = CollaborationOperationScope.Background(admission)
        val configured = setup(scope)
        currentCoroutineContext().ensureActive()
        if (configured is AppResult.Failed) return collaborationWorkFailure(configured.failure)
        val current = (configured as AppResult.Success).value
        val device = current.device
        if (
            current.owner != admission.owner ||
                device == null ||
                device.id != admission.observation.deviceId ||
                device.version < admission.observation.deviceVersion ||
                device.state != CollaborationDeviceState.ACTIVE
        )
            return SyncResult.Blocked(failure("setup_required", FailureKind.Unavailable))
        val access = admission.owner.localListAccess()
        val found = readProgress(access)
        if (found is AppResult.Failed) return collaborationWorkFailure(found.failure)
        var snapshot = (found as AppResult.Success).value
        val received = refresh(scope, snapshot.progress?.position?.afterConversationId, limit = 1)
        currentCoroutineContext().ensureActive()
        if (received is AppResult.Failed) return collaborationWorkFailure(received.failure)
        val page = (received as AppResult.Success).value
        if (page.owner != admission.owner)
            return SyncResult.Blocked(failure("owner_changed", FailureKind.Unauthorized))
        for (summary in page.value.items) {
            val id = summary.conversation.id
            val attempted = updateProgress(snapshot, CollaborationRoomRecoveryChange.Attempt(id))
            if (attempted is AppResult.Failed) return collaborationWorkFailure(attempted.failure)
            snapshot = (attempted as AppResult.Success).value.snapshot
            val result =
                withTimeoutOrNull(12.seconds) { room(admission, device.id, id) }
                    ?: SyncResult.Retry(failure("room_timeout"))
            currentCoroutineContext().ensureActive()
            val completed =
                updateProgress(
                    snapshot,
                    CollaborationRoomRecoveryChange.Complete(
                        id,
                        result is SyncResult.Retry && result.failure != null,
                        result is SyncResult.Retry && result.failure == null,
                    ),
                )
            if (completed is AppResult.Failed) return collaborationWorkFailure(completed.failure)
            snapshot = (completed as AppResult.Success).value.snapshot
        }
        if (page.value.nextCursor != null) return SyncResult.Retry()
        val finished = updateProgress(snapshot, CollaborationRoomRecoveryChange.Finish)
        if (finished is AppResult.Failed) return collaborationWorkFailure(finished.failure)
        return when ((finished as AppResult.Success).value.completion) {
            CollaborationRoomRecoveryCompletion.Covered -> SyncResult.Complete
            CollaborationRoomRecoveryCompletion.Continue -> SyncResult.Retry()
            else -> SyncResult.Retry(failure("sweep_pending", FailureKind.Unavailable))
        }
    }

    private suspend fun room(
        admission: CollaborationBackgroundAdmission,
        deviceId: String,
        conversationId: String,
    ): SyncResult {
        val scope = CollaborationOperationScope.Background(admission)
        val metadata = readRoom.room(scope, conversationId)
        currentCoroutineContext().ensureActive()
        if (metadata is AppResult.Failed) return collaborationWorkFailure(metadata.failure)
        val binding = (metadata as AppResult.Success).value.value
        when (binding.state) {
            CollaborationDirectRoomState.NOT_REQUESTED -> return SyncResult.Complete
            CollaborationDirectRoomState.QUEUED,
            CollaborationDirectRoomState.RUNNING ->
                return SyncResult.Retry(failure("room_pending", FailureKind.Unavailable))
            CollaborationDirectRoomState.FAILED,
            CollaborationDirectRoomState.CANCELLED ->
                return SyncResult.Blocked(failure("room_review_required", FailureKind.Unavailable))
            CollaborationDirectRoomState.READY -> Unit
        }
        var lease: CollaborationBackgroundRuntimeLease? = null
        try {
            val borrowed = acquireRuntime(admission)
            if (borrowed is AppResult.Success) lease = borrowed.value
            currentCoroutineContext().ensureActive()
            if (borrowed is AppResult.Failed) return collaborationWorkFailure(borrowed.failure)
            val access = admission.owner.localListAccess()
            return monitorCollaborationBackgroundDelivery(
                requireNotNull(lease),
                observeAccess(CollaborationLocalScope.Captured(access)),
                access,
            ) {
                originals(scope, deviceId, conversationId, binding.matrixRoomId)
            }
        } finally {
            withContext(NonCancellable) { lease?.close() }
        }
    }

    private suspend fun originals(
        scope: CollaborationOperationScope.Background,
        deviceId: String,
        conversationId: String,
        roomId: String?,
    ): SyncResult {
        var child: CollaborationTimelineLease? = null
        try {
            val opened = enter(scope, deviceId, conversationId)
            if (opened is AppResult.Success) child = opened.value
            currentCoroutineContext().ensureActive()
            if (opened is AppResult.Failed) return collaborationWorkFailure(opened.failure)
            if (roomId == null) return SyncResult.Retry(failure("room_pending"))
            return recoverHistory(
                scope,
                CollaborationHistoryTraversal(
                    conversationId,
                    roomId,
                    deviceId,
                    CollaborationHistoryPurpose.Incoming,
                ),
            )
        } finally {
            withContext(NonCancellable) { child?.close() }
        }
    }

    private fun failure(suffix: String, kind: FailureKind = FailureKind.Timeout) =
        Failure(kind, "Chat recovery is pending.", code = "client.collaboration_incoming_$suffix")
}
