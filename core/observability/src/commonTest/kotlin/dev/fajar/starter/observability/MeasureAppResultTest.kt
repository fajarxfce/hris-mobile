package dev.fajar.starter.observability

import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest

class MeasureAppResultTest {
    @Test
    fun successWithFalseOrNullAndTypedFailureKeepTheirExactResult() = runTest {
        val events = mutableListOf<Diagnostic>()
        Diagnostics.install(DiagnosticSink { event, _ -> events += event })
        try {
            val noChange = AppResult.Success(false)
            val absent = AppResult.Success(null)
            val failed = AppResult.Failed(Failure(FailureKind.Storage, "private failure"))
            for (original in listOf(noChange, absent, failed)) {
                assertSame(
                    original,
                    measureAppResult(PerformanceOperation.CommandPreparation) { original },
                )
            }
            assertEquals(
                listOf(
                    PerformanceOutcome.Succeeded,
                    PerformanceOutcome.Succeeded,
                    PerformanceOutcome.Failed,
                ),
                events.map { it.outcome },
            )
            assertTrue(events.none { encodeDiagnostic(it).contains("private failure") })
        } finally {
            Diagnostics.install(ConsoleDiagnosticSink)
        }
    }

    @Test
    fun thrownFailureAndCancellationAreNotConvertedToApplicationResults() = runTest {
        val events = mutableListOf<Diagnostic>()
        Diagnostics.install(DiagnosticSink { event, _ -> events += event })
        try {
            val failed = IllegalStateException("private exception")
            val received =
                assertFailsWith<IllegalStateException> {
                    measureAppResult<Unit>(PerformanceOperation.CommandQueue) { throw failed }
                }
            assertTrue(received === failed || received.cause === failed)
            val cancelled = CancellationException("private cancellation")
            val interruption =
                assertFailsWith<CancellationException> {
                    measureAppResult<Unit>(PerformanceOperation.CommandQueue) { throw cancelled }
                }
            assertTrue(interruption === cancelled || interruption.cause === cancelled)
            assertEquals(
                listOf(PerformanceOutcome.Failed, PerformanceOutcome.Cancelled),
                events.map { it.outcome },
            )
            assertTrue(events.none { encodeDiagnostic(it).contains("private") })
        } finally {
            Diagnostics.install(ConsoleDiagnosticSink)
        }
    }
}
