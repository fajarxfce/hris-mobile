package dev.fajar.starter.observability

import dev.fajar.starter.common.result.AppResult

/** Observe the result boundary without changing its value, failure or cancellation semantics. */
suspend fun <T> measureAppResult(
    name: PerformanceOperation,
    operation: suspend () -> AppResult<T>,
): AppResult<T> =
    measureOperation(
        name,
        classify = {
            if (it is AppResult.Success) PerformanceOutcome.Succeeded else PerformanceOutcome.Failed
        },
        operation = operation,
    )
