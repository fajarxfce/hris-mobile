package dev.fajar.starter.dashboard.domain.usecases

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.dashboard.domain.repositories.DashboardRepository
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.sync.domain.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One instance per container serializes foreground, periodic, and explicit runs. */
class SyncDashboard(
    private val repository: DashboardRepository,
    private val sessions: SessionRepository,
) : SyncTask {
    override val key = KEY
    private val execution = Mutex()

    override suspend fun invoke(): SyncResult =
        execution.withLock {
            val session =
                when (val result = sessions.current()) {
                    is AppResult.Failed -> return@withLock syncFailure(result.failure)
                    is AppResult.Success -> result.value ?: return@withLock SyncResult.Complete
                }
            val changes =
                when (val pending = repository.pendingChanges(session.id, BATCH_SIZE)) {
                    is AppResult.Failed -> return@withLock syncFailure(pending.failure)
                    is AppResult.Success -> pending.value
                }
            for (change in changes) {
                when (val pushed = repository.push(session.id, change)) {
                    is AppResult.Failed -> return@withLock syncFailure(pushed.failure)
                    is AppResult.Success -> Unit
                }
                // A cancelled request may already have reached the server. Keep its id for replay.
                currentCoroutineContext().ensureActive()
                when (val acknowledged = repository.acknowledge(session.id, change.operationId)) {
                    is AppResult.Failed -> return@withLock syncFailure(acknowledged.failure)
                    is AppResult.Success -> Unit
                }
            }
            when (val refreshed = repository.refresh(session.id)) {
                is AppResult.Failed -> return@withLock syncFailure(refreshed.failure)
                is AppResult.Success -> Unit
            }
            when (val remaining = repository.pendingChanges(session.id, 1)) {
                is AppResult.Failed -> syncFailure(remaining.failure)
                is AppResult.Success ->
                    if (remaining.value.isEmpty()) SyncResult.Complete else SyncResult.Retry()
            }
        }

    companion object {
        const val KEY = "dashboard-sync"
        const val BATCH_SIZE = 50
    }
}
