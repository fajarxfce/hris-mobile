package dev.fajar.hris.collaboration.proof

import android.content.Context
import androidx.work.*
import dev.fajar.starter.sync.domain.*
import dev.fajar.starter.worker.*
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import org.json.JSONObject

/** Actual WorkManager migration and dispatch; no account, SDK, network or message content. */
internal class SyncTriggerProbe(private val context: Context, private val directory: File) {
    suspend fun run() {
        check(!File(directory, "workmanager-enabled.json").exists())
        check(!File(directory, "sync-trigger.json").exists())
        val triggers = Channel<SyncTrigger>(4)
        val task =
            object : SyncTask {
                override val key = "proof.sync-trigger"

                override suspend fun invoke(): SyncResult = error("Missing dispatch trigger")

                override suspend fun invoke(trigger: SyncTrigger): SyncResult {
                    check(triggers.trySend(trigger).isSuccess)
                    return SyncResult.Complete
                }
            }
        WorkManager.initialize(
            context,
            Configuration.Builder().setWorkerFactory(SyncWorkerFactory { listOf(task) }).build(),
        )
        val manager = WorkManager.getInstance(context)
        val periodicName = "${task.key}-periodic"
        val result = JSONObject()
        try {
            // A pre-upgrade periodic request has no distinguishing tag. UPDATE must preserve
            // its identity while installing the current dispatch contract.
            val old =
                PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                    .setInputData(workDataOf(SyncWorker.TASK_KEY to task.key))
                    .setInitialDelay(10, TimeUnit.SECONDS)
                    .addTag(SyncWorker.TAG)
                    .build()
            manager
                .enqueueUniquePeriodicWork(periodicName, ExistingPeriodicWorkPolicy.KEEP, old)
                .await()
            val before = manager.getWorkInfosForUniqueWorkFlow(periodicName).first().single()
            check(before.id == old.id && SyncWorker.PERIODIC_TAG !in before.tags)
            val scheduler = AndroidWorkScheduler(manager)
            scheduler.installPeriodic(setOf(task.key))
            val updated =
                withTimeout(15_000) {
                    manager
                        .getWorkInfosForUniqueWorkFlow(periodicName)
                        .first { rows ->
                            rows.size == 1 && SyncWorker.PERIODIC_TAG in rows.single().tags
                        }
                        .single()
                }
            check(updated.id == old.id)
            check(withTimeout(30_000) { triggers.receive() } == SyncTrigger.Scheduled)
            scheduler.enqueue(task.key)
            check(withTimeout(15_000) { triggers.receive() } == SyncTrigger.Requested)
            withTimeout(15_000) {
                manager.getWorkInfosForUniqueWorkFlow(task.key).first { rows ->
                    rows.isNotEmpty() && rows.all { it.state == WorkInfo.State.SUCCEEDED }
                }
            }
            result
                .put("periodicIdentityPreserved", true)
                .put("legacyPeriodicTagUpdated", true)
                .put("periodicDispatchScheduled", true)
                .put("oneTimeDispatchRequested", true)
        } finally {
            withContext(NonCancellable) {
                withTimeout(15_000) {
                    for (name in listOf(task.key, periodicName)) {
                        manager.cancelUniqueWork(name).await()
                        manager.getWorkInfosForUniqueWorkFlow(name).first { rows ->
                            rows.all { it.state.isFinished }
                        }
                    }
                }
                triggers.close()
            }
        }
        directory.mkdirs()
        result.put("cleanup", true).put("providerRequests", 0).put("messagesSent", 0)
        File(directory, "sync-trigger.json").writeText(result.toString())
    }
}
