package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.test.*

class CollaborationSpaceRoomKeyRetentionPolicyTest {
    private class Fixture {
        val base = SpaceHistoryRecoveryCompletionFixture()
        val before = assertNotNull(base.snapshot)
        val room = "!known:test"
        val batch =
            CollaborationSpaceRoomKeyBatch(
                base.runtime,
                base.base.source,
                base.base.id(997),
                listOf(room),
                false,
            )
        val receipt = assertNotNull(captureCollaborationSpaceRoomKeyReceipt(batch))
        val next = success(planCollaborationSpaceRoomKeyRetention(before, receipt, listOf(room)))
        val saved = before.copy(generation = base.base.id(998), demand = next)
        val storage = AppResult.Failed(base.base.directory.storageFailure)

        fun confirm(
            written: AppResult<CollaborationSpaceIncomingDemandSnapshot?> =
                AppResult.Success(saved),
            retained: CollaborationSpaceIncomingDemandSnapshot? = saved,
        ) =
            confirmCollaborationSpaceRoomKeyRetention(
                base.access,
                before,
                receipt,
                listOf(room),
                next,
                written,
                retained,
            )
    }

    @Test
    fun aKeyReceiptDoesNotInventASourceSyncPosition() {
        val f = Fixture()
        val next = success(planCollaborationSpaceRoomKeyRetention(null, f.receipt, listOf(f.room)))
        assertEquals(f.receipt, next.roomKeyReceipt)
        val recovery = assertNotNull(next.historyRecovery)
        assertEquals(listOf(f.room), recovery.roomIds)
        assertEquals(f.receipt.receiptId, recovery.generation)
        assertTrue(next.sources.isEmpty())
        assertEquals(0L, next.nextPosition)
    }

    @Test
    fun laterKeysPreserveActiveHistoryAndPrimaryWork() {
        val f = Fixture()
        val old = assertNotNull(f.before.demand.historyRecovery)
        val next = assertNotNull(f.next.historyRecovery)
        assertEquals(old.generation, next.generation)
        assertEquals(old.roomIds, next.roomIds)
        assertEquals(f.receipt.receiptId, next.requestedGeneration)
        assertEquals(listOf(f.room), next.requestedRoomIds)
        assertEquals(
            f.before.demand.copy(historyRecovery = next, roomKeyReceipt = f.receipt),
            f.next,
        )
    }

    @Test
    fun exactReplayAfterWorkerCompletionDoesNotCreateAnotherPass() {
        val f = Fixture()
        val completed =
            CollaborationSpaceHistoryRecovery(f.base.base.id(993), revision = 1, complete = true)
        val progressed =
            f.saved.copy(
                generation = f.base.base.id(994),
                demand = f.next.copy(historyRecovery = completed),
            )
        assertEquals(
            AppResult.Success(progressed.demand),
            planCollaborationSpaceRoomKeyRetention(progressed, f.receipt, null),
        )
    }

    @Test
    fun aNewSameRoomOccurrenceRetainsADistinctLaterRequest() {
        val f = Fixture()
        val second = f.receipt.copy(receiptId = f.base.base.id(995))
        val next = success(planCollaborationSpaceRoomKeyRetention(f.saved, second, listOf(f.room)))
        assertEquals(second, next.roomKeyReceipt)
        val recovery = assertNotNull(next.historyRecovery)
        assertEquals(second.receiptId, recovery.requestedGeneration)
        assertEquals(listOf(f.room), recovery.requestedRoomIds)
        assertEquals(f.next.historyRecovery?.generation, recovery.generation)
    }

    @Test
    fun receiptReuseWithDifferentRuntimeOrPhysicalSourceIsRejected() {
        val f = Fixture()
        val changed =
            listOf(
                f.receipt.copy(runtimeDeviceId = f.base.base.id(996)),
                f.receipt.copy(runtimeOperationId = f.base.base.id(996)),
                f.receipt.copy(source = f.receipt.source.copy(matrixDeviceId = "another-device")),
            )
        for (receipt in changed) assertIs<AppResult.Failed>(
            planCollaborationSpaceRoomKeyRetention(f.saved, receipt, listOf(f.room))
        )
    }

    @Test
    fun aHistoryGenerationWithoutItsReceiptCannotBeBorrowedAsProof() {
        val f = Fixture()
        val before =
            f.before.copy(
                demand =
                    f.before.demand.copy(
                        historyRecovery = CollaborationSpaceHistoryRecovery(f.receipt.receiptId)
                    )
            )
        assertIs<AppResult.Failed>(planCollaborationSpaceRoomKeyRetention(before, f.receipt, null))
    }

    @Test
    fun malformedReceiptCannotCreateOrConfirmIntent() {
        val f = Fixture()
        for (receipt in
            listOf(
                f.receipt.copy(receiptId = "bad"),
                f.receipt.copy(runtimeDeviceId = "bad"),
                f.receipt.copy(runtimeOperationId = "bad"),
                f.receipt.copy(source = f.receipt.source.copy(storeId = "bad")),
            )) {
            assertFalse(validCollaborationSpaceRoomKeyReceipt(receipt))
            assertIs<AppResult.Failed>(
                planCollaborationSpaceRoomKeyRetention(f.before, receipt, null)
            )
        }
    }

    @Test
    fun callerOwnedTargetsAreNotRetainedByThePlan() {
        val f = Fixture()
        val rooms = mutableListOf(f.room)
        val next = success(planCollaborationSpaceRoomKeyRetention(null, f.receipt, rooms))
        rooms.clear()
        assertEquals(listOf(f.room), assertNotNull(next.historyRecovery).roomIds)
    }

    @Test
    fun exactReceiptAndReadbackConfirmAtomicIntent() {
        assertEquals(AppResult.Success(true), Fixture().confirm())
    }

    @Test
    fun explicitCompareAndSetLossIsNotACommittedReceipt() {
        assertIs<AppResult.Failed>(Fixture().confirm(AppResult.Success(null)))
    }

    @Test
    fun responseWithoutDurableReceiptCannotConfirmIntent() {
        val f = Fixture()
        assertIs<AppResult.Failed>(f.confirm(retained = f.before))
        assertIs<AppResult.Failed>(f.confirm(retained = null))
    }

    @Test
    fun lostWriteReplyIsConfirmedAfterWorkerAndUnrelatedProducerProgress() {
        val f = Fixture()
        val progressed =
            f.saved.copy(
                generation = f.base.base.id(992),
                demand =
                    f.next.copy(
                        historyRecovery =
                            CollaborationSpaceHistoryRecovery(
                                f.base.base.id(991),
                                revision = 1,
                                complete = true,
                            ),
                        rescanGeneration = f.base.base.id(990),
                    ),
            )
        assertEquals(AppResult.Success(true), f.confirm(f.storage, progressed))
    }

    @Test
    fun anOverwrittenOrChangedWitnessPreservesTheStorageFailure() {
        val f = Fixture()
        for (receipt in
            listOf(
                null,
                f.receipt.copy(receiptId = f.base.base.id(993)),
                f.receipt.copy(source = f.receipt.source.copy(matrixDeviceId = "other")),
            )) {
            val retained = f.saved.copy(demand = f.next.copy(roomKeyReceipt = receipt))
            assertEquals(f.storage, f.confirm(f.storage, retained))
        }
    }

    @Test
    fun nonStorageFailureCannotBecomeAConfirmedReceipt() {
        val f = Fixture()
        for (kind in
            listOf(
                FailureKind.AccessDenied,
                FailureKind.Unauthorized,
                FailureKind.Timeout,
                FailureKind.Conflict,
            )) {
            val failed = collaborationRecoveryFailure("failed", kind)
            assertEquals(failed, f.confirm(failed))
        }
    }

    @Test
    fun fabricatedReplyCannotDropIndependentPrimaryWork() {
        val f = Fixture()
        val reply = f.saved.copy(demand = f.next.copy(rooms = emptyList()))
        assertIs<AppResult.Failed>(f.confirm(AppResult.Success(reply)))
    }

    @Test
    fun aWitnessWithoutAnyRecoveryStateIsInvalid() {
        val f = Fixture()
        assertFalse(
            validCollaborationSpaceIncomingDemand(
                CollaborationSpaceIncomingDemand(roomKeyReceipt = f.receipt)
            )
        )
    }

    @Test
    fun syncResponsesAndInventoryLossPreserveTheLastKeyWitness() {
        val f = Fixture()
        var current = f.next
        for ((index, loss) in listOf(false, true).withIndex()) {
            val source = current.sources.single()
            val response =
                CollaborationSpaceSyncBatch(
                    f.base.runtime,
                    source.source,
                    source.position,
                    "next-$index",
                    emptyList(),
                    loss,
                )
            val retained =
                assertIs<AppResult.Success<CollaborationSpaceIncomingBatchRetention>>(
                        retainCollaborationSpaceSyncBatch(
                            current,
                            response,
                            emptyList(),
                            f.base.base.id(2500 + index),
                        )
                    )
                    .value
            assertEquals(f.receipt, retained.demand.roomKeyReceipt)
            current = retained.demand
        }
        val source = current.sources.single()
        assertNotNull(source.inventoryGeneration)
        val page =
            CollaborationSpaceRoomInventoryPage(
                f.base.runtime,
                source.source,
                null,
                emptyList(),
                null,
            )
        val finished =
            assertIs<AppResult.Success<CollaborationSpaceInventoryRetention>>(
                    retainCollaborationSpaceInventoryPage(current, source, page, emptyList())
                )
                .value
        assertEquals(f.receipt, finished.demand.roomKeyReceipt)
        assertNull(finished.demand.sources.single().inventoryGeneration)
    }

    @Test
    fun logicalQueueRequestsAndRescanPreserveTheLastKeyWitness() {
        val f = Fixture()
        var current = f.next
        for (change in
            listOf(
                CollaborationSpaceIncomingDemandChange.Rescan(f.base.base.id(2500)),
                CollaborationSpaceIncomingDemandChange.Request(
                    f.base.base.id(2501),
                    f.base.base.id(2502),
                    null,
                ),
            )) {
            current =
                assertIs<AppResult.Success<CollaborationSpaceIncomingDemand>>(
                        nextCollaborationSpaceIncomingDemand(current, change)
                    )
                    .value
            assertEquals(f.receipt, current.roomKeyReceipt)
        }
    }

    companion object {
        private fun success(result: AppResult<CollaborationSpaceIncomingDemand>) =
            assertIs<AppResult.Success<CollaborationSpaceIncomingDemand>>(result).value
    }
}
