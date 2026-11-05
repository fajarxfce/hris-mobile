package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.repositories.AndroidCollaborationRuntimeLease
import dev.fajar.starter.collaboration.data.repositories.handoverAndroidSdkRuntime
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AndroidSdkRuntimeHandoverTest {
    @Test
    fun authRejectionSurvivesALaterCleanupFailure() = runTest {
        val rejected =
            Failure(
                FailureKind.Unauthorized,
                "Rejected",
                code = "client.collaboration_sdk_unauthorized",
            )
        val result =
            handoverAndroidSdkRuntime<AndroidCollaborationRuntimeLease?> { failed ->
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
            handoverAndroidSdkRuntime { _ ->
                currentCoroutineContext().cancel()
                f.lease
            }
        }
        assertFailsWith<CancellationException> { operation.await() }
        f.assertReleased()
        f.close()
    }

    @Test
    fun cancellationIsNotReplacedByAnObservedAuthRejection() = runTest {
        val operation = async {
            handoverAndroidSdkRuntime<AndroidCollaborationRuntimeLease?> { failed ->
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
