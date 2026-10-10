package dev.fajar.starter.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.fajar.starter.sync.domain.*

class SyncWorker(context: Context, parameters: WorkerParameters, private val task: SyncTask?) :
    CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val work = task ?: return Result.failure()
        return when (runSyncTask(work)) {
            SyncResult.Complete -> Result.success()
            is SyncResult.Retry -> Result.retry()
            is SyncResult.Blocked -> Result.failure()
        }
    }

    companion object {
        const val TASK_KEY = "sync_task"
        const val TAG = "starter-sync"
    }
}
