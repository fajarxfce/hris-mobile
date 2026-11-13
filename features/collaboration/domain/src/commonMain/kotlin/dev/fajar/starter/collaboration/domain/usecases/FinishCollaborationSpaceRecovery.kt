package dev.fajar.starter.collaboration.domain.usecases

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.firstOrNull

/**
 * A clean finite sweep and current complete local inventory may discharge only its original wake.
 */
class FinishCollaborationSpaceRecovery(
    private val progress: CollaborationSpaceRecoveryProgressRepository,
    private val inventory: CollaborationSpaceRoomBindingInventoryRepository,
    private val messages: CollaborationSpaceMessageRepository,
    private val outbox: CollaborationSpaceComposerOutboxRepository,
    private val directory: CollaborationSpaceDirectoryRepository,
    private val sessions: SessionRepository,
    private val server: IdentityServer,
) {
    suspend operator fun invoke(
        snapshot: CollaborationSpaceRecoverySnapshot
    ): AppResult<CollaborationSpaceRecoveryUpdate> = Window(snapshot).run()

    private inner class Window(val snapshot: CollaborationSpaceRecoverySnapshot) {
        val owner = snapshot.owner
        val access = owner.localDirectoryAccess()
        var restriction: Failure? = null

        suspend fun run(): AppResult<CollaborationSpaceRecoveryUpdate> =
            withTimeoutOrNull(5_000) {
                val current = admitted()
                if (current is AppResult.Failed) return@withTimeoutOrNull current
                val proposed =
                    nextCollaborationSpaceRecoverySweep(
                        snapshot,
                        CollaborationSpaceRecoveryChange.Finish,
                    )
                if (proposed is AppResult.Failed) return@withTimeoutOrNull proposed
                val previous = requireNotNull(snapshot.progress)
                val swept = requireNotNull(previous.sweep)
                val fresh = checked {
                    when (val read = inventory.page(access, null, 1)) {
                        is AppResult.Failed -> read
                        is AppResult.Success ->
                            if (read.value.bindings.size > 1) changedSpaceProgress()
                            else
                                AppResult.Success(
                                    read.value.copy(bindings = read.value.bindings.toList())
                                )
                    }
                }
                if (fresh is AppResult.Failed) return@withTimeoutOrNull fresh
                val page = (fresh as AppResult.Success).value
                val first = CollaborationSpaceRecoverySweep(swept.wakeGeneration)
                val candidate =
                    nextCollaborationSpaceRecoverySweep(
                        snapshot.copy(progress = previous.copy(sweep = first)),
                        CollaborationSpaceRecoveryChange.Attempt(page),
                    )
                if (candidate is AppResult.Failed) return@withTimeoutOrNull candidate
                val old = swept.boundary
                val latest = page.boundary
                if (
                    old != null &&
                        (latest == null ||
                            latest.generation != old.generation ||
                            latest.revision < old.revision)
                )
                    return@withTimeoutOrNull changedSpaceProgress()
                val commands = checked {
                    val read = messages.observe(access, null).firstOrNull()
                    if (read is AppResult.Failed) read
                    else {
                        val captured =
                            (read as? AppResult.Success)?.value?.let {
                                collaborationSpaceMessageInventory(access, it)
                            }
                        if (captured == null) changedSpaceProgress()
                        else AppResult.Success(captured)
                    }
                }
                if (commands is AppResult.Failed) return@withTimeoutOrNull commands
                val hasPending =
                    (commands as AppResult.Success).value.any {
                        it.state !is CollaborationMessageState.Sent &&
                            it.state != CollaborationMessageState.Cancelled
                    }
                var next =
                    if (hasPending || latest != old || page.hasNewerBindings) first
                    else (proposed as AppResult.Success).value
                if (next == null) {
                    val waiting = checked { outbox.pending(access, limit = 1) }
                    if (waiting is AppResult.Failed) return@withTimeoutOrNull waiting
                    val captured =
                        captureCollaborationSpaceComposerPage(
                            access,
                            (waiting as AppResult.Success).value,
                            limit = 1,
                        ) ?: return@withTimeoutOrNull changedSpaceProgress()
                    if (captured.submissions.isNotEmpty()) next = first
                }
                publish(next)
            }
                ?: collaborationRecoveryFailure(
                    "client.collaboration_space_recovery_finish_timeout",
                    FailureKind.Timeout,
                )

        private suspend fun publish(
            next: CollaborationSpaceRecoverySweep?
        ): AppResult<CollaborationSpaceRecoveryUpdate> {
            val previous = requireNotNull(snapshot.progress)
            val result = checked { progress.replace(owner, previous, next) }
            if (result is AppResult.Failed) return result
            val saved = (result as AppResult.Success).value ?: return changedSpaceProgress()
            if (!validCollaborationSpaceRecoveryProgress(owner, saved) || saved.sweep != next)
                return changedSpaceProgress(FailureKind.Unexpected)
            return AppResult.Success(
                CollaborationSpaceRecoveryUpdate(
                    snapshot.copy(progress = saved),
                    if (next == null)
                        CollaborationSpaceRecoveryWake(
                            requireNotNull(previous.sweep).wakeGeneration
                        )
                    else null,
                )
            )
        }

        suspend fun <T> checked(block: suspend () -> AppResult<T>): AppResult<T> {
            val before = admitted()
            if (before is AppResult.Failed) return before
            val result = block()
            val after = admitted()
            return if (after is AppResult.Failed) after else result
        }

        suspend fun admitted(): AppResult<Unit> {
            currentCoroutineContext().ensureActive()
            restriction?.let {
                return AppResult.Failed(it)
            }
            val local = sessions.guardSpaceRecoveryOwner(server, owner)
            if (local is AppResult.Failed) return local
            val read = directory.admission(access)
            currentCoroutineContext().ensureActive()
            if (read is AppResult.Failed) return read
            val value = (read as AppResult.Success).value
            if (!validCollaborationSpaceDirectoryAdmission(value)) return changedSpaceProgress()
            restriction = value.denial
            restriction?.let {
                return AppResult.Failed(it)
            }
            return sessions.guardSpaceRecoveryOwner(server, owner)
        }
    }
}
