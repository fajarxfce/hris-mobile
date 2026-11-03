package dev.fajar.starter.app.work

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.localListAccess
import dev.fajar.starter.collaboration.domain.policies.validCollaborationPushHint
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
    private val deliverReadReceipts: DeliverCollaborationReadReceipts,
    private val readIdentity: ReadCollaborationIdentity,
    private val clock: Clock = Clock.System,
) {
    private val execution = Mutex()

    suspend operator fun invoke(admission: CollaborationBackgroundAdmission): SyncResult =
        bounded(admission) { process(admission) }

    suspend fun priority(
        admission: CollaborationBackgroundAdmission,
        hint: CollaborationPushHint,
    ): SyncResult {
        currentCoroutineContext().ensureActive()
        if (
            !validCollaborationPushHint(hint) ||
                hint.roomId == null ||
                hint.matrixEventId == null ||
                hint.companyId != admission.owner.companyId ||
                hint.deviceId != admission.observation.deviceId
        )
            return SyncResult.Blocked(failure("invalid_hint", FailureKind.Validation))
        return bounded(admission) {
            val scope = CollaborationOperationScope.Background(admission)
            val configured = setup(scope)
            currentCoroutineContext().ensureActive()
            if (configured is AppResult.Failed)
                return@bounded collaborationWorkFailure(configured.failure)
            val current = (configured as AppResult.Success).value
            val device = current.device
            if (
                current.owner != admission.owner ||
                    device?.id != hint.deviceId ||
                    device.state != CollaborationDeviceState.ACTIVE
            )
                return@bounded SyncResult.Blocked(
                    failure("setup_required", FailureKind.Unavailable)
                )
            val found = refresh.byRoom(scope, requireNotNull(hint.roomId))
            currentCoroutineContext().ensureActive()
            if (found is AppResult.Failed) return@bounded collaborationWorkFailure(found.failure)
            val summary = (found as AppResult.Success).value
            if (summary.owner != admission.owner)
                return@bounded SyncResult.Blocked(
                    failure("owner_changed", FailureKind.Unauthorized)
                )
            withTimeoutOrNull(12.seconds) {
                room(admission, hint.deviceId, summary.value.conversation.id, hint)
            } ?: SyncResult.Retry(failure("room_timeout"))
        }
    }

    private suspend fun bounded(
        admission: CollaborationBackgroundAdmission,
        operation: suspend () -> SyncResult,
    ): SyncResult {
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
            return withTimeoutOrNull(budget) { operation() } ?: SyncResult.Retry(failure("timeout"))
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
        hint: CollaborationPushHint? = null,
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
        if (hint != null && binding.matrixRoomId != hint.roomId)
            return SyncResult.Blocked(failure("room_mismatch", FailureKind.AccessDenied))
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
                originals(
                    scope,
                    deviceId,
                    conversationId,
                    binding.matrixRoomId,
                    hint?.matrixEventId,
                )
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
        priorityEventId: String?,
    ): SyncResult {
        var child: CollaborationTimelineLease? = null
        try {
            val identity = identity(scope, deviceId)
            if (identity is SyncResult.Blocked) return identity
            val opened = enter(scope, deviceId, conversationId)
            if (opened is AppResult.Success) child = opened.value
            currentCoroutineContext().ensureActive()
            if (opened is AppResult.Failed) return collaborationWorkFailure(opened.failure)
            if (roomId == null) return SyncResult.Retry(failure("room_pending"))
            val key =
                CollaborationHistoryTraversal(
                    conversationId,
                    roomId,
                    deviceId,
                    CollaborationHistoryPurpose.Incoming,
                )
            val receipts = receipts(scope, deviceId, conversationId, roomId)
            if (receipts is SyncResult.Blocked) return receipts
            // A failed metadata request still leaves an incoming turn within the room budget.
            val recovered =
                if (priorityEventId == null) recoverHistory(scope, key)
                else recoverHistory.priority(scope, key, priorityEventId)
            return combineCollaborationRecovery(
                identity,
                combineCollaborationRecovery(receipts, recovered),
            )
        } finally {
            withContext(NonCancellable) { child?.close() }
        }
    }

    private suspend fun identity(
        scope: CollaborationOperationScope.Background,
        deviceId: String,
    ): SyncResult {
        val result =
            withTimeoutOrNull(2.seconds) { readIdentity.acquire(scope, deviceId) }
                ?: return SyncResult.Retry(failure("identity_timeout"))
        currentCoroutineContext().ensureActive()
        return when (result) {
            is AppResult.Failed ->
                if (result.failure.kind == FailureKind.Unavailable) SyncResult.Retry(result.failure)
                else collaborationWorkFailure(result.failure)
            is AppResult.Success ->
                if (result.value.value == null)
                    SyncResult.Retry(failure("identity_pending", FailureKind.Unavailable))
                else SyncResult.Complete
        }
    }

    private suspend fun receipts(
        scope: CollaborationOperationScope.Background,
        deviceId: String,
        conversationId: String,
        roomId: String,
    ): SyncResult {
        val delivered =
            withTimeoutOrNull(3.seconds) {
                deliverReadReceipts(scope, deviceId, conversationId, roomId, limit = 2)
            } ?: return SyncResult.Retry(failure("read_receipt_timeout"))
        currentCoroutineContext().ensureActive()
        return when (delivered) {
            is AppResult.Failed -> collaborationWorkFailure(delivered.failure)
            is AppResult.Success ->
                if (delivered.value.remaining == 0) SyncResult.Complete else SyncResult.Retry()
        }
    }

    private fun failure(suffix: String, kind: FailureKind = FailureKind.Timeout) =
        Failure(kind, "Chat recovery is pending.", code = "client.collaboration_incoming_$suffix")
}
