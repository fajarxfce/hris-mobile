@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class RetainCollaborationSpaceRoomKeysTest {
    @Test
    fun knownOriginalKeysRetainIntentAndWakeBeforeAcknowledgement() = runTest {
        val f = RoomKeyRetentionFixture()
        f.before = { step ->
            if (step == "ack") {
                assertEquals(
                    f.batch.receiptId,
                    assertNotNull(f.snapshot).demand.roomKeyReceipt?.receiptId,
                )
                assertEquals(f.batch.receiptId, f.wake?.generation)
                assertEquals(
                    listOf(f.original.room.roomId),
                    f.snapshot?.demand?.historyRecovery?.roomIds,
                )
            }
        }
        assertEquals(AppResult.Success(true), f.receive())
        assertEquals(1, f.writes)
        assertEquals(1, f.consumed)
        assertEquals(1, f.starts)
        assertEquals(1, f.observers)
        assertEquals(1, f.stopped)
        assertEquals(1, f.schedules)
        assertEquals(listOf("original" to f.batch.roomIds.single()), f.lookupRooms)
        assertTrue(assertNotNull(f.snapshot).demand.sources.isEmpty())
    }

    @Test
    fun publishedPhysicalRoomUsesLocalIndexesWithoutTransportLookup() = runTest {
        val f = RoomKeyRetentionFixture()
        f.originalKnown = false
        f.publishedKnown = true
        val batch = f.batch.copy(roomIds = listOf(f.published.room.roomId))
        assertEquals(AppResult.Success(true), f.receive(batch))
        assertEquals(
            listOf("original" to batch.roomIds.single(), "published" to batch.roomIds.single()),
            f.lookupRooms,
        )
        assertEquals(batch.roomIds, f.snapshot?.demand?.historyRecovery?.roomIds)
        assertEquals(1, f.consumed)
    }

    @Test
    fun reconciliationRequestsAccountRecoveryWithoutRoomLookups() = runTest {
        val f = RoomKeyRetentionFixture()
        assertEquals(AppResult.Success(true), f.receive(f.batch.copy(reconcileRequired = true)))
        assertTrue(f.lookupRooms.isEmpty())
        assertNull(assertNotNull(f.snapshot?.demand?.historyRecovery).roomIds)
    }

    @Test
    fun genuinelyUnknownRoomRequestsOneAccountFallback() = runTest {
        val f = RoomKeyRetentionFixture()
        f.originalKnown = false
        assertEquals(AppResult.Success(true), f.receive())
        assertEquals(2, f.lookupRooms.size)
        assertNull(assertNotNull(f.snapshot?.demand?.historyRecovery).roomIds)
        assertEquals(1, f.writes)
    }

    @Test
    fun failedLookupCannotBeAcknowledgedAsAnUnknownRoom() = runTest {
        val f = RoomKeyRetentionFixture()
        val denied = Failure(FailureKind.AccessDenied, "Denied")
        f.failures["original"] = denied
        assertEquals(AppResult.Failed(denied), f.receive())
        assertEquals(1, f.lookupRooms.size)
        assertEquals(0, f.writes)
        assertTrue(f.acknowledged.isEmpty())
        assertEquals(f.batch, f.pending)
    }

    @Test
    fun mismatchedLocalMetadataCannotClassifyTheRoom() = runTest {
        val f = RoomKeyRetentionFixture()
        f.wrongOriginal = true
        assertIs<AppResult.Failed>(f.receive())
        assertEquals(1, f.lookupRooms.size)
        assertEquals(0, f.writes)
        assertTrue(f.acknowledged.isEmpty())
    }

    @Test
    fun anotherSameRoomOccurrenceDuringWriteSurvivesTheFirstAck() = runTest {
        val f = RoomKeyRetentionFixture()
        val second = f.batch.copy(receiptId = f.id(2101))
        f.before = { step -> if (step == "committed" && f.writes == 1) f.publish(second) }
        f.publish()
        assertEquals(
            listOf(AppResult.Success(true), AppResult.Success(true)),
            f.usecase.observe(f.runtime).take(2).toList(),
        )
        assertEquals(
            listOf(f.batch.receiptId, second.receiptId),
            f.acknowledged.map { it.receiptId },
        )
        assertEquals(2, f.writes)
        assertEquals(2, f.consumed)
        assertEquals(second.receiptId, f.snapshot?.demand?.roomKeyReceipt?.receiptId)
        assertEquals(f.batch.receiptId, f.snapshot?.demand?.historyRecovery?.generation)
        assertEquals(second.receiptId, f.snapshot?.demand?.historyRecovery?.requestedGeneration)
    }

    @Test
    fun lostCommitReplyIsConfirmedAfterWorkerAndOtherProducerProgress() = runTest {
        val f = RoomKeyRetentionFixture()
        f.loseReply = true
        f.before = { step ->
            if (step == "committed") {
                val saved = assertNotNull(f.snapshot)
                f.snapshot =
                    saved.copy(
                        generation = f.id(2300),
                        demand =
                            saved.demand.copy(
                                historyRecovery =
                                    CollaborationSpaceHistoryRecovery(
                                        f.id(2301),
                                        revision = 1,
                                        complete = true,
                                    ),
                                rescanGeneration = f.id(2302),
                            ),
                    )
            }
        }
        assertEquals(AppResult.Success(true), f.receive())
        assertEquals(1, f.writes)
        assertEquals(1, f.consumed)
    }

    @Test
    fun exactReplaySkipsMetadataWritesAndNewWake() = runTest {
        val f = RoomKeyRetentionFixture()
        assertEquals(AppResult.Success(true), f.receive())
        val snapshot = f.snapshot
        val lookups = f.lookupRooms.toList()
        assertEquals(AppResult.Success(false), f.receive())
        assertEquals(snapshot, f.snapshot)
        assertEquals(lookups, f.lookupRooms)
        assertEquals(1, f.writes)
        assertEquals(1, f.schedules)
        assertEquals(2, f.consumed)
    }

    @Test
    fun compareAndSetContentionRetriesTheSameOccurrenceAtMostThreeTimes() = runTest {
        val f = RoomKeyRetentionFixture()
        f.casLosses = 2
        assertEquals(AppResult.Success(true), f.receive())
        assertEquals(3, f.writes)
        assertEquals(f.batch.receiptId, f.snapshot?.demand?.historyRecovery?.generation)
        assertEquals(1, f.starts)
        assertEquals(1, f.observers)
        assertEquals(1, f.consumed)
    }

    @Test
    fun persistentStorageFailureEndsObservationAfterThreeAttempts() = runTest {
        val f = RoomKeyRetentionFixture()
        f.failures["write"] = f.storage
        assertEquals(AppResult.Failed(f.storage), f.receive())
        assertEquals(3, f.writes)
        assertEquals(750L, testScheduler.currentTime)
        assertEquals(1, f.starts)
        assertEquals(1, f.stopped)
        assertTrue(f.acknowledged.isEmpty())
        assertEquals(f.batch, f.pending)
    }

    @Test
    fun authorityFailureNeverRetriesEvenWithAContentionCode() = runTest {
        val f = RoomKeyRetentionFixture()
        val denied =
            Failure(
                FailureKind.AccessDenied,
                "Denied",
                code = "client.collaboration_room_key_write_contended",
            )
        f.failures["write"] = denied
        assertEquals(AppResult.Failed(denied), f.receive())
        assertEquals(1, f.writes)
        assertTrue(f.acknowledged.isEmpty())
    }

    @Test
    fun timeoutsAreBoundedWithoutRestartingNativeObservation() = runTest {
        val f = RoomKeyRetentionFixture()
        f.before = { step -> if (step == "write") awaitCancellation() }
        assertEquals(FailureKind.Timeout, assertIs<AppResult.Failed>(f.receive()).failure.kind)
        assertEquals(3, f.writes)
        assertEquals(15_750L, testScheduler.currentTime)
        assertEquals(1, f.starts)
        assertEquals(1, f.observers)
        assertTrue(f.acknowledged.isEmpty())
    }

    @Test
    fun successfulResponseWithoutACommitNeverAcknowledgesTheHint() = runTest {
        val f = RoomKeyRetentionFixture()
        f.commit = false
        assertIs<AppResult.Failed>(f.receive())
        assertEquals(1, f.writes)
        assertNull(f.snapshot)
        assertTrue(f.acknowledged.isEmpty())
    }

    @Test
    fun retirementAfterCommitKeepsIntentButStopsOldAcknowledgement() = runTest {
        val f = RoomKeyRetentionFixture()
        f.before = { step -> if (step == "committed") f.base.installed = null }
        assertEquals(FailureKind.Unavailable, assertIs<AppResult.Failed>(f.receive()).failure.kind)
        assertNotNull(f.snapshot?.demand?.roomKeyReceipt)
        assertNotNull(f.wake)
        assertTrue(f.acknowledged.isEmpty())
        assertEquals(1, f.writes)
    }

    @Test
    fun cancelledCommitKeepsTheSameReceiptForANewCollector() = runTest {
        val f = RoomKeyRetentionFixture()
        val entered = CompletableDeferred<Unit>()
        f.before = { step ->
            if (step == "committed") {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        f.publish()
        val first = launch { f.usecase.observe(f.runtime).collect() }
        entered.await()
        first.cancelAndJoin()
        assertEquals(f.batch.receiptId, f.snapshot?.demand?.roomKeyReceipt?.receiptId)
        assertTrue(f.acknowledged.isEmpty())
        f.before = {}
        assertEquals(AppResult.Success(false), f.usecase.observe(f.runtime).first())
        assertEquals(1, f.writes)
        assertEquals(1, f.consumed)
    }

    @Test
    fun cancellationBeforeWriteCreatesNeitherReceiptNorAcknowledgement() = runTest {
        val f = RoomKeyRetentionFixture()
        val entered = CompletableDeferred<Unit>()
        f.before = { step ->
            if (step == "write") {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        f.publish()
        val job = launch { f.usecase.observe(f.runtime).collect() }
        entered.await()
        job.cancelAndJoin()
        assertNull(f.snapshot)
        assertNull(f.wake)
        assertTrue(f.acknowledged.isEmpty())
        assertEquals(1, f.stopped)
    }

    @Test
    fun aSecondCollectorWaitsUntilTheFirstHasBeenCancelledAndJoined() = runTest {
        val f = RoomKeyRetentionFixture()
        val first = launch { f.usecase.observe(f.runtime).collect() }
        runCurrent()
        assertEquals(1, f.observers)
        val second = async { f.usecase.observe(f.runtime).first() }
        runCurrent()
        assertEquals(1, f.starts)
        assertEquals(1, f.observers)
        first.cancelAndJoin()
        f.publish()
        assertEquals(AppResult.Success(true), second.await())
        assertEquals(2, f.starts)
        assertEquals(2, f.stopped)
        assertEquals(1, f.writes)
    }

    @Test
    fun cancellingAWaitingCollectorDoesNotRegisterAnotherListener() = runTest {
        val f = RoomKeyRetentionFixture()
        val first = launch { f.usecase.observe(f.runtime).collect() }
        runCurrent()
        val waiting = launch { f.usecase.observe(f.runtime).collect() }
        runCurrent()
        waiting.cancelAndJoin()
        assertEquals(1, f.starts)
        assertEquals(1, f.observers)
        first.cancelAndJoin()
        assertEquals(1, f.stopped)
    }

    @Test
    fun invalidOrForeignCallbackCannotRetainOrAcknowledgeWork() = runTest {
        for (foreign in listOf(false, true)) {
            val f = RoomKeyRetentionFixture()
            val batch =
                if (foreign) f.batch.copy(source = f.source.copy(matrixDeviceId = "other"))
                else f.batch.copy(roomIds = emptyList())
            assertEquals(
                FailureKind.Validation,
                assertIs<AppResult.Failed>(f.receive(batch)).failure.kind,
            )
            assertTrue(f.lookupRooms.isEmpty())
            assertTrue(f.acknowledged.isEmpty())
            assertEquals(0, f.writes)
            assertEquals(1, f.stopped)
        }
    }

    @Test
    fun rawRoomListsAreCapturedBeforeTheFirstSuspendedRead() = runTest {
        val f = RoomKeyRetentionFixture()
        val rooms = mutableListOf(f.original.room.roomId)
        f.before = { step -> if (step == "demand") rooms.clear() }
        assertEquals(AppResult.Success(true), f.receive(f.batch.copy(roomIds = rooms)))
        assertEquals(listOf(f.original.room.roomId), f.snapshot?.demand?.historyRecovery?.roomIds)
        assertEquals(listOf(f.original.room.roomId), f.acknowledged.single().roomIds)
    }

    @Test
    fun absentRootOrRegistrationNeverStartsKeyObservation() = runTest {
        for (missingRoot in listOf(false, true)) {
            val f = RoomKeyRetentionFixture()
            if (missingRoot) f.history.root = null else f.base.registration = null
            assertIs<AppResult.Failed>(f.receive())
            assertEquals(0, f.starts)
            assertEquals(0, f.observers)
            assertEquals(0, f.writes)
        }
    }

    @Test
    fun unavailableNativeRegistrationIsNotRetriedByTheFlow() = runTest {
        val f = RoomKeyRetentionFixture()
        val unavailable = Failure(FailureKind.Unavailable, "Unavailable")
        f.failures["start"] = unavailable
        assertEquals(AppResult.Failed(unavailable), f.receive())
        assertEquals(1, f.starts)
        assertEquals(0, f.observers)
        assertEquals(0, f.writes)
    }

    @Test
    fun observerFailureEndsCollectionWithoutStartingItAgain() = runTest {
        val f = RoomKeyRetentionFixture()
        val failed = AppResult.Failed(Failure(FailureKind.Unavailable, "Unavailable"))
        f.updates.value = failed
        assertEquals(listOf(failed), f.usecase.observe(f.runtime).toList())
        assertEquals(1, f.starts)
        assertEquals(1, f.observers)
        assertEquals(1, f.stopped)
        assertEquals(0, f.writes)
    }

    @Test
    fun aPreviouslyReleasedReceiptCannotConsumeItsSuccessor() = runTest {
        val f = RoomKeyRetentionFixture()
        val next = f.batch.copy(receiptId = f.id(2101))
        f.before = { step ->
            if (step == "ack") {
                // Simulate a late reply after the old raw release; its successor now owns the slot.
                f.pending = next
                f.updates.value = AppResult.Success(next)
            }
        }
        assertEquals(AppResult.Success(true), f.receive())
        assertEquals(next, f.pending)
        assertEquals(0, f.consumed)
        assertEquals(listOf(f.batch), f.acknowledged)
        assertEquals(1, f.writes)
    }

    @Test
    fun failedAdvisorySchedulingDoesNotUndoDurableIntentOrAck() = runTest {
        val f = RoomKeyRetentionFixture()
        f.failures["schedule"] = f.storage
        assertEquals(AppResult.Success(true), f.receive())
        assertEquals(1, f.consumed)
        assertNotNull(f.snapshot?.demand?.roomKeyReceipt)
        assertNotNull(f.wake)
        assertEquals(1, f.schedules)
    }
}
