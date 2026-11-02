package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class ReopenCollaborationHistoryTest {
    @Test
    fun capacityRecoveryAdmitsEachBoundaryWithoutBootstrappingSyncOrAcquisition() = runTest {
        val f = HistoryReopeningFixture()
        assertEquals(f.result(CollaborationHistoryRuntimePreparation.Reopened), f.reopen())
        assertEquals(
            listOf("get", "room", "prepare", "get", "room", "restore", "get", "room", "publish"),
            f.events,
        )
        assertEquals(3, f.local.setup.calls)
        assertEquals(0, f.closes)
        assertEquals(0, f.delivery.acquisitions)
    }

    @Test
    fun retainedFocusAndFreshCapacityLeaveTheExistingRuntimeInPlace() = runTest {
        val f = HistoryReopeningFixture()
        f.prepared = AppResult.Success(false)
        assertEquals(f.result(CollaborationHistoryRuntimePreparation.Available), f.reopen())
        assertEquals(listOf("get", "room", "prepare"), f.events)
        assertEquals(0, f.closes)
    }

    @Test
    fun idleMissingStoreRequiresSetupWithoutIssuingANewIdentity() = runTest {
        val f = HistoryReopeningFixture()
        f.restored = AppResult.Success(null)
        assertEquals(f.result(CollaborationHistoryRuntimePreparation.SetupRequired), f.reopen())
        assertEquals(1, f.events.count { it == "restore" })
        assertEquals(0, f.closes)
    }

    @Test
    fun blockedExpiredOrUnreadableAdmissionNeverClosesTheRuntime() = runTest {
        for (scenario in 0..2) {
            val f = HistoryReopeningFixture()
            when (scenario) {
                0 -> f.local.gate.value = CollaborationListAdmission("blocked", f.local.denied)
                1 -> f.local.setup.now += 3.hours
                else -> f.local.gateFailure = f.local.storage
            }
            assertIs<AppResult.Failed>(f.reopen())
            assertTrue(f.events.isEmpty())
        }
    }

    @Test
    fun aBusyWriterOrUnfinishedCloseIsReturnedWithoutRestoreOrRetry() = runTest {
        for (code in
            listOf("client.collaboration_queue_busy", "client.collaboration_close_pending")) {
            val f = HistoryReopeningFixture()
            f.prepared =
                AppResult.Failed(Failure(FailureKind.Unavailable, "Fixture pending", code = code))
            assertEquals(
                assertIs<AppResult.Failed>(f.prepared).failure,
                assertIs<AppResult.Failed>(f.reopen()).failure,
            )
            assertEquals(listOf("get", "room", "prepare"), f.events)
            assertEquals(0, f.closes)
        }
    }

    @Test
    fun ownerReplacementAndConfirmedDenialDuringClosePreventRestore() = runTest {
        for (denied in listOf(false, true)) {
            val f = HistoryReopeningFixture()
            f.afterPrepare = {
                if (denied)
                    f.local.gate.value = CollaborationListAdmission("blocked", f.local.denied)
                else f.local.setup.session = null
            }
            assertIs<AppResult.Failed>(f.reopen())
            assertFalse("restore" in f.events)
        }
    }

    @Test
    fun liveRevocationOrRoomReplacementAfterClosePreventsRestore() = runTest {
        for (denied in listOf(false, true)) {
            val f = HistoryReopeningFixture()
            f.afterPrepare = {
                if (denied) f.delivery.control.remoteFailure = f.local.denied
                else
                    f.delivery.control.room =
                        f.delivery.control.room.copy(matrixRoomId = "!replacement:test")
            }
            assertIs<AppResult.Failed>(f.reopen())
            assertFalse("restore" in f.events)
            if (denied) assertEquals(f.local.denied, f.local.gate.value.denial)
        }
    }

    @Test
    fun lateRestoredOwnerDenialAndRoomReplacementCloseOnlyTheNewLease() = runTest {
        for (scenario in 0..2) {
            val f = HistoryReopeningFixture()
            f.afterRestore = {
                when (scenario) {
                    0 -> f.local.setup.session = null
                    1 -> f.delivery.control.remoteFailure = f.local.denied
                    else ->
                        f.delivery.control.room =
                            f.delivery.control.room.copy(matrixRoomId = "!replacement:test")
                }
            }
            assertIs<AppResult.Failed>(f.reopen())
            assertEquals(1, f.closes)
            assertEquals("close", f.events.last())
            assertFalse("publish" in f.events)
        }
    }

    @Test
    fun restoredIdentityMismatchAndCredentialFailureCloseTheNewLease() = runTest {
        for (scenario in 0..2) {
            val f = HistoryReopeningFixture()
            when (scenario) {
                0 -> f.leaseOwner = f.leaseOwner.copy(accountId = "foreign")
                1 -> f.leaseDevice = "foreign"
                else -> f.failures.value = f.local.storage
            }
            assertIs<AppResult.Failed>(f.reopen())
            assertEquals(1, f.closes)
        }
    }

    @Test
    fun cancellationDuringAnUncooperativeRestoreDisposesItsLateLease() = runTest {
        val f = HistoryReopeningFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.afterRestore = {
            entered.complete(Unit)
            withContext(NonCancellable) { release.await() }
        }
        val task = launch { f.reopen() }
        entered.await()
        task.cancel()
        release.complete(Unit)
        task.join()
        assertTrue(task.isCancelled)
        assertEquals(1, f.closes)
        assertFalse("publish" in f.events)
    }

    @Test
    fun pendingFinalRoomAdmissionDoesNotPublishAndFailedPublicationClosesTheNewLease() = runTest {
        val f = HistoryReopeningFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.delivery.control.beforeRemote = {
            f.events += it
            if (it == "room" && "restore" in f.events) {
                entered.complete(Unit)
                release.await()
            }
        }
        f.publicationFailure = f.local.storage
        val task = async { f.reopen() }
        entered.await()
        assertFalse("publish" in f.events)
        assertEquals(0, f.closes)
        release.complete(Unit)
        assertEquals(AppResult.Failed(f.local.storage), task.await())
        assertEquals(listOf("publish", "close"), f.events.takeLast(2))
    }

    @Test
    fun cancellationDuringCloseDoesNotReopenAndConcurrentWaitersDoNotStart() = runTest {
        val f = HistoryReopeningFixture()
        val entered = CompletableDeferred<Unit>()
        f.afterPrepare = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val first = launch { f.reopen() }
        entered.await()
        val second = launch(start = CoroutineStart.UNDISPATCHED) { f.reopen() }
        second.cancelAndJoin()
        first.cancelAndJoin()
        assertEquals(1, f.events.count { it == "prepare" })
        assertFalse("restore" in f.events)
    }

    @Test
    fun invalidInputNeverReachesDeviceAdmission() = runTest {
        val f = HistoryReopeningFixture()
        assertIs<AppResult.Failed>(
            f.useCase("selection", "bad", f.delivery.conversation, "\$anchor")
        )
        assertIs<AppResult.Failed>(
            f.useCase("selection", f.delivery.device.deviceId, "bad", "\$anchor")
        )
        assertIs<AppResult.Failed>(
            f.useCase("selection", f.delivery.device.deviceId, f.delivery.conversation, "bad")
        )
        assertTrue(f.events.isEmpty())
        assertEquals(0, f.local.setup.calls)
    }

    @Test
    fun latestOwnedHeadIsReadAndRoomReadmittedBeforeCapacityRecovery() = runTest {
        val f = HistoryReopeningFixture()
        assertEquals(f.result(CollaborationHistoryRuntimePreparation.Reopened), f.reopen(null))
        assertEquals(
            listOf(
                "get",
                "room",
                "head",
                "get",
                "room",
                "prepare",
                "get",
                "room",
                "restore",
                "get",
                "room",
                "publish",
            ),
            f.events,
        )
        assertEquals(0, f.delivery.acquisitions)
    }

    @Test
    fun absentHeadLeavesTheRuntimeAndAcquiredHistoryUntouched() = runTest {
        val f = HistoryReopeningFixture()
        f.head = f.head.copy(eventId = null)
        assertEquals(f.result(CollaborationHistoryRuntimePreparation.Available), f.reopen(null))
        assertEquals(listOf("get", "room", "head", "get", "room"), f.events)
        assertEquals(0, f.closes)
        assertEquals(0, f.delivery.acquisitions)
    }

    @Test
    fun unownedOrMalformedHeadNeverClosesOrRestores() = runTest {
        for (scenario in 0..3) {
            val f = HistoryReopeningFixture()
            f.head =
                when (scenario) {
                    0 -> f.head.copy(owner = f.head.owner.copy(accountId = "foreign"))
                    1 -> f.head.copy(deviceId = "foreign")
                    2 -> f.head.copy(roomId = "!foreign:test")
                    else -> f.head.copy(eventId = "invalid")
                }
            val failed = assertIs<AppResult.Failed>(f.reopen(null))
            assertEquals("client.collaboration_invalid_history_head", failed.failure.code)
            assertFalse("prepare" in f.events)
            assertFalse("restore" in f.events)
        }
    }

    @Test
    fun ownerDenialOrRoomChangeDuringHeadLookupCannotCloseTheOldRuntime() = runTest {
        for (scenario in 0..2) {
            val f = HistoryReopeningFixture()
            f.afterHead = {
                when (scenario) {
                    0 -> f.local.setup.session = null
                    1 -> f.local.gate.value = CollaborationListAdmission("denied", f.local.denied)
                    else ->
                        f.delivery.control.room =
                            f.delivery.control.room.copy(matrixRoomId = "!replacement:test")
                }
            }
            assertIs<AppResult.Failed>(f.reopen(null))
            assertFalse("prepare" in f.events)
            assertFalse("restore" in f.events)
        }
    }

    @Test
    fun failedHeadLookupPreservesItsFailureAndConfirmedDenial() = runTest {
        for (failure in
            listOf(
                Failure(FailureKind.Network, "Fixture offline"),
                HistoryReopeningFixture().local.denied,
            )) {
            val f = HistoryReopeningFixture()
            f.headFailure = failure
            assertEquals(failure, assertIs<AppResult.Failed>(f.reopen(null)).failure)
            assertFalse("prepare" in f.events)
            if (failure.code == f.local.denied.code)
                assertEquals(failure, f.local.gate.value.denial)
        }
    }

    @Test
    fun cancelledUncooperativeHeadCannotStartShutdownAfterItsLateReply() = runTest {
        val f = HistoryReopeningFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.afterHead = {
            withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
            }
        }
        val task = launch { f.reopen(null) }
        entered.await()
        task.cancel()
        release.complete(Unit)
        task.join()
        assertTrue(task.isCancelled)
        assertFalse("prepare" in f.events)
        assertFalse("restore" in f.events)
    }
}
