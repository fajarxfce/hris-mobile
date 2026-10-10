package dev.fajar.starter.worker

import androidx.work.*
import dev.fajar.starter.sync.data.datasources.WorkScheduler
import java.util.concurrent.TimeUnit

/** WorkManager persists only the task key; feature payloads remain in the transactional outbox. */
class AndroidWorkScheduler(private val workManager: WorkManager) : WorkScheduler {
    override suspend fun enqueue(key: String) {
        val request =
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setInputData(workDataOf(SyncWorker.TASK_KEY to key))
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(SyncWorker.TAG)
                .build()
        // KEEP can lose a wake-up when a local write races with a finishing worker.
        workManager.enqueueUniqueWork(key, ExistingWorkPolicy.APPEND_OR_REPLACE, request).await()
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
