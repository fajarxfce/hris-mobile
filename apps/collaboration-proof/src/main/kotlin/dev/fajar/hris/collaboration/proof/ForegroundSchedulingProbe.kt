package dev.fajar.hris.collaboration.proof

import android.content.Context
import androidx.work.*
import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.common.result.FailureKind
import dev.fajar.starter.database.*
import dev.fajar.starter.sync.domain.*
import dev.fajar.starter.worker.*
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.TimeSource
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.json.JSONObject

/** Real WorkManager backoff and process-local wake; synthetic metadata only, no SDK or login. */
internal class ForegroundSchedulingProbe(
    private val context: Context,
    private val directory: File,
) {
    suspend fun run() = coroutineScope {
        check(!File(directory, "workmanager-enabled.json").exists())
        val database = createAppDatabase(context)
        val task = ProbeTask(database.workWakes)
        val queue = ForegroundWorkScheduler(setOf(KEY))
        var manager: WorkManager? = null
        var foreground: Job? = null
        val result = JSONObject()
        try {
            WorkManager.initialize(
                context,
                Configuration.Builder().setWorkerFactory(SyncWorkerFactory { listOf(task) }).build(),
            )
            val platform = WorkManager.getInstance(context).also { manager = it }
            val scheduler = AndroidWorkScheduler(platform, onRequested = { queue.notify(it) })
            val worker = ForegroundSyncWorker(listOf(task), queue)
            database.workWakes.request(KEY, UUID.randomUUID().toString(), 1)
            AndroidWorkScheduler(platform).enqueue(KEY)
            val original =
                platform
                    .getWorkInfosForUniqueWorkFlow(KEY)
                    .first { rows ->
                        rows.any { it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount == 1 }
                    }
                    .single { !it.state.isFinished }
            foreground = worker.start(this)
            task.attempts.first { it >= 2 }
            task.ready.set(true)
            bypassBackoff(database.workWakes, task, scheduler, platform, original, result)
            foreground.cancelAndJoin()
            foreground = null
            val stopped = task.attempts.value
            val next = UUID.randomUUID().toString()
            checkNotNull(database.workWakes.request(KEY, next, 1))
            scheduler.enqueue(KEY)
            delay(250)
            check(task.attempts.value == stopped)
            foreground = worker.start(this)
            check(withTimeout(5_000) { task.completed.receive() } == next)
            delay(100)
            check(task.attempts.value == stopped + 1)
            result.put("stoppedOwnerDidNotRun", true).put("restartConsumedWakeOnce", true)
        } finally {
            withContext(NonCancellable) {
                foreground?.cancelAndJoin()
                queue.close()
                try {
                    withTimeout(15_000) {
                        manager?.let {
                            it.cancelUniqueWork(KEY).await()
                            it.getWorkInfosForUniqueWorkFlow(KEY).first { rows ->
                                rows.all { row -> row.state.isFinished }
                            }
                        }
                        task.active.first { it == 0 }
                    }
                } finally {
                    task.completed.close()
                    database.close()
                }
            }
        }
        result.put("cleanup", true).put("providerRequests", 0).put("messagesSent", 0)
        directory.mkdirs()
        File(directory, "foreground-scheduling.json").writeText(result.toString())
    }

    private suspend fun bypassBackoff(
        store: WorkWakeStore,
        task: ProbeTask,
        scheduler: AndroidWorkScheduler,
        manager: WorkManager,
        original: WorkInfo,
        result: JSONObject,
    ) {
        val generation = UUID.randomUUID().toString()
        checkNotNull(store.request(KEY, generation, 1))
        val mark = TimeSource.Monotonic.markNow()
        scheduler.enqueue(KEY)
        check(withTimeout(5_000) { task.completed.receive() } == generation)
        val elapsed = mark.elapsedNow().inWholeMilliseconds
        check(elapsed < 5_000)
        val jobs = manager.getWorkInfosForUniqueWorkFlow(KEY).first()
        check(
            jobs
                .single { it.id == original.id }
                .let { it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount == 1 }
        )
        check(jobs.any { it.state == WorkInfo.State.BLOCKED })
        check(store.get(KEY)?.let { !it.pending && it.generation == generation } == true)
        result
            .put("foregroundAcknowledgementMillis", elapsed)
            .put("olderWorkStillInBackoff", true)
            .put("appendedWorkStillBlocked", true)
            .put("exactDurableGenerationAcknowledged", true)
    }

    private class ProbeTask(private val store: WorkWakeStore) : SyncTask {
        override val key = KEY
        val attempts = MutableStateFlow(0)
        val ready = AtomicBoolean(false)
        val active = MutableStateFlow(0)
        val completed = Channel<String>(Channel.CONFLATED)

        override suspend fun invoke(): SyncResult {
            active.update { it + 1 }
            try {
                val mayComplete = ready.get()
                attempts.update { it + 1 }
                if (!mayComplete)
                    return SyncResult.Retry(Failure(FailureKind.Network, "Fixture unavailable"))
                val wake = store.get(KEY) ?: return SyncResult.Complete
                if (wake.pending) {
                    check(store.acknowledge(KEY, wake.generation))
                    completed.send(wake.generation)
                }
                return SyncResult.Complete
            } finally {
                active.update { it - 1 }
            }
        }
    }

    private companion object {
        const val KEY = "proof.foreground-delivery"
    }
}
