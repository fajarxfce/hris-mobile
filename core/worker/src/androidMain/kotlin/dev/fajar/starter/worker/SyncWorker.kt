package dev.fajar.starter.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.fajar.starter.sync.domain.*

class SyncWorker(context: Context, parameters: WorkerParameters, private val task: SyncTask?) :
    CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val work = task ?: return Result.failure()
        val trigger = if (PERIODIC_TAG in tags) SyncTrigger.Scheduled else SyncTrigger.Requested
        return when (runSyncTask(work, trigger)) {
            SyncResult.Complete -> Result.success()
            is SyncResult.Retry -> Result.retry()
            is SyncResult.Blocked -> Result.failure()
        }
    }

    companion object {
        const val TASK_KEY = "sync_task"
        const val TAG = "starter-sync"
        const val PERIODIC_TAG = "starter-sync-periodic"
    }
}
