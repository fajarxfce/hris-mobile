package dev.fajar.starter.worker

import android.content.Context
import androidx.work.*
import dev.fajar.starter.sync.domain.SyncTask

class SyncWorkerFactory(private val tasks: () -> List<SyncTask>) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? {
        if (workerClassName != SyncWorker::class.java.name) return null
        val key = workerParameters.inputData.getString(SyncWorker.TASK_KEY)
        return SyncWorker(appContext, workerParameters, tasks().singleOrNull { it.key == key })
    }
}
