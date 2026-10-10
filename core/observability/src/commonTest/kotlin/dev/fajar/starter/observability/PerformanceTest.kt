package dev.fajar.starter.observability

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class PerformanceTest {
    private val events = mutableListOf<Diagnostic>()
    private val samples = mutableListOf<PerformanceOutcome>()

    @BeforeTest
    fun before() {
        Diagnostics.install(DiagnosticSink { event, _ -> events += event })
        PerformanceMonitoring.install(PerformanceSink { PerformanceSample { samples += it } })
    }

    @AfterTest
    fun after() {
        PerformanceMonitoring.install(PerformanceSink { null })
        Diagnostics.install(ConsoleDiagnosticSink)
    }

    @Test
    fun cancellationClosesTheSampleAndPreservesTheOriginalCancellation() = runTest {
        val cancelled = CancellationException("private")
        val error =
            assertFailsWith<CancellationException> {
                measureOperation(PerformanceOperation.Sync) { throw cancelled }
            }
        assertTrue(error === cancelled || error.cause === cancelled)
        assertEquals(listOf(PerformanceOutcome.Cancelled), samples)
        assertEquals(PerformanceOutcome.Cancelled, events.single().outcome)
        assertFalse(encodeDiagnostic(events.single()).contains("private"))
    }

    @Test
    fun nestedOperationsShareATraceAndSeparateRootsRemainIndependent() = runTest {
        val root =
            measureOperation(PerformanceOperation.Sync) {
                val parent = checkNotNull(currentCoroutineContext()[OperationTrace])
                measureOperation(PerformanceOperation.HttpRequest) {
                    val child = checkNotNull(currentCoroutineContext()[OperationTrace])
                    assertEquals(parent.traceId, child.traceId)
                    assertNotEquals(parent.spanId, child.spanId)
                }
                parent
            }
        val other =
            async {
                    measureOperation(PerformanceOperation.Sync) {
                        currentCoroutineContext()[OperationTrace]
                    }
                }
                .await()
        assertNotEquals(root.traceId, other?.traceId)
        assertNull(currentCoroutineContext()[OperationTrace])
        assertEquals(3, samples.size)
    }

    @Test
    fun failingTelemetryCannotReplaceAResultOrMaskAnOperationFailure() = runTest {
        PerformanceMonitoring.install(
            PerformanceSink { PerformanceSample { error("sink failure") } }
        )
        assertEquals(42, measureOperation(PerformanceOperation.Sync) { 42 })
        val original = IllegalStateException("private operation")
        val failure =
            assertFailsWith<IllegalStateException> {
                measureOperation(PerformanceOperation.Sync) { throw original }
            }
        assertTrue(failure === original || failure.cause === original)
        assertEquals(PerformanceOutcome.Failed, events.last().outcome)
        PerformanceMonitoring.install(PerformanceSink { error("start failed") })
        assertEquals(7, measureOperation(PerformanceOperation.Sync) { 7 })
    }
}
