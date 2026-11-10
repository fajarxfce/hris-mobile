package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.recoverCollaborationSpaceStaging
import kotlin.test.*

class CollaborationSpaceStagingRecoveryTest {
    @Test
    fun exactOriginalIsRecoveredWithoutAllocatingAnotherTransaction() {
        val f = Fixture()
        assertEquals(CollaborationQueueRecovery.Found(f.source.original.transactionId), f.recover())
    }

    @Test
    fun onlyCompleteUnchangedBaselineCanEstablishMissing() {
        val f = Fixture()
        assertEquals(
            CollaborationQueueRecovery.Missing,
            f.recover(f.snapshot.copy(messages = listOf(f.source.base.queue.original))),
        )
        assertEquals(
            CollaborationQueueRecovery.Ambiguous,
            f.recover(f.snapshot.copy(messages = emptyList())),
        )
    }

    @Test
    fun anotherLogicalMessageCannotBeMistakenForTheTargetsTransaction() {
        val f = Fixture()
        val other = f.source.original.copy(logicalId = f.source.base.queue.id(999))
        assertEquals(
            CollaborationQueueRecovery.Ambiguous,
            f.recover(f.snapshot.copy(messages = listOf(f.source.base.queue.original, other))),
        )
    }

    @Test
    fun sameLogicalIdWithChangedCanonicalContentCannotRecover() {
        val f = Fixture()
        val other =
            f.source.original.copy(
                origin = f.source.original.origin.copy(contentHash = "b".repeat(64))
            )
        assertNotEquals(f.source.original.origin, other.origin)
        assertEquals(
            CollaborationQueueRecovery.Ambiguous,
            f.recover(f.snapshot.copy(messages = listOf(f.source.base.queue.original, other))),
        )
    }

    @Test
    fun sameTargetInHistoricalBaselineIsNotANewEnqueue() {
        val f = Fixture()
        val state = f.record.state as CollaborationMessageState.Staging
        val changed =
            f.record.copy(
                state = state.copy(before = state.before + f.source.original.transactionId)
            )
        assertEquals(CollaborationQueueRecovery.Ambiguous, f.recover(record = changed))
    }

    @Test
    fun duplicateUnknownAndOversizedInventoriesRemainAmbiguous() {
        val f = Fixture()
        val old = f.source.base.queue.original
        for (values in
            listOf(
                listOf(old, f.source.original, f.source.original),
                listOf(
                    old,
                    f.source.original,
                    f.source.original.copy(
                        logicalId = f.source.base.queue.id(999),
                        transactionId = "extra",
                    ),
                ),
                listOf(old, f.source.original.copy(logicalId = null)),
                List(129) { old },
            )) assertEquals(
            CollaborationQueueRecovery.Ambiguous,
            f.recover(f.snapshot.copy(messages = values)),
        )
    }

    @Test
    fun originalPhysicalDeviceCannotBeReplacedWithLogicalRuntimeIdentity() {
        val f = Fixture()
        assertEquals(
            CollaborationQueueRecovery.DifferentDevice,
            f.recover(
                f.snapshot.copy(
                    device = f.snapshot.device.copy(deviceId = f.source.base.queue.runtime.deviceId)
                )
            ),
        )
    }

    @Test
    fun missingBaselineOriginalCannotBeIgnoredEvenWhenTargetExists() {
        val f = Fixture()
        assertEquals(
            CollaborationQueueRecovery.Ambiguous,
            f.recover(f.snapshot.copy(messages = listOf(f.source.original))),
        )
    }

    @Test
    fun nonStagingAndForeignOwnerRecordsCannotBeRecovered() {
        val f = Fixture()
        assertEquals(CollaborationQueueRecovery.Ambiguous, f.recover(record = f.source.record))
        val intent = f.record.intent
        assertEquals(
            CollaborationQueueRecovery.Ambiguous,
            f.recover(
                record =
                    f.record.copy(
                        intent =
                            intent.copy(
                                owner = intent.owner.copy(spaceId = f.source.base.queue.id(999))
                            )
                    )
            ),
        )
    }

    private class Fixture {
        val source = MixedOriginalQueueReadFixture()
        val snapshot = source.base.snapshot.original
        val record =
            source.record.copy(
                revision = 1,
                state =
                    CollaborationMessageState.Staging(
                        snapshot.device,
                        setOf(source.base.queue.original.transactionId),
                        source.original.origin,
                    ),
            )

        fun recover(
            snapshot: CollaborationOriginalQueueSnapshot = this.snapshot,
            record: CollaborationSpaceMessageRecord = this.record,
        ) = recoverCollaborationSpaceStaging(source.access, record, snapshot)
    }
}
