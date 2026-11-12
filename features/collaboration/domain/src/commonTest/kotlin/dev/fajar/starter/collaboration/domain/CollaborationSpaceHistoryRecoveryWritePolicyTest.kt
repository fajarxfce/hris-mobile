package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.test.*

class CollaborationSpaceHistoryRecoveryWritePolicyTest {
    private class Fixture {
        val source = SpaceHistoryRecoveryCompletionFixture()
        val before = assertNotNull(source.snapshot)
        val next = source.recovery.copy(revision = 1, complete = true)
        val saved =
            before.copy(
                generation = source.base.id(981),
                demand = before.demand.copy(historyRecovery = next),
            )
        val storage = AppResult.Failed(source.base.directory.storageFailure)

        fun confirm(
            written: AppResult<CollaborationSpaceIncomingDemandSnapshot?> =
                AppResult.Success(saved),
            retained: CollaborationSpaceIncomingDemandSnapshot? = saved,
            expected: CollaborationSpaceHistoryRecovery = next,
        ) =
            confirmCollaborationSpaceHistoryRecoveryWrite(
                source.access,
                before,
                expected,
                written,
                retained,
            )
    }

    @Test
    fun exactWriteAndReadbackConfirmOneAdvancement() {
        assertEquals(AppResult.Success(true), Fixture().confirm())
    }

    @Test
    fun missingWriteReceiptCannotBeReplacedByCoincidentalReadback() {
        val f = Fixture()
        assertIs<AppResult.Failed>(f.confirm(AppResult.Success(null)))
    }

    @Test
    fun unchangedOrMalformedWriteRevisionIsNotAReceipt() {
        val f = Fixture()
        for (id in listOf(f.before.generation, "malformed")) assertIs<AppResult.Failed>(
            f.confirm(AppResult.Success(f.saved.copy(generation = id)))
        )
    }

    @Test
    fun writtenDemandMustPreserveOtherWorkExactly() {
        val f = Fixture()
        assertIs<AppResult.Failed>(
            f.confirm(
                AppResult.Success(f.saved.copy(demand = f.saved.demand.copy(rooms = emptyList())))
            )
        )
    }

    @Test
    fun unchangedOrMissingReadbackCannotConfirmSuccess() {
        val f = Fixture()
        for (retained in listOf(null, f.before, f.saved.copy(demand = f.before.demand))) assertIs<
            AppResult.Failed
        >(
            f.confirm(retained = retained)
        )
    }

    @Test
    fun storageReplyLossCanBeConfirmedByExactDurableNextTurn() {
        val f = Fixture()
        assertEquals(AppResult.Success(true), f.confirm(f.storage))
        assertEquals(f.storage, f.confirm(f.storage, f.before))
    }

    @Test
    fun operationalOrAuthorizationFailureCannotBeConvertedToReceipt() {
        val f = Fixture()
        for (kind in
            listOf(
                FailureKind.Unauthorized,
                FailureKind.AccessDenied,
                FailureKind.Conflict,
                FailureKind.Timeout,
            )) {
            val failed = collaborationRecoveryFailure("failure", kind)
            assertEquals(failed, f.confirm(failed))
        }
    }

    @Test
    fun newerIndependentLaneDoesNotInvalidateConfirmedHistory() {
        val f = Fixture()
        val retained =
            f.saved.copy(
                generation = f.source.base.id(982),
                demand = f.saved.demand.copy(rescanGeneration = f.source.base.id(983)),
            )
        assertEquals(AppResult.Success(true), f.confirm(retained = retained))
    }

    @Test
    fun laterRequestedGenerationMayChangeAtAnActiveCursor() {
        val f = Fixture()
        val next = f.next.copy(complete = false, afterPartition = "partition")
        val saved = f.saved.copy(demand = f.saved.demand.copy(historyRecovery = next))
        val retained =
            saved.copy(
                demand =
                    saved.demand.copy(
                        historyRecovery = next.copy(requestedGeneration = f.source.base.id(984))
                    )
            )
        assertEquals(AppResult.Success(true), f.confirm(AppResult.Success(saved), retained, next))
    }

    @Test
    fun advancingTheNextTurnAgainIsNotExactReadback() {
        val f = Fixture()
        val retained =
            f.saved.copy(
                demand =
                    f.saved.demand.copy(
                        historyRecovery = CollaborationSpaceHistoryRecovery(f.source.base.id(985))
                    )
            )
        assertIs<AppResult.Failed>(f.confirm(retained = retained))
        assertEquals(f.storage, f.confirm(f.storage, retained))
    }

    @Test
    fun invalidRetainedOtherWorkIsRejected() {
        val f = Fixture()
        assertIs<AppResult.Failed>(
            f.confirm(retained = f.saved.copy(demand = f.saved.demand.copy(nextPosition = 0)))
        )
    }

    @Test
    fun unchangedExpectedRecoveryIsNotAnAdvancement() {
        val f = Fixture()
        assertIs<AppResult.Failed>(f.confirm(expected = f.source.recovery))
    }
}
