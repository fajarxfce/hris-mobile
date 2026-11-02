package dev.fajar.hris.collaboration.proof

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.work.*
import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.common.result.FailureKind
import dev.fajar.starter.database.*
import dev.fajar.starter.sync.domain.SyncResult
import dev.fajar.starter.sync.domain.SyncTask
import dev.fajar.starter.worker.AndroidWorkScheduler
import dev.fajar.starter.worker.SyncWorker
import dev.fajar.starter.worker.SyncWorkerFactory
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import org.json.JSONObject

/** Synthetic scheduling metadata in the separate emulator proof application, without HRIS login. */
internal class CollapsedSchedulingProbe(private val context: Context, private val directory: File) {
    suspend fun stage() {
        val database = createAppDatabase(context)
        try {
            val record =
                checkNotNull(database.workWakes.request(KEY, UUID.randomUUID().toString(), 1))
            directory.mkdirs()
            File(directory, "wake-stage.json")
                .writeText(JSONObject().put("generation", record.generation).toString())
        } finally {
            database.close()
        }
    }

    suspend fun run(resuming: Boolean = false) {
        check(!File(directory, "workmanager-enabled.json").exists())
        val database = createAppDatabase(context)
        val task = ProbeTask(database.workWakes)
        val manager =
            try {
                WorkManager.initialize(
                    context,
                    Configuration.Builder()
                        .setWorkerFactory(SyncWorkerFactory { listOf(task) })
                        .build(),
                )
                WorkManager.getInstance(context)
            } catch (failure: Throwable) {
                database.close()
                throw failure
            }
        val scheduler = AndroidWorkScheduler(manager, setOf(KEY))
        val peer = AndroidWorkScheduler(manager, setOf(KEY))
        val checks = JSONObject()
        try {
            if (resuming) {
                val staged =
                    JSONObject(File(directory, "wake-stage.json").readText())
                        .getString("generation")
                check(
                    database.workWakes.get(KEY)?.let { it.pending && it.generation == staged } ==
                        true
                )
                scheduler.enqueue(KEY)
                val run = task.next()
                check(run.wake.generation == staged)
                run.outcome.complete(Outcome.Complete)
                run.finished.await()
                finished(manager)
                check(run.acknowledged == true)
                check(database.workWakes.get(KEY)?.pending == false)
                checks.put("processDeathRepair", true)
            } else {
                check(database.workWakes.get(KEY) == null)
                generations(database.workWakes, manager, scheduler, peer, task, checks)
                burst(database.workWakes, manager, scheduler, peer, task, checks)
                backoff(database.workWakes, manager, scheduler, task, checks)
                failedSlot(database.workWakes, manager, scheduler, task, checks)
                cancelledSlot(database.workWakes, manager, scheduler, peer, task, checks)
            }
            inspectInputs()
            check(task.peak.get() <= 2)
            checks.put("maximumSimultaneousTasks", task.peak.get())
            checks.put("onlyTaskKeyInWorkInput", true)
            checks.put("noDependencies", true)
        } finally {
            try {
                withContext(NonCancellable) {
                    withTimeout(15_000) {
                        for (name in NAMES) manager.cancelUniqueWork(name).await()
                        finished(manager)
                        task.seen.forEach { it.finished.await() }
                        while (task.active.get() != 0) delay(10)
                    }
                }
            } finally {
                task.events.close()
                database.close()
            }
        }
        checks.put("cleanup", true)
        directory.mkdirs()
        File(directory, if (resuming) "wake-resume.json" else "wake-scheduling.json")
            .writeText(checks.toString())
    }

    private suspend fun generations(
        store: WorkWakeStore,
        manager: WorkManager,
        scheduler: AndroidWorkScheduler,
        peer: AndroidWorkScheduler,
        task: ProbeTask,
        checks: JSONObject,
    ) {
        val original = wake(store)
        scheduler.enqueue(KEY)
        val first = task.next()
        check(first.wake == original)
        val newer = wake(store)
        peer.enqueue(KEY)
        val second = task.next()
        check(second.wake == newer)
        waitFor(manager) { it.count { work -> work.state == WorkInfo.State.RUNNING } == 2 }
        val latest = wake(store)
        check(runCatching { scheduler.enqueue(KEY) }.exceptionOrNull() is IllegalStateException)
        first.outcome.complete(Outcome.Complete)
        second.outcome.complete(Outcome.Complete)
        first.finished.await()
        second.finished.await()
        finished(manager)
        check(first.acknowledged == false && second.acknowledged == false)
        check(store.get(KEY) == latest)
        scheduler.enqueue(KEY)
        val repair = task.next()
        check(repair.wake == latest)
        repair.outcome.complete(Outcome.Complete)
        repair.finished.await()
        finished(manager)
        check(repair.acknowledged == true && store.get(KEY)?.pending == false)
        checks
            .put("oldCompletionFenced", true)
            .put("twoRunningSlotsRejectThird", true)
            .put("capacityRepair", true)
    }

    private suspend fun burst(
        store: WorkWakeStore,
        manager: WorkManager,
        scheduler: AndroidWorkScheduler,
        peer: AndroidWorkScheduler,
        task: ProbeTask,
        checks: JSONObject,
    ) {
        wake(store)
        val accepted = coroutineScope {
            (0 until 40)
                .map { index ->
                    async(start = CoroutineStart.UNDISPATCHED) {
                        try {
                            (if (index % 2 == 0) scheduler else peer).enqueue(KEY)
                            true
                        } catch (_: IllegalStateException) {
                            false
                        }
                    }
                }
                .awaitAll()
                .count { it }
        }
        check(accepted > 0)
        val jobs = waitFor(manager) { it.any { work -> !work.state.isFinished } }
        check(jobs.count { !it.state.isFinished } <= 2)
        for (name in NAMES) manager.cancelUniqueWork(name).await()
        finished(manager)
        withTimeout(15_000) { task.seen.forEach { it.finished.await() } }
        while (task.events.tryReceive().isSuccess) {
            /* Discard cancelled fixture gates only. */
        }
        check(store.get(KEY)?.pending == true)
        checks
            .put("competingSchedulerRequests", 40)
            .put("acceptedRequests", accepted)
            .put("burstUnfinishedBound", 2)
    }

    private suspend fun backoff(
        store: WorkWakeStore,
        manager: WorkManager,
        scheduler: AndroidWorkScheduler,
        task: ProbeTask,
        checks: JSONObject,
    ) {
        wake(store)
        scheduler.enqueue(KEY)
        val run = task.next()
        run.outcome.complete(Outcome.Retry)
        run.finished.await()
        val pending =
            waitFor(manager) {
                    it.any { work ->
                        work.state == WorkInfo.State.ENQUEUED && work.runAttemptCount == 1
                    }
                }
                .single { !it.state.isFinished }
        repeat(20) { scheduler.enqueue(KEY) }
        val reused = snapshot(manager).filter { !it.state.isFinished }
        check(reused.size == 1 && reused.single().id == pending.id)
        check(store.get(KEY)?.pending == true)
        manager.cancelWorkById(pending.id).await()
        finished(manager)
        checks.put("backoffReusesPendingId", true).put("backoffRequests", 20)
    }

    private suspend fun failedSlot(
        store: WorkWakeStore,
        manager: WorkManager,
        scheduler: AndroidWorkScheduler,
        task: ProbeTask,
        checks: JSONObject,
    ) {
        wake(store)
        scheduler.enqueue(KEY)
        val failed = task.next()
        failed.outcome.complete(Outcome.Fail)
        failed.finished.await()
        waitFor(manager) {
            it.any { work -> work.state == WorkInfo.State.FAILED } &&
                it.all { work -> work.state.isFinished }
        }
        check(store.get(KEY)?.pending == true)
        scheduler.enqueue(KEY)
        val repair = task.next()
        repair.outcome.complete(Outcome.Complete)
        repair.finished.await()
        finished(manager)
        check(repair.acknowledged == true)
        checks.put("failedSlotReused", true)
    }

    private suspend fun cancelledSlot(
        store: WorkWakeStore,
        manager: WorkManager,
        scheduler: AndroidWorkScheduler,
        peer: AndroidWorkScheduler,
        task: ProbeTask,
        checks: JSONObject,
    ) {
        wake(store)
        scheduler.enqueue(KEY)
        val first = task.next()
        wake(store)
        peer.enqueue(KEY)
        val second = task.next()
        val both =
            waitFor(manager) { it.count { work -> work.state == WorkInfo.State.RUNNING } == 2 }
        val firstId = both.single { "${SyncWorker.TAG}:${NAMES[0]}" in it.tags }.id
        manager.cancelWorkById(firstId).await()
        first.finished.await()
        check(!second.finished.isCompleted)
        wake(store)
        scheduler.enqueue(KEY)
        val third = task.next()
        check(!second.finished.isCompleted)
        second.outcome.complete(Outcome.Complete)
        third.outcome.complete(Outcome.Complete)
        second.finished.await()
        third.finished.await()
        finished(manager)
        check(second.acknowledged == false && third.acknowledged == true)
        checks.put("cancelledSlotReused", true).put("otherRunningSlotPreserved", true)
    }

    private suspend fun wake(store: WorkWakeStore) =
        checkNotNull(store.request(KEY, UUID.randomUUID().toString(), 1))

    private fun query() = WorkQuery.Builder.fromUniqueWorkNames(NAMES).build()

    private suspend fun snapshot(manager: WorkManager) = manager.getWorkInfosFlow(query()).first()

    private suspend fun waitFor(manager: WorkManager, ready: (List<WorkInfo>) -> Boolean) =
        withTimeout(20_000) { manager.getWorkInfosFlow(query()).first(ready) }

    private suspend fun finished(manager: WorkManager) =
        waitFor(manager) { it.all { work -> work.state.isFinished } }

    private fun inspectInputs() {
        val file = File(context.noBackupFilesDir, "androidx.work.workdb")
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
            database
                .rawQuery(
                    "SELECT input, worker_class_name, required_network_type, backoff_delay_duration FROM workspec WHERE id IN (SELECT work_spec_id FROM workname WHERE name IN (?,?))",
                    NAMES.toTypedArray(),
                )
                .use { rows ->
                    var count = 0
                    while (rows.moveToNext()) {
                        count++
                        check(
                            Data.fromByteArray(rows.getBlob(0)).keyValueMap ==
                                mapOf(SyncWorker.TASK_KEY to KEY)
                        )
                        check(rows.getString(1) == SyncWorker::class.java.name)
                        check(rows.getInt(2) == NetworkType.CONNECTED.ordinal)
                        check(rows.getLong(3) == 30_000L)
                    }
                    check(count in 1..2)
                }
            database.rawQuery("SELECT COUNT(*) FROM dependency", null).use { rows ->
                check(rows.moveToFirst() && rows.getInt(0) == 0)
            }
        }
    }

    private enum class Outcome {
        Complete,
        Retry,
        Fail,
    }

    private class Run(val wake: WorkWakeRecord) {
        val outcome = CompletableDeferred<Outcome>()
        val finished = CompletableDeferred<Unit>()
        @Volatile var acknowledged: Boolean? = null
    }

    private class ProbeTask(private val store: WorkWakeStore) : SyncTask {
        override val key = KEY
        val events = Channel<Run>(2)
        val seen = ConcurrentLinkedQueue<Run>()
        val active = AtomicInteger()
        val peak = AtomicInteger()

        suspend fun next() = withTimeout(20_000) { events.receive() }

        override suspend fun invoke(): SyncResult {
            val count = active.incrementAndGet()
            peak.updateAndGet { maxOf(it, count) }
            var captured: Run? = null
            try {
                check(count <= 2)
                val run = Run(checkNotNull(store.get(KEY)))
                captured = run
                check(run.wake.pending)
                seen += run
                events.send(run)
                return when (withTimeout(60_000) { run.outcome.await() }) {
                    Outcome.Complete -> {
                        run.acknowledged = store.acknowledge(KEY, run.wake.generation)
                        SyncResult.Complete
                    }
                    Outcome.Retry -> SyncResult.Retry()
                    Outcome.Fail ->
                        SyncResult.Blocked(
                            Failure(FailureKind.Unavailable, "Synthetic fixture failure")
                        )
                }
            } finally {
                active.decrementAndGet()
                captured?.finished?.complete(Unit)
            }
        }
    }

    private companion object {
        const val KEY = "proof.collapsed-recovery"
        val NAMES = (0..1).map { "$KEY:collapsed:$it" }
    }
}
