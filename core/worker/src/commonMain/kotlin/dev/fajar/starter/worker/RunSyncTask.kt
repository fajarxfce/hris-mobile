package dev.fajar.starter.worker

import dev.fajar.starter.common.result.*
import dev.fajar.starter.observability.DiagnosticArea
import dev.fajar.starter.observability.Diagnostics
import dev.fajar.starter.observability.PerformanceOperation
import dev.fajar.starter.observability.PerformanceOutcome
import dev.fajar.starter.observability.measureOperation
import dev.fajar.starter.sync.domain.*
import kotlinx.coroutines.*

/** Last-resort runtime boundary for a faulty task; expected failures come from repositories. */
suspend fun runSyncTask(
    task: SyncTask,
    onException: (Exception) -> Unit = { Diagnostics.failure(DiagnosticArea.Worker, it) },
): SyncResult =
    measureOperation(
        PerformanceOperation.Sync,
        classify = {
            if (it == SyncResult.Complete || it is SyncResult.Retry && it.failure == null)
                PerformanceOutcome.Succeeded
            else PerformanceOutcome.Failed
        },
    ) {
        try {
            currentCoroutineContext().ensureActive()
            val result = task()
            currentCoroutineContext().ensureActive()
            result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            onException(error)
            SyncResult.Blocked(Failure(FailureKind.Unexpected, "Sync could not be completed."))
        }
    }
