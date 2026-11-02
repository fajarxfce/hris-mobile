@file:OptIn(
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    kotlin.time.ExperimentalTime::class,
)

package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.repositories.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class AndroidCollaborationBackgroundRuntimeTest {
    @Test
    fun timerExpiryInvalidatesTheReferenceWithoutClosingTheForegroundClient() = runTest {
        val f = fixture(foreground = true)
        try {
            assertEquals(AppResult.Success(Unit), f.borrowed.publish())
            runCurrent()
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(
                "client.collaboration_revalidation_required",
                f.borrowed.interruptions.value?.code,
            )
            assertTrue(f.native.lease.availableForEntry)
            assertTrue(f.native.calls.isEmpty())
            assertEquals(AppResult.Success(Unit), f.borrowed.close())
            assertTrue(f.native.lease.availableForEntry)
        } finally {
            f.close()
        }
        f.native.assertReleased()
    }

    @Test
    fun lastBackgroundReferenceExpiryDrainsItsNativeClientAndStopsItsTimer() = runTest {
        val f = fixture()
        try {
            assertEquals(AppResult.Success(Unit), f.borrowed.publish())
            runCurrent()
            advanceTimeBy(10_000)
            runCurrent()
            assertFalse(f.native.lease.availableForEntry)
            f.native.assertReleased()
            repeat(2) { assertEquals(AppResult.Success(Unit), f.borrowed.close()) }
        } finally {
            f.close()
        }
        f.native.assertReleased()
    }

    @Test
    fun elapsedMonotonicDeadlineCannotPublishBeforeTheTimerGetsScheduled() = runTest {
        val f = fixture()
        try {
            f.time += 10.seconds
            assertIs<AppResult.Failed>(f.borrowed.publish())
            assertFalse(f.native.lease.availableForEntry)
            runCurrent()
            f.native.assertReleased()
        } finally {
            f.close()
        }
    }

    @Test
    fun parentInvalidationInterruptsBorrowAndDrainsItWithoutWaitingForItsDeadline() = runTest {
        val f = fixture()
        try {
            assertEquals(AppResult.Success(Unit), f.borrowed.publish())
            f.registry.update(CollaborationRuntimeRetention(null, null))
            assertNotNull(f.borrowed.interruptions.value)
            assertFalse(f.native.lease.availableForEntry)
            runCurrent()
            assertEquals(0L, testScheduler.currentTime)
            f.native.assertReleased()
        } finally {
            f.close()
        }
    }

    @Test
    fun callerCancellationStillReleasesItsReferenceAndNativeOwner() = runTest {
        val f = fixture()
        try {
            assertEquals(AppResult.Success(Unit), f.borrowed.publish())
            val closing = launch {
                currentCoroutineContext().cancel()
                f.borrowed.close()
            }
            runCurrent()
            closing.join()
            assertTrue(closing.isCancelled)
            f.native.assertReleased()
        } finally {
            f.close()
        }
    }

    @Test
    fun failedDrainRetainsTheReservationUntilAnExplicitCloseRetryFinishes() = runTest {
        val f = fixture()
        try {
            f.native.beforePause = { error("Controlled pause failure") }
            assertIs<AppResult.Failed>(f.borrowed.close())
            assertIs<AppResult.Failed>(
                f.registry.reserveBackground("store", setupOwner, SETUP_DEVICE)
            )
            assertFalse("directory.close" in f.native.calls)
            f.native.beforePause = {}
            assertEquals(AppResult.Success(Unit), f.borrowed.close())
            assertEquals(1, f.native.calls.count { it == "directory.close" })
        } finally {
            f.native.beforePause = {}
            f.close()
        }
    }

    private suspend fun TestScope.fixture(foreground: Boolean = false): BackgroundFixture {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val registry =
            SdkRuntimeRegistry<AndroidCollaborationRuntimeLease>(
                { it.availableForEntry && it.credentialFailures.value == null },
                { it.requestClose() },
            )
        val parent =
            CollaborationNativeParent(
                setupOwner.sessionId,
                setupOwner.audience,
                setupOwner.accountId,
                setupOwner.nativeSessionId,
            )
        registry.update(CollaborationRuntimeRetention(parent, if (foreground) setupOwner else null))
        val existing =
            if (foreground)
                assertIs<
                        AppResult.Success<SdkRuntimeReservation<AndroidCollaborationRuntimeLease>>
                    >(
                        registry.reserveForeground("store", setupOwner, SETUP_DEVICE)
                    )
                    .value
            else null
        val pending =
            if (!foreground)
                assertIs<AppResult.Success<SdkRuntimeBorrow<AndroidCollaborationRuntimeLease>>>(
                        registry.reserveBackground("store", setupOwner, SETUP_DEVICE)
                    )
                    .value
            else null
        val reservation = existing ?: checkNotNull(pending).reservation
        val native = runtimeLeaseFixture(dispatcher) { registry.released(reservation) }
        registry.attach(reservation, native.lease)
        native.lease.completeAcquisition()
        if (foreground) assertEquals(AppResult.Success(Unit), native.lease.publish())
        val reference =
            pending
                ?: assertIs<AppResult.Success<SdkRuntimeBorrow<AndroidCollaborationRuntimeLease>>>(
                        registry.reserveBackground("store", setupOwner, SETUP_DEVICE)
                    )
                    .value
        val time = TestTimeSource()
        val borrowed =
            AndroidCollaborationBackgroundRuntimeLease(
                registry,
                reference,
                time.markNow() + 10.seconds,
                dispatcher,
            )
        return BackgroundFixture(native, registry, borrowed, time)
    }
}

private class BackgroundFixture(
    val native: RuntimeLeaseFixture,
    val registry: SdkRuntimeRegistry<AndroidCollaborationRuntimeLease>,
    val borrowed: AndroidCollaborationBackgroundRuntimeLease,
    val time: TestTimeSource,
) {
    suspend fun close() {
        try {
            borrowed.close()
        } finally {
            native.close()
        }
    }
}
