package dev.fajar.starter.app.work

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.localListAccess
import dev.fajar.starter.collaboration.domain.repositories.CollaborationBackgroundRuntimeLease
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.sync.domain.*
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex

/** A bounded platform entry point. The encrypted command store, not this worker, owns intent. */
class CollaborationDeliveryWorker(
    private val recoverDrafts: CollaborationDraftRecovery,
    private val discover: ReadCollaborationDeliveryWork,
    private val advance: AdvanceCollaborationDelivery,
    private val observeAccess: ObserveCollaborationAccess,
    private val loadSetup: LoadCollaborationSetup,
    private val ensureRuntime: EnsureCollaborationRuntime,
    private val control: ReadCollaborationControl,
    private val read: ReadCollaborationConversations,
    private val prepare: PrepareCollaborationConversation,
    private val replay: ReplayCollaborationConversation,
    private val reconcileRoom: ReconcileCollaborationConversation,
    private val enter: EnterCollaborationConversation,
    private val messages: ObserveCollaborationMessages,
    private val reconcile: ReconcileCollaborationMessages,
    private val stage: StageCollaborationText,
    private val deliver: DeliverCollaborationMessages,
    private val acquire: AcquireCollaborationHistory,
    private val reopenHistory: ReopenCollaborationHistory,
    private val recover: RecoverCollaborationMessageReceipts,
    private val retire: RetireCollaborationMessages,
    private val acquireBackground: AcquireCollaborationBackgroundRuntime,
    private val clock: Clock = Clock.System,
) : SyncTask {
    override val key = QueueCollaborationText.DELIVERY_KEY
    private val execution = Mutex()

    override suspend fun invoke(): SyncResult = bounded(60.seconds) { process() }

    /** Ephemeral admitted work; platform job input still contains only a durable task key. */
    suspend operator fun invoke(admission: CollaborationBackgroundAdmission): SyncResult =
        bounded(
            minOf(
                60.seconds,
                admission.expiresAt - clock.now(),
                -admission.monotonicDeadline.elapsedNow(),
            )
        ) {
            processBackground(admission)
        }

    private suspend fun bounded(budget: Duration, action: suspend () -> SyncResult): SyncResult {
        currentCoroutineContext().ensureActive()
        if (!execution.tryLock()) return SyncResult.Retry(busy())
        try {
            if (budget <= Duration.ZERO) return SyncResult.Retry(timeout())
            return withTimeoutOrNull(budget) { action() } ?: SyncResult.Retry(timeout())
        } finally {
            execution.unlock()
        }
    }

    private suspend fun processBackground(admission: CollaborationBackgroundAdmission): SyncResult {
        val scope = CollaborationOperationScope.Background(admission)
        // Fresh setup can readmit an old company gate before any local discovery or SDK borrowing.
        val setup = loadSetup(scope)
        currentCoroutineContext().ensureActive()
        if (setup is AppResult.Failed) return collaborationWorkFailure(setup.failure)
        val configured = (setup as AppResult.Success).value
        if (configured.owner != admission.owner) return SyncResult.Blocked(changed())
        val device = configured.device ?: return SyncResult.Blocked(setupRequired())
        val local = CollaborationLocalScope.Captured(configured.owner.localListAccess())
        val draftResult = recoverDrafts(local)
        currentCoroutineContext().ensureActive()
        val found = discover(local)
        currentCoroutineContext().ensureActive()
        if (found is AppResult.Failed) return collaborationWorkFailure(found.failure)
        val work = (found as AppResult.Success).value ?: return draftResult
        if (work.access != local.access) return SyncResult.Blocked(changed())
        var lease: CollaborationBackgroundRuntimeLease? = null
        try {
            val acquired = acquireBackground(admission)
            if (acquired is AppResult.Success) lease = acquired.value
            currentCoroutineContext().ensureActive()
            if (acquired is AppResult.Failed) return collaborationWorkFailure(acquired.failure)
            val borrowed = lease ?: return SyncResult.Blocked(setupRequired())
            return monitorCollaborationBackgroundDelivery(
                borrowed,
                observeAccess(local),
                work.access,
            ) {
                processPrepared(work, scope, device, draftResult)
            }
        } finally {
            withContext(NonCancellable) { lease?.close() }
        }
    }

    private suspend fun process(): SyncResult {
        val draftResult = recoverDrafts()
        currentCoroutineContext().ensureActive()
        val found = discover()
        currentCoroutineContext().ensureActive()
        if (found is AppResult.Failed) return syncFailure(found.failure)
        val work = (found as AppResult.Success).value ?: return draftResult
        val selection =
            work.scope as? CollaborationLocalScope.Selected ?: return SyncResult.Blocked(changed())
        val scope = CollaborationOperationScope.Selected(selection.workspaceId)
        val setup = loadSetup(scope)
        currentCoroutineContext().ensureActive()
        if (setup is AppResult.Failed) return syncFailure(setup.failure)
        val configured = (setup as AppResult.Success).value
        if (configured.owner.localListAccess() != work.access) return SyncResult.Blocked(changed())
        val device = configured.device ?: return SyncResult.Blocked(setupRequired())
        if (device.state != CollaborationDeviceState.ACTIVE)
            return SyncResult.Blocked(setupRequired())
        val restored = ensureRuntime(scope.workspaceId, device.id)
        currentCoroutineContext().ensureActive()
        if (restored is AppResult.Failed) return collaborationWorkFailure(restored.failure)
        if (!(restored as AppResult.Success).value.value) return SyncResult.Blocked(setupRequired())
        return processPrepared(work, scope, device, draftResult)
    }

    private suspend fun processPrepared(
        work: CollaborationDeliveryWork,
        scope: CollaborationOperationScope,
        device: CollaborationDeviceSetup,
        draftResult: SyncResult,
    ): SyncResult {
        var cursor = work.cursor
        var retry: Failure? = null
        var blocked: Failure? = null
        val blockedRooms = mutableSetOf<String>()
        for (conversationId in work.conversationIds) {
            val admitted = guard(work)
            if (admitted is AppResult.Failed) return syncFailure(admitted.failure)
            val advanced = advance(work.copy(cursor = cursor), conversationId)
            currentCoroutineContext().ensureActive()
            if (advanced is AppResult.Failed) return SyncResult.Retry(advanced.failure)
            cursor = (advanced as AppResult.Success).value
            val result =
                withTimeoutOrNull(12.seconds) { processRoom(work, scope, device, conversationId) }
                    ?: AppResult.Failed(timeout())
            currentCoroutineContext().ensureActive()
            if (result is AppResult.Failed) {
                when (val classified = collaborationWorkFailure(result.failure)) {
                    is SyncResult.Retry -> retry = retry ?: classified.failure
                    is SyncResult.Blocked -> {
                        blocked = blocked ?: classified.failure
                        blockedRooms += conversationId
                    }
                    SyncResult.Complete -> Unit
                }
            }
        }
        guard(work).let { if (it is AppResult.Failed) return syncFailure(it.failure) }
        val remaining = discover(work.scope)
        currentCoroutineContext().ensureActive()
        if (remaining is AppResult.Failed) return syncFailure(remaining.failure)
        val pending = (remaining as AppResult.Success).value ?: return draftResult
        if (retry != null) return SyncResult.Retry(retry)
        // A blocked room must not stop bounded batches or newly queued independent work.
        if (pending.conversationIds.any { it !in blockedRooms }) return SyncResult.Retry()
        return SyncResult.Blocked(requireNotNull(blocked))
    }

    private suspend fun processRoom(
        work: CollaborationDeliveryWork,
        scope: CollaborationOperationScope,
        device: CollaborationDeviceSetup,
        conversationId: String,
    ): AppResult<Unit> = traceCollaborationDelivery { stage ->
        processRoomSteps(work, scope, device, conversationId, stage)
    }

    private suspend fun processRoomSteps(
        work: CollaborationDeliveryWork,
        scope: CollaborationOperationScope,
        device: CollaborationDeviceSetup,
        conversationId: String,
        stage: (CollaborationDeliveryStage) -> Unit,
    ): AppResult<Unit> {
        val projected = reconcile(work.scope, conversationId)
        currentCoroutineContext().ensureActive()
        if (projected is AppResult.Failed) return projected
        stage(CollaborationDeliveryStage.ReadPending)
        val snapshot = messages(work.scope, conversationId).first()
        currentCoroutineContext().ensureActive()
        if (snapshot is AppResult.Failed) return snapshot
        val records = (snapshot as AppResult.Success).value
        if (records.isEmpty()) return AppResult.Success(Unit)
        val pending = records.filter { it.state !is CollaborationMessageState.Sent }
        stage(CollaborationDeliveryStage.PrepareRoom)
        val room = readyRoom(work, scope, device, conversationId, pending.isNotEmpty())
        currentCoroutineContext().ensureActive()
        if (room is AppResult.Failed) return room
        if (!(room as AppResult.Success).value) return AppResult.Failed(waitingRoom())
        var child: dev.fajar.starter.collaboration.domain.repositories.CollaborationTimelineLease? =
            null
        try {
            stage(CollaborationDeliveryStage.EnterTimeline)
            val acquired = enter(scope, device.id, conversationId)
            if (acquired is AppResult.Success) child = acquired.value
            currentCoroutineContext().ensureActive()
            guard(work).let { if (it is AppResult.Failed) return it }
            if (acquired is AppResult.Failed) return acquired
            if (records.any { it.state != CollaborationMessageState.Queued }) {
                // Resolve retained uncertainty from original evidence before resuming an old ID.
                stage(CollaborationDeliveryStage.RecoverOriginal)
                val recovered = acquireOriginals(work, scope, device.id, conversationId)
                currentCoroutineContext().ensureActive()
                if (recovered is AppResult.Failed) return recovered
                if ((recovered as AppResult.Success).value) {
                    withContext(NonCancellable) { child?.close() }
                    child = null
                    val reopened = enter(scope, device.id, conversationId)
                    if (reopened is AppResult.Success) child = reopened.value
                    currentCoroutineContext().ensureActive()
                    guard(work).let { if (it is AppResult.Failed) return it }
                    if (reopened is AppResult.Failed) return reopened
                }
            }
            stage(CollaborationDeliveryStage.ReadPrepared)
            val current = messages(work.scope, conversationId).first()
            currentCoroutineContext().ensureActive()
            if (current is AppResult.Failed) return current
            val selected =
                (current as AppResult.Success)
                    .value
                    .filter { it.state !is CollaborationMessageState.Sent }
                    .sortedWith(
                        compareBy<CollaborationMessageRecord> { it.intent.createdAt }
                            .thenBy { it.intent.id }
                    )
                    .take(8)
            stage(CollaborationDeliveryStage.Flush)
            val flushed = flush(work, scope, device.id, conversationId, selected, stage)
            if (flushed is AppResult.Failed) return flushed
            stage(CollaborationDeliveryStage.Retire)
            return acquireAndRetire(work, scope, device.id, conversationId)
        } finally {
            withContext(NonCancellable) { child?.close() }
        }
    }

    private suspend fun flush(
        work: CollaborationDeliveryWork,
        scope: CollaborationOperationScope,
        deviceId: String,
        conversationId: String,
        selected: List<CollaborationMessageRecord>,
        reportStage: (CollaborationDeliveryStage) -> Unit,
    ): AppResult<Unit> {
        for (record in selected) {
            reportStage(CollaborationDeliveryStage.StageMessage)
            val result = stage(scope, deviceId, record.intent.id)
            currentCoroutineContext().ensureActive()
            if (result is AppResult.Failed) return result
        }
        if (selected.isNotEmpty()) {
            reportStage(CollaborationDeliveryStage.DeliverMessages)
            val sent = deliver(scope, deviceId, conversationId)
            currentCoroutineContext().ensureActive()
            if (sent is AppResult.Failed) return sent
            // Unselected durable intents are expected to remain for another bounded pass.
            if (
                (sent as AppResult.Success).value.unresolved.any { id ->
                    selected.any { it.intent.id == id }
                }
            )
                return AppResult.Failed(
                    Failure(
                        FailureKind.Unavailable,
                        "Messages are waiting to send.",
                        code = "client.collaboration_delivery_pending",
                    )
                )
        }
        return AppResult.Success(Unit)
    }

    private suspend fun readyRoom(
        work: CollaborationDeliveryWork,
        scope: CollaborationOperationScope,
        device: CollaborationDeviceSetup,
        conversationId: String,
        hasPending: Boolean,
    ): AppResult<Boolean> {
        val retained = control.room(work.scope, conversationId)
        currentCoroutineContext().ensureActive()
        if (retained is AppResult.Failed) return retained
        var command = (retained as AppResult.Success).value.value
        val loaded = read.room(scope, conversationId)
        currentCoroutineContext().ensureActive()
        if (loaded is AppResult.Failed) return loaded
        val room = (loaded as AppResult.Success).value.value
        if (command == null && room.state == CollaborationDirectRoomState.READY)
            return AppResult.Success(true)
        if (
            command == null &&
                hasPending &&
                room.state == CollaborationDirectRoomState.NOT_REQUESTED
        ) {
            val prepared =
                prepare(
                    scope,
                    CollaborationConversationAction.RequestRoom(
                        conversationId,
                        device.id,
                        device.version,
                        null,
                    ),
                )
            currentCoroutineContext().ensureActive()
            if (prepared is AppResult.Failed) return prepared
            command = (prepared as AppResult.Success).value
        }
        if (command != null) {
            if (command.outcome !is CollaborationConversationOutcome.Confirmed) {
                val result = replay(scope, command.command.id)
                currentCoroutineContext().ensureActive()
                if (result is AppResult.Failed) return result
            }
            val observed = reconcileRoom(scope, command.command.id)
            currentCoroutineContext().ensureActive()
            if (observed is AppResult.Failed) return observed
            if (!(observed as AppResult.Success).value) return AppResult.Success(false)
        }
        val latest = read.room(scope, conversationId)
        currentCoroutineContext().ensureActive()
        return when (latest) {
            is AppResult.Failed -> latest
            is AppResult.Success ->
                when (latest.value.value.state) {
                    CollaborationDirectRoomState.READY -> AppResult.Success(true)
                    CollaborationDirectRoomState.FAILED,
                    CollaborationDirectRoomState.CANCELLED ->
                        AppResult.Failed(
                            Failure(
                                FailureKind.Unavailable,
                                "Review this conversation before retrying.",
                                code = "client.collaboration_room_retry_required",
                            )
                        )
                    else -> AppResult.Success(false)
                }
        }
    }

    private suspend fun acquireOriginals(
        work: CollaborationDeliveryWork,
        scope: CollaborationOperationScope,
        deviceId: String,
        conversationId: String,
    ): AppResult<Boolean> {
        val snapshot = messages(work.scope, conversationId).first()
        currentCoroutineContext().ensureActive()
        if (snapshot is AppResult.Failed) return snapshot
        val anchor =
            (snapshot as AppResult.Success)
                .value
                .mapNotNull { (it.state as? CollaborationMessageState.Sent)?.eventId }
                .firstOrNull()
        var reopened = false
        var acquired = acquire(scope, deviceId, conversationId, anchor)
        currentCoroutineContext().ensureActive()
        if (
            acquired is AppResult.Failed &&
                acquired.failure.code == "client.collaboration_history_focus_capacity"
        ) {
            if (scope !is CollaborationOperationScope.Selected) return acquired
            val prepared = reopenHistory(scope.workspaceId, deviceId, conversationId, anchor)
            currentCoroutineContext().ensureActive()
            if (prepared is AppResult.Failed) return prepared
            if (
                (prepared as AppResult.Success).value.value ==
                    CollaborationHistoryRuntimePreparation.SetupRequired
            )
                return AppResult.Failed(setupRequired())
            reopened = prepared.value.value == CollaborationHistoryRuntimePreparation.Reopened
            // One admitted capacity recovery; a missing receipt uses a freshly owned head.
            // No send is repeated to obtain history.
            acquired = acquire(scope, deviceId, conversationId, anchor)
            currentCoroutineContext().ensureActive()
        }
        if (acquired is AppResult.Failed) return acquired
        val recovered = recover(work.scope, conversationId)
        currentCoroutineContext().ensureActive()
        if (recovered is AppResult.Failed) return recovered
        val projected = reconcile(work.scope, conversationId)
        currentCoroutineContext().ensureActive()
        if (projected is AppResult.Failed) return projected
        val admitted = guard(work)
        if (admitted is AppResult.Failed) return admitted
        return AppResult.Success(reopened)
    }

    private suspend fun acquireAndRetire(
        work: CollaborationDeliveryWork,
        scope: CollaborationOperationScope,
        deviceId: String,
        conversationId: String,
    ): AppResult<Unit> {
        val acquired = acquireOriginals(work, scope, deviceId, conversationId)
        currentCoroutineContext().ensureActive()
        if (acquired is AppResult.Failed) return acquired
        val retired = retire(work.scope, conversationId)
        currentCoroutineContext().ensureActive()
        if (retired is AppResult.Failed) return retired
        if ((retired as AppResult.Success).value == 0) {
            val pending = messages(work.scope, conversationId).first()
            currentCoroutineContext().ensureActive()
            if (pending is AppResult.Failed) return pending
            if (
                (pending as AppResult.Success).value.any {
                    it.state is CollaborationMessageState.Sent
                }
            )
                return AppResult.Failed(
                    Failure(
                        FailureKind.Unavailable,
                        "Waiting for confirmed message history.",
                        code = "client.collaboration_history_pending",
                    )
                )
        }
        return guard(work)
    }

    private suspend fun guard(work: CollaborationDeliveryWork): AppResult<Unit> {
        val current = observeAccess(work.scope).first()
        currentCoroutineContext().ensureActive()
        if (current is AppResult.Failed) return current
        if ((current as AppResult.Success).value != work.access) return AppResult.Failed(changed())
        return AppResult.Success(Unit)
    }

    private fun busy() =
        Failure(
            FailureKind.Unavailable,
            "Chat delivery is already running.",
            code = "client.collaboration_delivery_busy",
        )

    private fun timeout() =
        Failure(
            FailureKind.Timeout,
            "Chat delivery timed out.",
            code = "client.collaboration_delivery_timeout",
        )

    private fun changed() =
        Failure(
            FailureKind.Unauthorized,
            "Chat ownership changed.",
            code = "client.session_changed",
        )

    private fun setupRequired() =
        Failure(
            FailureKind.Unavailable,
            "Activate chat on this device.",
            code = "client.collaboration_setup_required",
        )

    private fun waitingRoom() =
        Failure(
            FailureKind.Unavailable,
            "Conversation is being prepared.",
            code = "client.collaboration_room_pending",
        )
}
