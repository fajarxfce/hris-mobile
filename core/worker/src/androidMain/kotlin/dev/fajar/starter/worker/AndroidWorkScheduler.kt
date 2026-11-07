package dev.fajar.starter.worker

import androidx.work.*
import dev.fajar.starter.sync.data.datasources.WorkScheduler
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first

/** WorkManager persists only the task key; feature payloads remain in the transactional outbox. */
class AndroidWorkScheduler(
    private val workManager: WorkManager,
    collapsedKeys: Set<String> = emptySet(),
    private val onRequested: (String) -> Unit = {},
) : WorkScheduler {
    private val collapsed = CollapsedWorkGate(collapsedKeys)

    override suspend fun enqueue(key: String) {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        // The caller already persisted its command. The host may wake foreground execution
        // independently of an older WorkManager request waiting for backoff or constraints.
        onRequested(key)
        if (collapsed.contains(key)) {
            collapsed.enqueue(key, { unfinished(key) }, { enqueueRequest(key, it) })
        } else enqueueRequest(key, null)
    }

    private suspend fun unfinished(key: String): WorkQueueSnapshot {
        val query =
            WorkQuery.Builder.fromUniqueWorkNames((0..1).map { slotName(key, it) })
                .addStates(
                    listOf(WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED, WorkInfo.State.RUNNING)
                )
                .build()
        val jobs = workManager.getWorkInfosFlow(query).first()
        val occupied = jobs.map { job -> (0..1).single { slotTag(key, it) in job.tags } }.toSet()
        check(occupied.size == jobs.size)
        return WorkQueueSnapshot(
            occupiedSlots = occupied,
            waiting = jobs.any { it.state != WorkInfo.State.RUNNING },
        )
    }

    private fun slotName(key: String, slot: Int) = "$key:collapsed:$slot"

    private fun slotTag(key: String, slot: Int) = "${SyncWorker.TAG}:${slotName(key, slot)}"

    private suspend fun enqueueRequest(key: String, slot: Int?) {
        val request =
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setInputData(workDataOf(SyncWorker.TASK_KEY to key))
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(SyncWorker.TAG)
                .apply { if (slot != null) addTag(slotTag(key, slot)) }
                .build()
        if (slot == null) {
            // Ordinary tasks retain their existing finishing-worker race protection.
            workManager
                .enqueueUniqueWork(key, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
                .await()
        } else {
            // This slot was vacant AFTER the durable write. A competing fill therefore also starts
            // after that write. A running slot is never reused; two names bound unfinished work.
            workManager
                .enqueueUniqueWork(slotName(key, slot), ExistingWorkPolicy.KEEP, request)
                .await()
        }
    }

    fun installPeriodic(keys: Set<String>) {
        keys.forEach { key ->
            val request =
                PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                    .setInputData(workDataOf(SyncWorker.TASK_KEY to key))
                    .setConstraints(
                        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                    )
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .addTag(SyncWorker.TAG)
                    .build()
            workManager.enqueueUniquePeriodicWork(
                "$key-periodic",
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
