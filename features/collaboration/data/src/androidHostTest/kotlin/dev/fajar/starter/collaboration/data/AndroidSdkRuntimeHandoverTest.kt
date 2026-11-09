package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.repositories.handoverAndroidSdkRuntime
import dev.fajar.starter.common.result.*
import dev.fajar.starter.observability.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AndroidSdkRuntimeHandoverTest {
    @Test
    fun classifiedRejectionWithoutAResourceNeedsNoInventedException() = runTest {
        val rejected =
            Failure(
                FailureKind.Unauthorized,
                "Rejected",
                code = "client.collaboration_sdk_unauthorized",
            )
        val result =
            handoverAndroidSdkRuntime(map = { requireNotNull(it) }) { failed ->
                failed(rejected)
                null
            }
        assertEquals(AppResult.Failed(rejected), result)
    }

    @Test
    fun aRealStorageExceptionRemainsReported() = runTest {
        val failure = IllegalStateException("Fixture storage failure")
        val events = mutableListOf<Pair<Diagnostic, Exception?>>()
        Diagnostics.install { event, error -> events += event to error }
        try {
            val result = handoverAndroidSdkRuntime(map = { it }) { throw failure }
            assertEquals(FailureKind.Storage, assertIs<AppResult.Failed>(result).failure.kind)
            val reported = events.single { it.first.kind == DiagnosticKind.OperationFailed }
            assertEquals(DiagnosticArea.Storage, reported.first.area)
            assertSame(failure, reported.second)
        } finally {
            Diagnostics.install(ConsoleDiagnosticSink)
        }
    }

    @Test
    fun authRejectionSurvivesALaterCleanupFailure() = runTest {
        val rejected =
            Failure(
                FailureKind.Unauthorized,
                "Rejected",
                code = "client.collaboration_sdk_unauthorized",
            )
        val result =
            handoverAndroidSdkRuntime(map = { it }) { failed ->
                failed(rejected)
                try {
                    error("Fixture native failure")
                } finally {
                    failed(Failure(FailureKind.Storage, "Fixture cleanup failure"))
                    error("Fixture cleanup failure")
                }
            }
        assertEquals(AppResult.Failed(rejected), result)
    }

    @Test
    fun lateCancellationClosesTheAcquiredResourceAndStaysCancellation() = runTest {
        val f = runtimeLeaseFixture(StandardTestDispatcher(testScheduler))
        f.lease.finishAcquisition()
        val operation = async {
            handoverAndroidSdkRuntime(map = { it }) { _ ->
                currentCoroutineContext().cancel()
                f.lease
            }
        }
        assertFailsWith<CancellationException> { operation.await() }
        f.assertReleased()
        f.close()
    }

    @Test
    fun cancellationDuringMappingStillClosesTheAcquiredResource() = runTest {
        val f = runtimeLeaseFixture(StandardTestDispatcher(testScheduler))
        f.lease.finishAcquisition()
        val operation = async {
            handoverAndroidSdkRuntime(map = { throw CancellationException("Mapping cancelled") }) {
                f.lease
            }
        }
        assertFailsWith<CancellationException> { operation.await() }
        f.assertReleased()
        f.close()
    }

    @Test
    fun failedMappingDoesNotLeakTheAcquiredResource() = runTest {
        val f = runtimeLeaseFixture(StandardTestDispatcher(testScheduler))
        f.lease.finishAcquisition()
        val result =
            handoverAndroidSdkRuntime(map = { error("Fixture mapping failure") }) { f.lease }
        assertEquals(FailureKind.Storage, assertIs<AppResult.Failed>(result).failure.kind)
        f.assertReleased()
        f.close()
    }

    @Test
    fun cancellationIsNotReplacedByAnObservedAuthRejection() = runTest {
        val operation = async {
            handoverAndroidSdkRuntime(map = { it }) { failed ->
                failed(
                    Failure(
                        FailureKind.Unauthorized,
                        "Rejected",
                        code = "client.collaboration_sdk_unauthorized",
                    )
                )
                throw CancellationException("Fixture cancelled")
            }
        }
        assertFailsWith<CancellationException> { operation.await() }
    }
}
