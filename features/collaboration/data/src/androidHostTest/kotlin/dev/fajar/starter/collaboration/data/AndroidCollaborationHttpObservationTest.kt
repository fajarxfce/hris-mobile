@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.datasources.AndroidCollaborationHttpObservation
import dev.fajar.starter.common.result.AppResult
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.matrix.rustcomponents.sdk.Client

/** Runtime ownership with inert native clients. The inspector repository owns actual JNI tests. */
class AndroidCollaborationHttpObservationTest {
    @Test
    fun observationStartsDuringAcquisitionAndStopsBeforeClientRelease() = runTest {
        var observed: Client? = null
        var stopped = false
        val f =
            runtimeLeaseFixture(
                StandardTestDispatcher(testScheduler),
                httpObservation =
                    AndroidCollaborationHttpObservation { client ->
                        observed = client
                        try {
                            awaitCancellation()
                        } finally {
                            stopped = true
                        }
                    },
            )
        try {
            assertNull(observed)
            f.lease.startHttpObservation(f.httpObservation)
            assertSame(f.lease.nativeClient, observed)
            val closing = async { f.lease.close() }
            runCurrent()
            assertFalse(stopped)
            assertFalse(closing.isCompleted)
            assertTrue(f.calls.isEmpty())
            f.beforePause = { assertTrue(stopped) }
            f.lease.finishAcquisition()
            runCurrent()
            assertEquals(AppResult.Success(Unit), closing.await())
            assertTrue(stopped)
            f.assertReleased()
        } finally {
            f.lease.finishAcquisition()
            f.close()
        }
    }

    @Test
    fun startingTwiceCannotReplaceTheActiveSubscription() = runTest {
        var starts = 0
        val f =
            runtimeLeaseFixture(
                StandardTestDispatcher(testScheduler),
                httpObservation =
                    AndroidCollaborationHttpObservation {
                        starts++
                        awaitCancellation()
                    },
            )
        try {
            repeat(3) { f.lease.startHttpObservation(f.httpObservation) }
            assertEquals(1, starts)
            f.lease.completeAcquisition()
            assertEquals(AppResult.Success(Unit), f.lease.publish())
        } finally {
            f.lease.finishAcquisition()
            f.close()
        }
        f.lease.startHttpObservation(f.httpObservation)
        assertEquals(1, starts)
    }

    @Test
    fun aClosingOwnerNeverOpensANewSubscription() = runTest {
        var starts = 0
        val f =
            runtimeLeaseFixture(
                StandardTestDispatcher(testScheduler),
                httpObservation = AndroidCollaborationHttpObservation { starts++ },
            )
        try {
            f.lease.requestClose()
            f.lease.startHttpObservation(f.httpObservation)
            f.lease.finishAcquisition()
            assertEquals(0, starts)
        } finally {
            f.lease.finishAcquisition()
            f.close()
        }
        f.lease.startHttpObservation(f.httpObservation)
        assertEquals(0, starts)
    }

    @Test
    fun optionalSourceFailureOrCancellationDoesNotChangeReadiness() = runTest {
        for (failure in
            listOf(IllegalStateException("fixture"), CancellationException("fixture"))) {
            val f =
                runtimeLeaseFixture(
                    StandardTestDispatcher(testScheduler),
                    httpObservation = AndroidCollaborationHttpObservation { throw failure },
                )
            try {
                f.lease.startHttpObservation(f.httpObservation)
                f.lease.completeAcquisition()
                runCurrent()
                assertEquals(AppResult.Success(Unit), f.lease.publish())
                assertTrue(f.lease.availableForEntry)
            } finally {
                f.lease.finishAcquisition()
                f.close()
            }
            f.assertReleased()
        }
    }

    @Test
    fun pendingObserverCleanupKeepsTheNativeClientOwned() = runTest {
        val cleaning = CompletableDeferred<Unit>()
        val released = CompletableDeferred<Unit>()
        val f =
            runtimeLeaseFixture(
                StandardTestDispatcher(testScheduler),
                httpObservation =
                    AndroidCollaborationHttpObservation {
                        try {
                            awaitCancellation()
                        } finally {
                            withContext(NonCancellable) {
                                cleaning.complete(Unit)
                                released.await()
                            }
                        }
                    },
            )
        try {
            f.lease.startHttpObservation(f.httpObservation)
            f.lease.completeAcquisition()
            val closing = async { f.lease.close() }
            runCurrent()
            assertTrue(cleaning.isCompleted)
            assertFalse(closing.isCompleted)
            assertFalse("client.close" in f.calls)
            assertFalse("directory.close" in f.calls)
            released.complete(Unit)
            runCurrent()
            assertEquals(AppResult.Success(Unit), closing.await())
            f.assertReleased()
        } finally {
            released.complete(Unit)
            f.lease.finishAcquisition()
            f.close()
        }
    }

    @Test
    fun cleanupFailureCannotRetainTheClientOrBlockItsNormalShutdown() = runTest {
        val f =
            runtimeLeaseFixture(
                StandardTestDispatcher(testScheduler),
                httpObservation =
                    AndroidCollaborationHttpObservation {
                        try {
                            awaitCancellation()
                        } finally {
                            error("fixture cleanup")
                        }
                    },
            )
        try {
            f.lease.startHttpObservation(f.httpObservation)
            f.lease.completeAcquisition()
        } finally {
            f.lease.finishAcquisition()
            f.close()
        }
        f.assertReleased()
    }

    @Test
    fun closingOneClientDoesNotCancelAnotherClientsObservation() = runTest {
        val active = mutableSetOf<Client>()
        val observation = AndroidCollaborationHttpObservation { client ->
            active += client
            try {
                awaitCancellation()
            } finally {
                active -= client
            }
        }
        val first = runtimeLeaseFixture(StandardTestDispatcher(testScheduler), observation)
        val second = runtimeLeaseFixture(StandardTestDispatcher(testScheduler), observation)
        try {
            first.lease.startHttpObservation(first.httpObservation)
            second.lease.startHttpObservation(second.httpObservation)
            first.lease.completeAcquisition()
            second.lease.completeAcquisition()
            assertEquals(setOf(first.lease.nativeClient, second.lease.nativeClient), active)
            first.close()
            assertEquals(setOf(second.lease.nativeClient), active)
            assertEquals(AppResult.Success(Unit), second.lease.publish())
            second.close()
            assertTrue(active.isEmpty())
        } finally {
            first.lease.finishAcquisition()
            second.lease.finishAcquisition()
            first.close()
            second.close()
        }
    }
}
