package dev.fajar.starter.observability

import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Records success/failure/cancellation once; native traces stop even if the operation throws. */
suspend fun <T> measureOperation(
    name: PerformanceOperation,
    classify: (T) -> PerformanceOutcome = { PerformanceOutcome.Succeeded },
    httpStatus: (T) -> Int? = { null },
    operation: suspend () -> T,
): T {
    val trace = OperationTrace.create(currentCoroutineContext()[OperationTrace])
    return withContext(trace) {
        val started = TimeSource.Monotonic.markNow()
        val sample = PerformanceMonitoring.start(name)
        var outcome = PerformanceOutcome.Failed
        var status: Int? = null
        try {
            val result = operation()
            currentCoroutineContext().ensureActive()
            status = httpStatus(result)
            outcome = classify(result)
            result
        } catch (cancelled: CancellationException) {
            outcome = PerformanceOutcome.Cancelled
            throw cancelled
        } finally {
            try {
                sample?.finish(outcome)
            } catch (error: Exception) {
                Diagnostics.failure(DiagnosticArea.Application, error)
            }
            Diagnostics.record(
                Diagnostic(
                    area =
                        if (name == PerformanceOperation.HttpRequest) DiagnosticArea.Network
                        else DiagnosticArea.Application,
                    kind = DiagnosticKind.OperationCompleted,
                    httpStatus = status,
                    durationMillis = started.elapsedNow().inWholeMilliseconds,
                    operation = name,
                    outcome = outcome,
                    traceId = trace.traceId,
                    spanId = trace.spanId,
                )
            )
        }
    }
}
