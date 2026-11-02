package dev.fajar.hris.collaboration.proof

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Process
import androidx.work.*
import dev.fajar.hris.collaboration.proof.CompanyRecoveryProofOwner.Companion.id
import dev.fajar.hris.collaboration.proof.CompanyRecoveryProofOwner.Companion.success
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.usecases.RequestCollaborationRecovery
import dev.fajar.starter.sync.data.repositories.PlatformSyncScheduleRepository
import dev.fajar.starter.sync.domain.*
import dev.fajar.starter.worker.AndroidWorkScheduler
import dev.fajar.starter.worker.SyncWorker
import dev.fajar.starter.worker.SyncWorkerFactory
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

/** Actual WorkManager/Room/Keystore consumer proof. Directory and company work are synthetic. */
internal class CompanyRecoveryProbe(private val context: Context, private val directory: File) {
    suspend fun stage() {
        val owner = CompanyRecoveryProofOwner(context)
        try {
            owner.activate()
            val wake = success(owner.wakes.request())
            val begun =
                success(
                        owner.update(success(owner.read()), CollaborationRecoveryChange.Begin(wake))
                    )
                    .snapshot
            val attempted =
                success(owner.update(begun, CollaborationRecoveryChange.Attempt(id(1)))).snapshot
            check(attempted.progress?.sweep?.inFlightCompanyId == id(1))
            directory.mkdirs()
            File(directory, "company-recovery-stage.json")
                .writeText(
                    JSONObject()
                        .put("generation", wake.generation)
                        .put("processId", Process.myPid())
                        .put("attemptPersisted", true)
                        .toString()
                )
        } finally {
            owner.close()
        }
    }

    suspend fun run(resuming: Boolean = false) {
        check(!File(directory, "workmanager-enabled.json").exists())
        val owner = CompanyRecoveryProofOwner(context)
        val closing = AtomicBoolean()
        val counts = ProbeCounts()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        lateinit var manager: WorkManager
        val scheduler by lazy { AndroidWorkScheduler(manager, setOf(KEY)) }
        val schedules = PlatformSyncScheduleRepository(lazy { scheduler })
        val worker =
            owner.worker(schedules) { grant ->
                val count = counts.callbacks.incrementAndGet()
                counts.callbackMaximum.updateAndGet { maxOf(it, count) }
                try {
                    check(count == 1)
                    check(counts.calls.size < 128)
                    counts.calls += grant.owner.companyId
                    if (!resuming && counts.calls.size == 1) {
                        entered.complete(Unit)
                        release.await()
                    }
                    SyncResult.Complete
                } finally {
                    counts.callbacks.decrementAndGet()
                }
            }
        val task =
            object : SyncTask {
                override val key = KEY

                override suspend fun invoke(): SyncResult {
                    val count = counts.active.incrementAndGet()
                    counts.maximum.updateAndGet { maxOf(it, count) }
                    try {
                        check(count <= 2)
                        if (closing.get()) return SyncResult.Complete
                        return worker().also {
                            check(counts.results.size < 128)
                            counts.results += it
                        }
                    } finally {
                        counts.active.decrementAndGet()
                    }
                }
            }
        val checks = JSONObject()
        var initialized = false
        try {
            owner.activate()
            WorkManager.initialize(
                context,
                Configuration.Builder().setWorkerFactory(SyncWorkerFactory { listOf(task) }).build(),
            )
            manager = WorkManager.getInstance(context)
            initialized = true
            val request = RequestCollaborationRecovery(owner.wakes, schedules)
            val expectedAcknowledgment =
                if (resuming) {
                    resume(owner, scheduler, counts, checks)
                } else {
                    burst(owner, request, counts, entered, release, checks)
                }
            withTimeout(20_000) {
                manager.getWorkInfosFlow(query()).first {
                    it.isNotEmpty() && it.all { info -> info.state.isFinished }
                }
            }
            saveProgress("finished", owner, expectedAcknowledgment, counts)
            check(expectedAcknowledgment in owner.acknowledged)
            check(counts.active.get() == 0 && counts.callbacks.get() == 0)
            check(success(owner.wakes.pending()) == null)
            check(success(owner.read()).progress?.sweep == null)
            check(owner.states.subscriptionCount.value == 0)
            check(counts.callbackMaximum.get() == 1)
            if (!resuming) check(counts.results.none { it is SyncResult.Retry })
            checks
                .put("maxSimultaneousOsTasks", counts.maximum.get())
                .put("maxCompanyProcessors", counts.callbackMaximum.get())
                .put("companyAttempts", counts.calls.size)
                .put("taskCompletions", counts.results.size)
                .put("retryResults", counts.results.count { it is SyncResult.Retry })
                .put("companyOrder", JSONArray(counts.calls.map { it.takeLast(12).toInt() }))
        } finally {
            withContext(NonCancellable) {
                withTimeout(15_000) {
                    closing.set(true)
                    release.complete(Unit)
                    if (initialized) {
                        for (name in NAMES) withContext(Dispatchers.IO) {
                            manager.cancelUniqueWork(name).result.get()
                        }
                        manager.getWorkInfosFlow(query()).first {
                            it.all { info -> info.state.isFinished }
                        }
                        while (counts.active.get() != 0 || counts.callbacks.get() != 0) delay(20)
                    }
                    owner.close()
                }
            }
        }
        checks.put("cleanup", true).put("sdkOrProviderAccepted", false)
        directory.mkdirs()
        File(directory, if (resuming) "company-recovery-resume.json" else "company-recovery.json")
            .writeText(checks.toString())
    }

    private suspend fun resume(
        owner: CompanyRecoveryProofOwner,
        scheduler: AndroidWorkScheduler,
        counts: ProbeCounts,
        checks: JSONObject,
    ): String {
        val staged = JSONObject(File(directory, "company-recovery-stage.json").readText())
        check(staged.getInt("processId") != Process.myPid())
        val generation = staged.getString("generation")
        check(success(owner.wakes.pending())?.generation == generation)
        check(success(owner.read()).progress?.sweep?.inFlightCompanyId == id(1))
        saveProgress("resume-start", owner, generation, counts)
        scheduler.enqueue(KEY)
        withTimeout(100_000) { while (success(owner.wakes.pending()) != null) delay(100) }
        check(counts.calls.toList().take(6) == (2..7).map(::id))
        check(counts.calls.toList().drop(6).take(7) == (1..7).map(::id))
        check(counts.results.any { it is SyncResult.Retry })
        checks
            .put("processDeathRepair", true)
            .put("sameWakeGeneration", true)
            .put("uncertainAttemptRetriedAfterLaterCompanies", true)
        return generation
    }

    private suspend fun burst(
        owner: CompanyRecoveryProofOwner,
        request: RequestCollaborationRecovery,
        counts: ProbeCounts,
        entered: CompletableDeferred<Unit>,
        release: CompletableDeferred<Unit>,
        checks: JSONObject,
    ): String {
        check(success(owner.wakes.pending()) == null)
        success(request())
        withTimeout(20_000) { entered.await() }
        val initial = success(owner.wakes.pending())
        success(request())
        withTimeout(20_000) { while (counts.active.get() != 2) delay(20) }
        val burst = coroutineScope { (1..40).map { async { request() } }.awaitAll() }
        check(burst.all { it is dev.fajar.starter.common.result.AppResult.Failed })
        val newest = success(owner.wakes.pending())
        check(newest != null && newest != initial)
        check(counts.calls.toList() == listOf(id(1)))
        inspectInputs()
        saveProgress("burst-held", owner, newest.generation, counts)
        release.complete(Unit)
        withTimeout(80_000) { while (success(owner.wakes.pending()) != null) delay(100) }
        check(counts.calls.toList().take(7) == (1..7).map(::id))
        check(counts.calls.toList().drop(7).take(7) == (1..7).map(::id))
        checks
            .put("burstRequests", burst.size)
            .put("rejectedEnqueuesRetainNewestWake", true)
            .put("oldSweepDidNotEraseNewWake", true)
        return newest.generation
    }

    private suspend fun saveProgress(
        phase: String,
        owner: CompanyRecoveryProofOwner,
        expected: String,
        counts: ProbeCounts,
    ) {
        directory.mkdirs()
        File(directory, "company-recovery-progress.json")
            .writeText(
                JSONObject()
                    .put("phase", phase)
                    .put("expectedAcknowledgment", expected)
                    .put("acknowledged", JSONArray(owner.acknowledged.toList().take(64)))
                    .put(
                        "pendingGeneration",
                        success(owner.wakes.pending())?.generation ?: JSONObject.NULL,
                    )
                    .put("activeTasks", counts.active.get())
                    .put("companyAttempts", counts.calls.size)
                    .put("completedTasks", counts.results.size)
                    .toString()
            )
    }

    private class ProbeCounts {
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val callbacks = AtomicInteger()
        val callbackMaximum = AtomicInteger()
        val calls = ConcurrentLinkedQueue<String>()
        val results = ConcurrentLinkedQueue<SyncResult>()
    }

    private fun query() = WorkQuery.Builder.fromUniqueWorkNames(NAMES).build()

    private fun inspectInputs() {
        val file = File(context.noBackupFilesDir, "androidx.work.workdb")
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
            database
                .rawQuery(
                    "SELECT input, required_network_type, backoff_delay_duration FROM workspec WHERE id IN (SELECT work_spec_id FROM workname WHERE name IN (?,?))",
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
                        check(rows.getInt(1) == NetworkType.CONNECTED.ordinal)
                        check(rows.getLong(2) == 30_000L)
                    }
                    check(count == 2)
                }
        }
    }

    private companion object {
        const val KEY = CollaborationRecoveryWake.WORK_KEY
        val NAMES = listOf("$KEY:collapsed:0", "$KEY:collapsed:1")
    }
}
