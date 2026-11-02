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

/** One account-owned sweep processor and at most one waiter without captured native resources. */
class CollaborationRecoveryWorker(
    private val states: Lazy<StateFlow<ApplicationCollaborationState>>,
    private val ensureWake: EnsureCollaborationRecoveryWake,
    private val readWake: ReadCollaborationRecoveryWake,
    private val completeWake: CompleteCollaborationRecoveryWake,
    private val resume: Lazy<ResumeCollaborationRecovery>,
    private val readProgress: ReadCollaborationRecoveryProgress,
    private val updateProgress: UpdateCollaborationRecoveryProgress,
    private val devices: LoadCollaborationDevicePage,
    private val admit: AcquireCollaborationBackgroundAdmission,
    private val recoverReplies: suspend (CollaborationNativeParent) -> SyncResult,
    private val recoverPriority: suspend (CollaborationNativeParent) -> SyncResult,
    private val build: AppBuild,
    private val recoverCompany: suspend (CollaborationBackgroundAdmission) -> SyncResult,
) : SyncTask {
    override val key = CollaborationRecoveryWake.WORK_KEY
    private val admission = Semaphore(2)
    private val processing = Mutex()
    private val waiting = MutableStateFlow(0)

    override suspend fun invoke(): SyncResult {
        currentCoroutineContext().ensureActive()
        if (build.platform !in setOf(AppPlatform.Android, AppPlatform.Ios))
            return SyncResult.Complete
        if (!admission.tryAcquire()) return SyncResult.Retry(failure("busy"))
        var queued = true
        waiting.update { it + 1 }
        try {
            return withTimeoutOrNull(180.seconds) {
                processing.withLock {
                    waiting.update { it - 1 }
                    queued = false
                    withTimeoutOrNull(90.seconds) { processCurrentParent() }
                        ?: SyncResult.Retry(failure("timeout", FailureKind.Timeout))
                }
            } ?: SyncResult.Retry(failure("timeout", FailureKind.Timeout))
        } finally {
            if (queued) waiting.update { it - 1 }
            admission.release()
        }
    }

    private suspend fun processCurrentParent(): SyncResult {
        val currentStates = states.value
        val observed =
            withTimeoutOrNull(5.seconds) {
                currentStates.first { it !is ApplicationCollaborationState.Pending }
            } ?: return SyncResult.Retry(failure("parent_pending"))
        val parent =
            observed.recoveryParent()
                ?: return SyncResult.Blocked(collaborationRecoveryParentChanged())
        return withCollaborationRecoveryParent(currentStates, parent) { process(parent) }
    }

    private suspend fun process(parent: CollaborationNativeParent): SyncResult {
        val wake = ensureWake()
        if (wake is AppResult.Failed) return retry(wake)
        val replies =
            withTimeoutOrNull(8.seconds) { recoverReplies(parent) }
                ?: SyncResult.Retry(failure("reply_timeout", FailureKind.Timeout))
        currentCoroutineContext().ensureActive()
        // Priority cannot consume the ordinary company's reserved turn or reset its seek.
        val priority =
            withTimeoutOrNull(20.seconds) { recoverPriority(parent) }
                ?: SyncResult.Retry(failure("priority_timeout", FailureKind.Timeout))
        currentCoroutineContext().ensureActive()
        return companies(parent, (wake as AppResult.Success).value, independent(replies, priority))
    }

    private suspend fun companies(
        parent: CollaborationNativeParent,
        wake: CollaborationRecoveryWake,
        independent: SyncResult,
    ): SyncResult {
        val found = readProgress()
        if (found is AppResult.Failed) return retry(found)
        var snapshot = (found as AppResult.Success).value
        if (
            snapshot.access !=
                CollaborationRecoveryAccess(parent.sessionId, parent.audience, parent.accountId)
        )
            return SyncResult.Blocked(collaborationRecoveryParentChanged())
        if (snapshot.progress?.sweep == null) {
            val begun = updateProgress(snapshot, CollaborationRecoveryChange.Begin(wake))
            if (begun is AppResult.Failed) return retry(begun)
            snapshot = (begun as AppResult.Success).value.snapshot
        }
        val page = devices(parent, snapshot.progress?.sweep?.afterCompanyId, limit = 1)
        if (page is AppResult.Failed) return retry(page)
        val directory = (page as AppResult.Success).value
        for (device in directory.devices) {
            currentCoroutineContext().ensureActive()
            val attempted =
                updateProgress(snapshot, CollaborationRecoveryChange.Attempt(device.companyId))
            if (attempted is AppResult.Failed) return retry(attempted)
            snapshot = (attempted as AppResult.Success).value.snapshot
            val result =
                withTimeoutOrNull(50.seconds) { company(parent, device) }
                    ?: SyncResult.Retry(failure("company_timeout", FailureKind.Timeout))
            currentCoroutineContext().ensureActive()
            val completed =
                updateProgress(
                    snapshot,
                    CollaborationRecoveryChange.Complete(
                        device.companyId,
                        retryRequired = result is SyncResult.Retry && result.failure != null,
                        continuationRequired = result is SyncResult.Retry && result.failure == null,
                    ),
                )
            if (completed is AppResult.Failed) return retry(completed)
            snapshot = (completed as AppResult.Success).value.snapshot
        }
        if (directory.nextAfterCompanyId != null) return continueWhenHealthy(independent)
        val sweep = requireNotNull(snapshot.progress?.sweep)
        val healthyContinuation =
            sweep.continuationRequired && !sweep.retryRequired && sweep.inFlightCompanyId == null
        val finished = updateProgress(snapshot, CollaborationRecoveryChange.Finish)
        if (finished is AppResult.Failed) return retry(finished)
        val completion =
            (finished as AppResult.Success).value.completedWake
                ?: return if (healthyContinuation) continueWhenHealthy(independent)
                else SyncResult.Retry(failure("sweep_pending"))
        // An independent phase cannot acknowledge a wake while another phase is pending.
        if (independent != SyncResult.Complete) return continueWhenHealthy(independent)
        val acknowledged = completeWake(completion)
        if (acknowledged is AppResult.Failed) return retry(acknowledged)
        // An ACK can lose to a new wake; check even after success because a later write can win.
        val pending = readWake()
        if (pending is AppResult.Failed) return retry(pending)
        return if ((pending as AppResult.Success).value == null) SyncResult.Complete
        else continuePending()
    }

    private fun independent(vararg results: SyncResult): SyncResult {
        for (result in results) when (result) {
            is SyncResult.Blocked -> return SyncResult.Retry(result.failure)
            is SyncResult.Retry -> if (result.failure != null) return result
            SyncResult.Complete -> Unit
        }
        return if (results.any { it is SyncResult.Retry }) SyncResult.Retry()
        else SyncResult.Complete
    }

    private suspend fun continueWhenHealthy(independent: SyncResult): SyncResult =
        if (independent is SyncResult.Retry && independent.failure != null) independent
        else continuePending()

    private suspend fun company(
        parent: CollaborationNativeParent,
        device: CollaborationDeviceReference,
    ): SyncResult {
        if (
            device.state != CollaborationDeviceState.ACTIVE ||
                device.platform.name != build.platform.name.uppercase()
        )
            return SyncResult.Complete
        val granted = admit(parent, device)
        currentCoroutineContext().ensureActive()
        if (granted is AppResult.Failed) return recoveryFailure(granted.failure)
        return recoverCompany((granted as AppResult.Success).value)
    }

    private suspend fun continuePending(): SyncResult {
        // A queued invocation captures fresh ownership only after this processor releases its lock.
        if (waiting.value > 0) return SyncResult.Complete
        var rejected: Failure? = null
        repeat(3) { attempt ->
            val scheduled = resume.value()
            currentCoroutineContext().ensureActive()
            if (scheduled is AppResult.Success || waiting.value > 0) return SyncResult.Complete
            rejected = (scheduled as AppResult.Failed).failure
            if (attempt < 2) delay(100)
        }
        return SyncResult.Retry(rejected)
    }

    private fun retry(result: AppResult.Failed): SyncResult = recoveryFailure(result.failure)

    private fun recoveryFailure(failure: Failure): SyncResult =
        when (failure.kind) {
            FailureKind.Unauthorized,
            FailureKind.AccessDenied,
            FailureKind.Validation -> SyncResult.Blocked(failure)
            else -> SyncResult.Retry(failure)
        }

    private fun failure(suffix: String, kind: FailureKind = FailureKind.Unavailable) =
        Failure(kind, "Chat recovery is pending.", code = "client.collaboration_recovery_$suffix")
}
