package dev.fajar.starter.app.work

import dev.fajar.starter.app.bootstrap.ApplicationCollaborationState
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.sync.domain.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock

/** One application-owned incoming processor; DI binds each operation to its owning usecase. */
class CollaborationSpaceIncomingWorker(
    private val states: Lazy<StateFlow<ApplicationCollaborationState>>,
    private val ensureWake: suspend () -> AppResult<CollaborationSpaceIncomingWake>,
    private val readWake: suspend () -> AppResult<CollaborationSpaceIncomingWake?>,
    private val requestScan: suspend (CollaborationSpaceLocalAccess, String) -> AppResult<Boolean>,
    private val readDemand:
        suspend (CollaborationSpaceLocalAccess) -> AppResult<CollaborationSpaceIncomingWork>,
    private val readRuntimeDemand:
        suspend (CollaborationSpaceRuntimeReady) -> AppResult<CollaborationSpaceIncomingWork>,
    private val resolveBinding:
        suspend (
            CollaborationSpaceRuntimeReady, CollaborationSpaceIncomingPhysicalRoomDemand,
        ) -> AppResult<CollaborationSpaceIncomingBindingResolution>,
    private val inventory:
        suspend (
            CollaborationSpaceRuntimeReady, CollaborationSpaceIncomingSourceProgress,
        ) -> AppResult<CollaborationSpaceInventoryOutcome>,
    private val deferInventory:
        suspend (
            CollaborationSpaceRuntimeReady, CollaborationSpaceIncomingSourceProgress, Failure?,
        ) -> AppResult<Boolean>,
    private val recoverHistory:
        suspend (CollaborationSpaceRuntimeReady) -> AppResult<
                CollaborationSpaceHistoryRecoveryOutcome
            >,
    private val deferPhysical:
        suspend (
            CollaborationSpaceRuntimeReady, CollaborationSpaceIncomingPhysicalRoomDemand, Failure?,
        ) -> AppResult<Boolean>,
    private val discover: suspend (CollaborationSpaceOwner) -> AppResult<Boolean>,
    private val finish:
        suspend (CollaborationSpaceLocalAccess, CollaborationSpaceIncomingWake) -> AppResult<
                Boolean
            >,
    private val resume: suspend () -> AppResult<Boolean>,
    private val device:
        suspend (CollaborationNativeParent) -> AppResult<CollaborationSpaceRecoveryDevice?>,
    private val adopt:
        suspend (CollaborationSpaceRecoveryDevice) -> AppResult<CollaborationSpaceRuntimeReady>,
    private val room:
        suspend (CollaborationSpaceRuntimeReady, CollaborationSpaceIncomingRoomDemand) -> AppResult<
                CollaborationSpaceIncomingRoom?
            >,
    private val resolveLogicalBinding:
        suspend (CollaborationSpaceRuntimeReady, CollaborationSpaceIncomingRoomDemand) -> AppResult<
                CollaborationSpaceIncomingBindingResolution
            >,
    private val originalRoom:
        suspend (CollaborationSpaceRuntimeReady, CollaborationSpaceIncomingRoomDemand) -> AppResult<
                Boolean
            >,
    private val acquirePage:
        suspend (
            CollaborationSpaceRuntimeReady,
            CollaborationSpaceConversation,
            CollaborationHistoryPageOrigin,
        ) -> AppResult<CollaborationSpaceProviderHistoryAcquisition>,
    private val advance:
        suspend (
            CollaborationSpaceLocalAccess,
            CollaborationSpaceIncomingRoomDemand,
            CollaborationSpaceProviderHistoryAcquisition,
        ) -> AppResult<Boolean>,
    private val readReferences:
        suspend (CollaborationSpaceLocalAccess, CollaborationHistoryTraversal) -> AppResult<
                CollaborationHistoryReferences
            >,
    private val acquireEvents:
        suspend (
            CollaborationSpaceRuntimeReady, CollaborationSpaceConversation, List<String>,
        ) -> AppResult<CollaborationSpaceIncomingBatch>,
    private val completeReferences:
        suspend (
            CollaborationSpaceLocalAccess,
            CollaborationHistoryTraversal,
            CollaborationHistoryReferences,
            CollaborationSpaceIncomingBatch?,
        ) -> AppResult<CollaborationSpaceIncomingReferenceCompletion>,
    private val completeRoom:
        suspend (
            CollaborationSpaceLocalAccess,
            CollaborationHistoryTraversal,
            CollaborationSpaceIncomingRoomDemand,
        ) -> AppResult<Boolean>,
    private val demoteRoom:
        suspend (CollaborationSpaceRuntimeReady, CollaborationSpaceIncomingRoom) -> AppResult<
                Boolean
            >,
    private val recoverProvider:
        suspend (
            CollaborationSpaceLocalAccess,
            CollaborationHistoryTraversal,
            CollaborationSpaceIncomingRoomDemand,
        ) -> AppResult<Boolean>,
    private val defer:
        suspend (
            CollaborationSpaceLocalAccess, CollaborationSpaceIncomingRoomDemand, Failure?,
        ) -> AppResult<Boolean>,
    private val build: AppBuild,
) : SyncTask {
    override val key = CollaborationSpaceIncomingWake.WORK_KEY
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
        val requested = if (trigger == SyncTrigger.Scheduled) ensureWake() else readWake()
        if (requested is AppResult.Failed) return failed(requested)
        val wake = (requested as AppResult.Success).value ?: return SyncResult.Complete
        val published = states.value
        val current =
            withTimeoutOrNull(5.seconds) {
                published.first { it !is ApplicationCollaborationState.Pending }
            } ?: return SyncResult.Retry(pending("parent_pending"))
        val parent =
            current.recoveryParent()
                ?: return SyncResult.Blocked(collaborationRecoveryParentChanged())
        return withCollaborationRecoveryParent(published, parent) { process(parent, wake, trigger) }
    }

    private suspend fun process(
        parent: CollaborationNativeParent,
        wake: CollaborationSpaceIncomingWake,
        trigger: SyncTrigger,
    ): SyncResult {
        val loaded = device(parent)
        if (loaded is AppResult.Failed) return failed(loaded)
        // An absent native root grants no authority to discharge another scope's retained wake.
        val original = (loaded as AppResult.Success).value ?: return SyncResult.Complete
        val access = original.owner.localDirectoryAccess()
        val initial = readDemand(access)
        if (initial is AppResult.Failed) return failed(initial)
        if (
            trigger == SyncTrigger.Scheduled ||
                (initial as AppResult.Success).value.snapshot == null
        ) {
            val seeded = requestScan(access, wake.generation)
            if (seeded is AppResult.Failed) return failed(seeded)
        }
        val scanned = discover(original.owner)
        if (scanned is AppResult.Failed) return failed(scanned)
        val found = readDemand(access)
        if (found is AppResult.Failed) return failed(found)
        val pending = (found as AppResult.Success).value
        if (
            pending.turn.isEmpty() &&
                pending.inventoryReady.isEmpty() &&
                pending.historyReady == null
        )
            return continueOrFinish(access, wake)
        val adopted = adopt(original)
        if (adopted is AppResult.Failed) return failed(adopted)
        val runtime = (adopted as AppResult.Success).value
        val selected = readRuntimeDemand(runtime)
        if (selected is AppResult.Failed) return failed(selected)
        val work = (selected as AppResult.Success).value
        // Reserve recovery service before slow primary work can exhaust the shared deadline.
        val source = work.inventoryReady.singleOrNull()
        if (source != null) {
            val processed = processInventory(runtime, source)
            if (processed is AppResult.Failed) return failed(processed)
        }
        if (work.historyReady != null) {
            val recovered = recoverHistory(runtime)
            if (recovered is AppResult.Failed) return failed(recovered)
        }
        for (task in work.turn) {
            val processed =
                when (task) {
                    is CollaborationSpaceIncomingTask.Logical -> processRoom(runtime, task.captured)
                    is CollaborationSpaceIncomingTask.Physical ->
                        processPhysical(runtime, task.captured)
                }
            if (processed is AppResult.Failed) return failed(processed)
        }
        return continueOrFinish(access, wake, runtime)
    }

    private suspend fun processInventory(
        runtime: CollaborationSpaceRuntimeReady,
        captured: CollaborationSpaceIncomingSourceProgress,
    ): AppResult<Boolean> {
        val received =
            withTimeoutOrNull(10.seconds) { inventory(runtime, captured) }
                ?: AppResult.Failed(pending("inventory_timeout", FailureKind.Timeout))
        if (received is AppResult.Failed && blocked(received.failure)) return received
        if (
            received is AppResult.Success &&
                received.value in
                    setOf(
                        CollaborationSpaceInventoryOutcome.Advanced,
                        CollaborationSpaceInventoryOutcome.Complete,
                        CollaborationSpaceInventoryOutcome.Obsolete,
                        CollaborationSpaceInventoryOutcome.Deferred,
                    )
        )
            return AppResult.Success(true)
        val latest = readRuntimeDemand(runtime)
        if (latest is AppResult.Failed) return latest
        val current =
            (latest as AppResult.Success).value.snapshot?.demand?.sources?.singleOrNull {
                it.source == captured.source &&
                    it.inventoryGeneration == captured.inventoryGeneration
            } ?: return AppResult.Success(false)
        return deferInventory(runtime, current, (received as? AppResult.Failed)?.failure)
    }

    private suspend fun processPhysical(
        runtime: CollaborationSpaceRuntimeReady,
        captured: CollaborationSpaceIncomingPhysicalRoomDemand,
    ): AppResult<Boolean> {
        val resolved =
            withTimeoutOrNull(40.seconds) { resolveBinding(runtime, captured) }
                ?: AppResult.Failed(pending("binding_timeout", FailureKind.Timeout))
        if (resolved is AppResult.Failed && blocked(resolved.failure)) return resolved
        if (
            resolved is AppResult.Success &&
                resolved.value in
                    setOf(
                        CollaborationSpaceIncomingBindingResolution.Queued,
                        CollaborationSpaceIncomingBindingResolution.Obsolete,
                    )
        )
            return AppResult.Success(true)
        return deferPhysical(runtime, captured, (resolved as? AppResult.Failed)?.failure)
    }

    private suspend fun processRoom(
        runtime: CollaborationSpaceRuntimeReady,
        captured: CollaborationSpaceIncomingRoomDemand,
    ): AppResult<Boolean> {
        val received =
            withTimeoutOrNull(40.seconds) {
                if (captured.target is CollaborationSpaceIncomingTarget.Original)
                    return@withTimeoutOrNull receiveRoom(runtime, captured)
                val routed = resolveLogicalBinding(runtime, captured)
                if (routed is AppResult.Failed) return@withTimeoutOrNull routed
                when ((routed as AppResult.Success).value) {
                    CollaborationSpaceIncomingBindingResolution.Published ->
                        receiveRoom(runtime, captured)
                    CollaborationSpaceIncomingBindingResolution.Queued,
                    CollaborationSpaceIncomingBindingResolution.Obsolete -> AppResult.Success(true)
                    // Waiting did not advance work; the domain deferral owner must retain backoff.
                    else -> AppResult.Failed(pending("binding_pending"))
                }
            } ?: AppResult.Failed(pending("room_timeout", FailureKind.Timeout))
        if (received is AppResult.Failed && blocked(received.failure)) return received
        if ((received as? AppResult.Success)?.value == true) return received
        // Provider phase may already have committed even if its response was lost.
        val latest = readRuntimeDemand(runtime)
        if (latest is AppResult.Failed) return latest
        val retained =
            (latest as AppResult.Success).value.snapshot?.demand?.rooms?.singleOrNull {
                it.target == captured.target && it.generation == captured.generation
            } ?: return AppResult.Success(false)
        return defer(
            runtime.owner.localDirectoryAccess(),
            retained,
            (received as? AppResult.Failed)?.failure,
        )
    }

    /** At most one metadata page and one pending page; the published parent owns ongoing sync. */
    private suspend fun receiveRoom(
        runtime: CollaborationSpaceRuntimeReady,
        captured: CollaborationSpaceIncomingRoomDemand,
    ): AppResult<Boolean> {
        if (captured.target is CollaborationSpaceIncomingTarget.Original)
            return originalRoom(runtime, captured)
        val loaded = room(runtime, captured)
        if (loaded is AppResult.Failed) return loaded
        val local = (loaded as AppResult.Success).value ?: return AppResult.Success(true)
        val access = runtime.owner.localDirectoryAccess()
        var current = local.captured
        if (current.phase != CollaborationSpaceIncomingPhase.Pending) {
            val origin =
                if (current.phase == CollaborationSpaceIncomingPhase.Latest)
                    CollaborationHistoryPageOrigin.Latest
                else CollaborationHistoryPageOrigin.SavedPosition
            val acquired = acquirePage(runtime, local.conversation, origin)
            if (acquired is AppResult.Failed) return acquired
            val receipt = (acquired as AppResult.Success).value
            val advanced = advance(access, current, receipt)
            if (advanced is AppResult.Failed) return advanced
            if (!(advanced as AppResult.Success).value) return AppResult.Success(true)
            current =
                current.copy(
                    phase =
                        if (receipt.exhausted) CollaborationSpaceIncomingPhase.Pending
                        else CollaborationSpaceIncomingPhase.Provider,
                    retry = null,
                )
        }
        val loadedReferences = readReferences(access, local.traversal)
        if (loadedReferences is AppResult.Failed) return loadedReferences
        val references = (loadedReferences as AppResult.Success).value
        if (references.progress == null) {
            // Provider positions are device-scoped; a new identity must start its own traversal.
            return recoverProvider(access, local.traversal, current)
        }
        val batch =
            if (references.events.isEmpty()) null
            else {
                val acquired =
                    acquireEvents(
                        runtime,
                        local.conversation,
                        references.events.map { it.event.eventId },
                    )
                if (acquired is AppResult.Failed) return acquired
                (acquired as AppResult.Success).value
            }
        val resolved = completeReferences(access, local.traversal, references, batch)
        if (resolved is AppResult.Failed) return resolved
        // Render each retained content page while older provider metadata is still arriving.
        if (current.phase != CollaborationSpaceIncomingPhase.Pending)
            return AppResult.Success(false)
        val completed = completeRoom(access, local.traversal, current)
        if (completed is AppResult.Failed || (completed as AppResult.Success).value)
            return completed
        val completion = (resolved as AppResult.Success).value
        if (completion.more) return AppResult.Success(false)
        if (completion.retryRequired) {
            val transferred = demoteRoom(runtime, local.copy(captured = current))
            if (transferred is AppResult.Failed || (transferred as AppResult.Success).value)
                return transferred
        }
        val revisited = recoverProvider(access, local.traversal, current)
        if (revisited is AppResult.Failed || (revisited as AppResult.Success).value)
            return revisited
        // A changed provider capture remains recoverable without an immediate empty-work loop.
        return AppResult.Failed(
            pending(if (completion.retryRequired) "keys_pending" else "source_pending")
        )
    }

    private suspend fun continueOrFinish(
        access: CollaborationSpaceLocalAccess,
        wake: CollaborationSpaceIncomingWake,
        runtime: CollaborationSpaceRuntimeReady? = null,
    ): SyncResult {
        val loaded = if (runtime == null) readDemand(access) else readRuntimeDemand(runtime)
        if (loaded is AppResult.Failed) return failed(loaded)
        val current = (loaded as AppResult.Success).value
        if (
            current.turn.isNotEmpty() ||
                current.inventoryReady.isNotEmpty() ||
                current.scanPending ||
                current.historyReady != null
        )
            return continueWork()
        if (current.roomsPending || current.historyDeferred != null)
            return SyncResult.Retry(pending("deferred"))
        val finished = finish(access, wake)
        if (finished is AppResult.Failed) return failed(finished)
        return if ((finished as AppResult.Success).value) SyncResult.Complete else continueWork()
    }

    private suspend fun continueWork(): SyncResult {
        val result = resume()
        return if (result is AppResult.Failed) failed(result) else SyncResult.Complete
    }

    private fun blocked(failure: Failure) =
        failure.kind in
            setOf(
                FailureKind.Unauthorized,
                FailureKind.AccessDenied,
                FailureKind.Validation,
                FailureKind.Conflict,
            )

    private fun failed(result: AppResult.Failed): SyncResult =
        if (blocked(result.failure)) SyncResult.Blocked(result.failure)
        else SyncResult.Retry(result.failure)

    private fun pending(suffix: String, kind: FailureKind = FailureKind.Unavailable) =
        Failure(
            kind,
            "Messages are waiting to sync.",
            code = "client.collaboration_space_incoming_$suffix",
            retryAfterSeconds = 30,
        )
}
