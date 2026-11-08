package dev.fajar.starter.worker

import dev.fajar.starter.sync.domain.SyncTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

/**
 * Runs explicit host requests while its scope is alive. Durable scheduling owns retries and
 * periodic work; task results never create a second foreground timer.
 */
class RequestedSyncWorker(tasks: List<SyncTask>, scheduler: ForegroundWorkScheduler) {
    init {
        require(tasks.map { it.key }.distinct().size == tasks.size) { "Duplicate sync task keys." }
    }

    private val lanes = tasks.map { it to scheduler.requests(it.key) }

    fun start(scope: CoroutineScope): Job =
        scope.launch {
            supervisorScope {
                lanes.forEach { (task, requests) ->
                    launch {
                        for (request in requests) {
                            // The persisted OS request remains responsible for a failed/unfinished
                            // pass.
                            runSyncTask(task)
                        }
                    }
                }
            }
        }
}
