package dev.fajar.starter.app.work

import dev.fajar.starter.app.bootstrap.ApplicationCollaborationState
import dev.fajar.starter.collaboration.domain.entities.CollaborationNativeParent
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.sync.domain.SyncResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Selected-company denial or pending cleanup cannot erase a processed native parent. */
internal fun ApplicationCollaborationState.recoveryParent(): CollaborationNativeParent? =
    ((this as? ApplicationCollaborationState.Observed)?.publication as? AppResult.Success)
        ?.value
        ?.retention
        ?.parent

internal suspend fun withCollaborationRecoveryParent(
    states: StateFlow<ApplicationCollaborationState>,
    parent: CollaborationNativeParent,
    action: suspend () -> SyncResult,
): SyncResult = coroutineScope {
    val replaced = MutableStateFlow(false)
    val work = async(start = CoroutineStart.LAZY) { action() }
    val observation =
        launch(start = CoroutineStart.UNDISPATCHED) {
            states.first { it.recoveryParent() != parent }
            replaced.value = true
            work.cancel()
        }
    try {
        work.start()
        work.await()
    } catch (cancelled: CancellationException) {
        currentCoroutineContext().ensureActive()
        if (!replaced.value) throw cancelled
        SyncResult.Blocked(collaborationRecoveryParentChanged())
    } finally {
        withContext(NonCancellable) {
            observation.cancelAndJoin()
            work.cancelAndJoin()
        }
    }
}

internal fun collaborationRecoveryParentChanged() =
    dev.fajar.starter.common.result.Failure(
        dev.fajar.starter.common.result.FailureKind.Unauthorized,
        "Chat ownership changed.",
        code = "client.session_changed",
    )
