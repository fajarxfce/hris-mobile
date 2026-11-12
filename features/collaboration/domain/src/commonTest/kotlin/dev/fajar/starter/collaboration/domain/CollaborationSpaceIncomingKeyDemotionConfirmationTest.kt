package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.test.*

class CollaborationSpaceIncomingKeyDemotionConfirmationTest {
    private val base = SpaceOriginalIncomingLoadFixture()
    private val previous = assertNotNull(base.snapshot)
    private val generation = base.id(991)
    private val next =
        assertIs<AppResult.Success<CollaborationSpaceIncomingDemand>>(
                planCollaborationSpaceIncomingKeyDemotion(
                    previous.demand,
                    base.captured,
                    generation,
                )
            )
            .value
    private val committed = CollaborationSpaceIncomingDemandSnapshot(base.id(992), next)

    @Test
    fun exactCommittedSnapshotConfirmsTheTransfer() {
        assertEquals(AppResult.Success(true), confirm())
    }

    @Test
    fun aLostStorageReplyCanBeConfirmedByTheRetainedUniqueGeneration() {
        assertEquals(
            AppResult.Success(true),
            confirm(AppResult.Failed(base.directory.storageFailure)),
        )
    }

    @Test
    fun explicitCasLossCannotBorrowAnotherReadback() {
        assertIs<AppResult.Failed>(confirm(AppResult.Success(null)))
    }

    @Test
    fun aSuccessfulReplyWithoutDurableStateIsNotAReceipt() {
        assertIs<AppResult.Failed>(confirm(retained = previous))
        assertIs<AppResult.Failed>(confirm(retained = null))
    }

    @Test
    fun retainedPrimaryCaptureMeansTheTransferIsUnconfirmed() {
        assertIs<AppResult.Failed>(
            confirm(retained = committed.copy(demand = next.copy(rooms = listOf(base.captured))))
        )
    }

    @Test
    fun laterPrimaryWorkCanCoexistWithTheConfirmedRequest() {
        val newer = base.captured.copy(generation = base.id(993))
        assertEquals(
            AppResult.Success(true),
            confirm(retained = committed.copy(demand = next.copy(rooms = listOf(newer)))),
        )
    }

    @Test
    fun missingOrUnrelatedRecoveryCannotConfirmTheUniqueRequest() {
        assertIs<AppResult.Failed>(
            confirm(retained = committed.copy(demand = next.copy(historyRecovery = null)))
        )
        assertIs<AppResult.Failed>(
            confirm(
                retained =
                    committed.copy(
                        demand =
                            next.copy(
                                historyRecovery = CollaborationSpaceHistoryRecovery(base.id(994))
                            )
                    )
            )
        )
    }

    @Test
    fun aCompletedRequestedPassStillWitnessesTheTransfer() {
        val completed =
            assertNotNull(next.historyRecovery)
                .copy(revision = 1, complete = true, unresolved = true)
        assertEquals(
            AppResult.Success(true),
            confirm(retained = committed.copy(demand = next.copy(historyRecovery = completed))),
        )
    }

    @Test
    fun freshRequestBehindAnOlderPassCanBeConfirmed() {
        val active =
            CollaborationSpaceHistoryRecovery(
                base.id(994),
                revision = 2,
                afterPartition = "partition",
            )
        val before = previous.copy(demand = previous.demand.copy(historyRecovery = active))
        val replacement =
            assertIs<AppResult.Success<CollaborationSpaceIncomingDemand>>(
                    planCollaborationSpaceIncomingKeyDemotion(
                        before.demand,
                        base.captured,
                        generation,
                    )
                )
                .value
        val receipt = committed.copy(demand = replacement)
        assertEquals(
            AppResult.Success(true),
            confirmCollaborationSpaceIncomingKeyDemotion(
                base.access,
                before,
                replacement,
                base.captured,
                generation,
                AppResult.Success(receipt),
                receipt,
            ),
        )
    }

    @Test
    fun unchangedOrMalformedReceiptRevisionCannotConfirmAWrite() {
        for (receipt in
            listOf(
                committed.copy(generation = previous.generation),
                committed.copy(generation = "invalid"),
                committed.copy(demand = previous.demand),
            )) assertIs<AppResult.Failed>(confirm(AppResult.Success(receipt)))
    }

    @Test
    fun unconfirmedUnknownWritePreservesTheOriginalStorageFailure() {
        assertEquals(
            base.directory.storageFailure,
            assertIs<AppResult.Failed>(
                    confirm(AppResult.Failed(base.directory.storageFailure), previous)
                )
                .failure,
        )
    }

    @Test
    fun incompatibleOrNoOpPlansCannotBecomeTransferReceipts() {
        assertIs<AppResult.Failed>(
            confirmCollaborationSpaceIncomingKeyDemotion(
                base.access,
                previous,
                previous.demand,
                base.captured,
                generation,
                AppResult.Success(committed),
                committed,
            )
        )
        assertIs<AppResult.Failed>(
            confirmCollaborationSpaceIncomingKeyDemotion(
                base.access,
                previous,
                next,
                base.captured,
                generation,
                AppResult.Failed(base.directory.denied),
                committed,
            )
        )
    }

    private fun confirm(
        written: AppResult<CollaborationSpaceIncomingDemandSnapshot?> =
            AppResult.Success(committed),
        retained: CollaborationSpaceIncomingDemandSnapshot? = committed,
    ) =
        confirmCollaborationSpaceIncomingKeyDemotion(
            base.access,
            previous,
            next,
            base.captured,
            generation,
            written,
            retained,
        )
}
