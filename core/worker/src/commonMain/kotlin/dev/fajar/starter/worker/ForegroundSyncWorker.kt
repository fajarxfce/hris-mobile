package dev.fajar.starter.worker

import dev.fajar.starter.sync.domain.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*

/** Application-host entry point. Closing/cancelling the host stops pending work and retries. */
class ForegroundSyncWorker(
    private val tasks: List<SyncTask>,
    private val scheduler: ForegroundWorkScheduler,
    private val interval: Duration = 15.minutes,
    private val retryDelay: Duration = 30.seconds,
) {
    init {
        require(tasks.map { it.key }.distinct().size == tasks.size) { "Duplicate sync task keys." }
        require(interval.isPositive() && retryDelay.isPositive())
    }

    fun start(scope: CoroutineScope): Job =
        scope.launch {
            supervisorScope {
                tasks.forEach { task ->
                    launch {
                        val requests = scheduler.requests(task.key)
                        var wait: Duration? = Duration.ZERO
                        var timedTrigger = SyncTrigger.Scheduled
                        var backoff = retryDelay
                        while (isActive) {
                            // Coalesce a wake retained while stopped with the startup scan.
                            val requested =
                                if (requests.tryReceive().isSuccess) true
                                else if (wait == null) {
                                    requests.receive()
                                    true
                                } else
                                    withTimeoutOrNull(wait) {
                                        requests.receive()
                                        true
                                    } == true
                            val trigger = if (requested) SyncTrigger.Requested else timedTrigger
                            when (val result = runSyncTask(task, trigger)) {
                                SyncResult.Complete -> {
                                    wait = interval
                                    timedTrigger = SyncTrigger.Scheduled
                                    backoff = retryDelay
                                }
                                is SyncResult.Retry -> {
                                    timedTrigger = SyncTrigger.Requested
                                    if (result.failure == null) {
                                        // A completed bounded batch has more work, without a failed
                                        // request.
                                        wait = 100.milliseconds
                                        backoff = retryDelay
                                    } else {
                                        wait = backoff
                                        backoff = (backoff * 2).coerceAtMost(interval)
                                    }
                                }
                                is SyncResult.Blocked -> {
                                    wait = null
                                    backoff = retryDelay
                                }
                            }
                        }
                    }
                }
            }
        }
}
