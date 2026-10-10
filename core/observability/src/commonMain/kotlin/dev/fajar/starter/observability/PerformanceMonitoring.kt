package dev.fajar.starter.observability

import kotlinx.coroutines.flow.MutableStateFlow

/** The host owns the sink; each operation owns and finishes its returned native sample. */
fun interface PerformanceSink {
    fun start(operation: PerformanceOperation): PerformanceSample?
}

fun interface PerformanceSample {
    fun finish(outcome: PerformanceOutcome)
}

object PerformanceMonitoring {
    private val sink = MutableStateFlow<PerformanceSink>(PerformanceSink { null })

    fun install(value: PerformanceSink) {
        sink.value = value
    }

    fun start(operation: PerformanceOperation): PerformanceSample? =
        try {
            sink.value.start(operation)
        } catch (error: Exception) {
            Diagnostics.failure(DiagnosticArea.Application, error)
            null
        }
}
