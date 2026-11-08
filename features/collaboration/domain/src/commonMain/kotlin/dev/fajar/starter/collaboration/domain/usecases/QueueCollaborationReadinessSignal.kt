package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.sync.domain.repositories.SyncScheduleRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Coalesces committed hints before waking original work; never opens a room or sends content. */
class QueueCollaborationReadinessSignal(
    private val sessions: SessionRepository,
    private val receipts: CollaborationReadinessReceiptRepository,
    private val wakes: CollaborationRecoveryWakeRepository,
    private val schedules: SyncScheduleRepository,
    private val server: IdentityServer,
    private val build: AppBuild,
    private val runtimes: CollaborationRuntimeRepository,
) {
    /** The application owns one serial collector, including cancellation of pending persistence. */
    fun observe(): Flow<AppResult<Boolean>> = runtimes.readinessSignals().map { invoke(it) }

    suspend operator fun invoke(signal: CollaborationReadinessSignal): AppResult<Boolean> {
        currentCoroutineContext().ensureActive()
        return withTimeoutOrNull(3_000) {
            val captured = sessions.capturePushParent(server, build)
            if (captured is AppResult.Failed) return@withTimeoutOrNull captured
            val parent = (captured as AppResult.Success).value
            val scope =
                collaborationReadinessScope(signal, parent)
                    ?: return@withTimeoutOrNull AppResult.Success(false)
            val retained = retain(scope, signal.control.publicationRevision)
            if (retained is AppResult.Failed) return@withTimeoutOrNull retained
            val pending =
                (retained as AppResult.Success).value
                    ?: return@withTimeoutOrNull AppResult.Success(false)
            sessions.guardPushParent(server, build, parent).let {
                if (it is AppResult.Failed) return@withTimeoutOrNull it
            }
            val wake = wakes.request()
            currentCoroutineContext().ensureActive()
            sessions.guardPushParent(server, build, parent).let {
                if (it is AppResult.Failed) return@withTimeoutOrNull it
            }
            if (wake is AppResult.Failed) return@withTimeoutOrNull wake
            currentCoroutineContext().ensureActive()
            val scheduled =
                withTimeoutOrNull(1_500) { schedules.request(CollaborationRecoveryWake.WORK_KEY) }
            currentCoroutineContext().ensureActive()
            sessions.guardPushParent(server, build, parent).let {
                if (it is AppResult.Failed) return@withTimeoutOrNull it
            }
            if (scheduled == null)
                return@withTimeoutOrNull collaborationRecoveryFailure(
                    "client.collaboration_recovery_schedule_timeout",
                    FailureKind.Timeout,
                )
            if (scheduled is AppResult.Failed) return@withTimeoutOrNull scheduled
            val acknowledged = acknowledge(scope, pending.progress.pendingRevision)
            if (acknowledged is AppResult.Failed) acknowledged else AppResult.Success(true)
        }
            ?: collaborationRecoveryFailure(
                "client.collaboration_readiness_timeout",
                FailureKind.Timeout,
            )
    }

    private suspend fun retain(
        scope: CollaborationReadinessScope,
        revision: Long,
    ): AppResult<CollaborationReadinessReceipt?> {
        repeat(3) {
            val loaded = receipts.current(scope)
            currentCoroutineContext().ensureActive()
            sessions.guardPushParent(server, build, scope.parent).let {
                if (it is AppResult.Failed) return it
            }
            if (loaded is AppResult.Failed) return loaded
            val before = (loaded as AppResult.Success).value
            if (before != null && !validCollaborationReadinessProgress(before.progress))
                return collaborationRecoveryFailure(
                    "client.collaboration_invalid_readiness_receipt",
                    FailureKind.Unexpected,
                )
            val next =
                retainCollaborationReadinessRevision(before?.progress, revision)
                    ?: return AppResult.Success(null)
            if (next == before?.progress) return AppResult.Success(before)
            val saved = receipts.replace(scope, before, next)
            currentCoroutineContext().ensureActive()
            sessions.guardPushParent(server, build, scope.parent).let {
                if (it is AppResult.Failed) return it
            }
            if (saved is AppResult.Failed || (saved as AppResult.Success).value != null)
                return saved
        }
        return collaborationRecoveryFailure(
            "client.collaboration_readiness_changed",
            FailureKind.Conflict,
        )
    }

    private suspend fun acknowledge(
        scope: CollaborationReadinessScope,
        revision: Long,
    ): AppResult<Unit> {
        repeat(3) {
            val loaded = receipts.current(scope)
            currentCoroutineContext().ensureActive()
            sessions.guardPushParent(server, build, scope.parent).let {
                if (it is AppResult.Failed) return it
            }
            if (loaded is AppResult.Failed) return loaded
            val current = (loaded as AppResult.Success).value
            if (
                current == null ||
                    !validCollaborationReadinessProgress(current.progress) ||
                    current.progress.pendingRevision < revision
            )
                return collaborationRecoveryFailure(
                    "client.collaboration_invalid_readiness_receipt",
                    FailureKind.Unexpected,
                )
            val next = acknowledgeCollaborationReadinessRevision(current.progress, revision)
            if (next == current.progress) return AppResult.Success(Unit)
            val saved = receipts.replace(scope, current, next)
            currentCoroutineContext().ensureActive()
            sessions.guardPushParent(server, build, scope.parent).let {
                if (it is AppResult.Failed) return it
            }
            if (saved is AppResult.Failed) return saved
            if ((saved as AppResult.Success).value != null) return AppResult.Success(Unit)
        }
        return collaborationRecoveryFailure(
            "client.collaboration_readiness_changed",
            FailureKind.Conflict,
        )
    }
}
