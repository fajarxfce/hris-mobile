package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.test.runTest

class CollaborationQueueNamespaceTest {
    @Test
    fun currentAcknowledgementDoesNotWaitForStagedWorkInAnotherPhysicalStore() = runTest {
        for (nativeChanged in listOf(true, false)) {
            val f = CollaborationDeliveryFixture()
            f.prepare(2)
            val original = f.records.last()
            val oldDevice =
                if (nativeChanged) f.device.copy(nativeSessionId = deliveryId(999))
                else f.device.copy(deviceId = deliveryId(999))
            val old =
                original.copy(
                    state = CollaborationMessageState.Staged(oldDevice, "old-transaction")
                )
            f.base.records.value = listOf(old, f.records.first())
            val result = assertIs<AppResult.Success<CollaborationDeliveryReport>>(f.deliver()).value
            assertEquals(setOf("original"), f.knownTransactions)
            assertEquals(1, f.windows)
            assertEquals(1, f.projections)
            assertEquals(old, f.records.first())
            assertEquals(setOf(old.intent.id), result.unresolved)
            assertEquals(setOf(f.records.last().intent.id), result.confirmed)
            assertEquals(CollaborationQueueDeliveryReason.UNRESOLVED, result.nativeReason)
            assertEquals(f.records.last().intent, f.journal.single().intent)
            assertEquals("original", f.journal.single().transactionId)
            assertEquals(1, f.closes)
        }
    }

    @Test
    fun anotherStoresUnfinishedStagingCannotBlockTheCurrentAcknowledgement() = runTest {
        val f = CollaborationDeliveryFixture()
        f.prepare(2)
        val old =
            f.records
                .last()
                .copy(
                    state =
                        CollaborationMessageState.Staging(
                            f.device.copy(nativeSessionId = deliveryId(999)),
                            setOf("old-before"),
                        )
                )
        f.base.records.value = listOf(old, f.records.first())
        val result = assertIs<AppResult.Success<CollaborationDeliveryReport>>(f.deliver()).value
        assertEquals(old, f.records.first())
        assertEquals(setOf(old.intent.id), result.unresolved)
        assertEquals(1, f.windows)
        assertEquals(1, f.journal.size)
        assertEquals(1, f.projections)
    }

    @Test
    fun historicalWorkAloneNeverOpensAQueueOrInventsAnAcknowledgement() = runTest {
        for (staging in listOf(true, false)) {
            val f = CollaborationDeliveryFixture()
            f.prepare()
            val device = f.device.copy(nativeSessionId = deliveryId(999))
            val old =
                f.records
                    .single()
                    .copy(
                        state =
                            if (staging) CollaborationMessageState.Staging(device, emptySet())
                            else CollaborationMessageState.Staged(device, "old-transaction")
                    )
            f.base.records.value = listOf(old)
            f.retainedTransactions = emptySet()
            val result = assertIs<AppResult.Success<CollaborationDeliveryReport>>(f.deliver()).value
            assertEquals(listOf(old), f.records)
            assertEquals(setOf(old.intent.id), result.unresolved)
            assertTrue(result.confirmed.isEmpty())
            assertEquals(0, f.windows)
            assertTrue(f.journal.isEmpty())
            assertEquals(1, f.closes)
        }
    }

    @Test
    fun newTextUsesItsCurrentStoreWithoutRewritingAnOlderStagingAttempt() = runTest {
        for (nativeChanged in listOf(true, false)) {
            val f = StageFixture()
            f.prepare()
            val device =
                if (nativeChanged) f.device.copy(nativeSessionId = deliveryId(999))
                else f.device.copy(deviceId = deliveryId(999))
            val old =
                f.record.copy(
                    intent =
                        f.record.intent.copy(id = deliveryId(998), text = "Older pending message"),
                    revision = 1,
                    state = CollaborationMessageState.Staging(device, setOf("old-before"), f.origin),
                )
            f.base.records.value += old
            assertIs<AppResult.Success<CollaborationMessageRecord>>(f.stage())
            assertEquals(setOf("original"), f.nativeIds)
            assertEquals(1, f.enqueues)
            assertEquals(old, f.base.records.value.last())
            assertEquals(
                f.device,
                assertIs<CollaborationMessageState.Staged>(f.record.state).device,
            )
            assertEquals(1, f.closes)
        }
    }

    @Test
    fun anUnknownCurrentEchoStillPreventsNewEnqueueDespiteHistoricalStaging() = runTest {
        val f = StageFixture()
        f.prepare()
        val old =
            f.record.copy(
                intent = f.record.intent.copy(id = deliveryId(998)),
                revision = 1,
                state =
                    CollaborationMessageState.Staging(
                        f.device.copy(nativeSessionId = deliveryId(999)),
                        setOf("old-before"),
                        f.origin,
                    ),
            )
        f.base.records.value += old
        f.nativeIds += "unknown-current"
        assertIs<AppResult.Failed>(f.stage())
        assertEquals(CollaborationMessageState.Queued, f.record.state)
        assertEquals(old, f.base.records.value.last())
        assertEquals(0, f.enqueues)
        assertEquals(0, f.preparations)
        assertEquals(1, f.closes)
    }

    @Test
    fun malformedHistoricalStateCannotBeSkippedToEnableEitherOperation() = runTest {
        val delivery = CollaborationDeliveryFixture()
        delivery.prepare(2)
        val old =
            delivery.records
                .last()
                .copy(
                    state =
                        CollaborationMessageState.Staged(
                            delivery.device.copy(nativeSessionId = "invalid"),
                            "old-transaction",
                        )
                )
        delivery.base.records.value = listOf(old, delivery.records.first())
        assertIs<AppResult.Failed>(delivery.deliver())
        assertEquals(0, delivery.windows)
        assertTrue(delivery.journal.isEmpty())
        assertEquals(old, delivery.records.first())

        val staging = StageFixture()
        staging.prepare()
        val invalid =
            staging.record.copy(
                intent = staging.record.intent.copy(id = deliveryId(998)),
                revision = 1,
                state =
                    CollaborationMessageState.Staging(
                        staging.device.copy(nativeSessionId = "invalid"),
                        emptySet(),
                        staging.origin,
                    ),
            )
        staging.base.records.value += invalid
        assertIs<AppResult.Failed>(staging.stage())
        assertEquals(0, staging.enqueues)
        assertEquals(0, staging.preparations)
        assertEquals(invalid, staging.base.records.value.last())
    }
}
