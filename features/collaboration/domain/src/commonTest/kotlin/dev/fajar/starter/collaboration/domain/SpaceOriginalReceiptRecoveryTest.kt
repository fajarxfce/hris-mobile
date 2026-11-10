@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class SpaceOriginalReceiptRecoveryTest {
    @Test
    fun foreignCommandProvenanceIsRejectedBeforeAnyHistoryRead() = runTest {
        for (variant in 0..2) {
            val f = SpaceOriginalDeliveryFixture()
            f.history.loseSdkOriginals()
            val record = f.queue.record
            val state = record.state as CollaborationMessageState.Staged
            f.records[record.intent.id] =
                when (variant) {
                    0 ->
                        record.copy(
                            state =
                                state.copy(
                                    origin =
                                        requireNotNull(state.origin)
                                            .copy(sender = "@foreign:example.test")
                                )
                        )
                    1 -> record.copy(intent = record.intent.copy(conversationId = f.queue.id(300)))
                    else ->
                        record.copy(
                            intent =
                                record.intent.copy(
                                    owner = record.intent.owner.copy(accountId = f.queue.id(300))
                                )
                        )
                }
            assertIs<AppResult.Failed>(f.deliver())
            assertEquals(0, f.count("localHistory"))
            assertEquals(0, f.count("originalHistory"))
            assertTrue(f.journal.isEmpty())
        }
    }

    @Test
    fun disappearedOriginalWithServerEvidenceRecoversWithoutReenqueueOrResume() = runTest {
        val f = SpaceOriginalDeliveryFixture()
        f.history.loseSdkOriginals()
        f.history.publish()
        val report = assertIs<AppResult.Success<CollaborationDeliveryReport>>(f.deliver()).value
        assertEquals(setOf(f.queue.record.intent.id), report.confirmed)
        assertTrue(report.unresolved.isEmpty())
        assertEquals(listOf(f.receipt()), f.journal)
        assertEquals(f.queue.sent(), f.records[f.queue.record.intent.id])
        assertEquals(1, f.count("originalHistory"))
        assertEquals(0, f.count("resume"))
        assertEquals(0, f.count("settle"))
    }

    @Test
    fun retainedOriginalAvoidsProviderReadAndDoesNotRequireRecipientReadiness() = runTest {
        val f = SpaceOriginalDeliveryFixture()
        f.history.loseSdkOriginals()
        f.history.retain()
        f.observed = f.observed.copy(recipientReady = false)
        assertIs<AppResult.Success<CollaborationDeliveryReport>>(f.deliver())
        assertEquals(listOf(f.receipt()), f.journal)
        assertEquals(1, f.count("localHistory"))
        assertEquals(0, f.count("originalHistory"))
        assertEquals(0, f.count("resume"))
    }

    @Test
    fun queuedAndAlreadyJournaledOriginalsNeverAcquireHistory() = runTest {
        for (journaled in listOf(false, true)) {
            val f = SpaceOriginalDeliveryFixture()
            if (journaled) f.journal = listOf(f.receipt())
            assertIs<AppResult.Success<CollaborationDeliveryReport>>(f.deliver())
            assertEquals(0, f.count("localHistory"))
            assertEquals(0, f.count("originalHistory"))
        }
    }

    @Test
    fun emptyHistoryLeavesMessageUnresolvedWithoutPollingOrRetirement() = runTest {
        val f = SpaceOriginalDeliveryFixture()
        f.history.loseSdkOriginals()
        val report = assertIs<AppResult.Success<CollaborationDeliveryReport>>(f.deliver()).value
        assertEquals(setOf(f.queue.record.intent.id), report.unresolved)
        assertTrue(f.journal.isEmpty())
        assertEquals(1, f.count("originalHistory"))
        advanceTimeBy(120_000)
        assertEquals(1, f.count("originalHistory"))
        assertEquals(0, f.count("update"))
        assertTrue(f.archived.isEmpty())
    }

    @Test
    fun foreignRuntimePhysicalOwnerDeviceRoomOrAnchorCannotPublishEvidence() = runTest {
        for (variant in 0..7) {
            val f = SpaceOriginalDeliveryFixture()
            f.history.loseSdkOriginals()
            f.history.publish()
            val value = f.history.snapshot
            f.history.snapshot =
                when (variant) {
                    0 -> value.copy(runtime = value.runtime.copy(operationId = f.queue.id(300)))
                    1 ->
                        value.copy(
                            head =
                                value.head.copy(
                                    owner = f.history.physical.copy(sessionId = "other")
                                )
                        )
                    2 ->
                        value.copy(
                            head =
                                value.head.copy(
                                    owner = f.history.physical.copy(companyId = f.queue.id(300))
                                )
                        )
                    3 ->
                        value.copy(
                            head =
                                value.head.copy(
                                    owner =
                                        f.history.physical.copy(nativeSessionId = f.queue.id(300))
                                )
                        )
                    4 -> value.copy(head = value.head.copy(deviceId = f.queue.runtime.deviceId))
                    5 -> value.copy(head = value.head.copy(roomId = "!other:example.test"))
                    6 -> value.copy(head = value.head.copy(eventId = "\$other"))
                    else -> value.copy(window = null)
                }
            assertIs<AppResult.Failed>(f.deliver())
            assertTrue(f.journal.isEmpty())
            assertEquals(0, f.count("update"))
            assertEquals(1, f.count("close"))
        }
    }

    @Test
    fun sameLogicalIdWithWrongSenderOriginalHashOrTextNeverConfirms() = runTest {
        for (variant in 0..3) {
            val f = SpaceOriginalDeliveryFixture()
            f.history.loseSdkOriginals()
            val event = f.history.event()
            f.history.publish(
                listOf(
                    when (variant) {
                        0 -> event.copy(sender = "@other:example.test")
                        1 -> event.copy(contentHash = "b".repeat(64))
                        2 -> event.copy(text = "Different original")
                        else -> event.copy(logicalId = f.queue.id(300))
                    }
                )
            )
            val report = assertIs<AppResult.Success<CollaborationDeliveryReport>>(f.deliver()).value
            assertEquals(setOf(f.queue.record.intent.id), report.unresolved)
            assertTrue(f.journal.isEmpty())
        }
    }

    @Test
    fun redactionAloneCannotSupplyUnknownOriginalContent() = runTest {
        val f = SpaceOriginalDeliveryFixture()
        f.history.loseSdkOriginals()
        f.history.publish(
            listOf(CollaborationHistoryChange.Redacted("\$accepted", f.queue.origin.sender))
        )
        val report = assertIs<AppResult.Success<CollaborationDeliveryReport>>(f.deliver()).value
        assertTrue(report.confirmed.isEmpty())
        assertTrue(f.journal.isEmpty())
    }

    @Test
    fun conflictingRetainedHistoryFailsWithoutProviderFallback() = runTest {
        val f = SpaceOriginalDeliveryFixture()
        f.history.loseSdkOriginals()
        f.history.retain()
        val id = f.queue.record.intent.id
        f.history.local[id] = f.history.local.getValue(id).copy(contentHash = "b".repeat(64))
        assertIs<AppResult.Failed>(f.deliver())
        assertEquals(0, f.count("originalHistory"))
        assertTrue(f.journal.isEmpty())
    }

    @Test
    fun denialOrReplacedRuntimeDuringAcquisitionPreventsJournalAndProjection() = runTest {
        for (denied in listOf(false, true)) {
            val f = SpaceOriginalDeliveryFixture()
            f.history.loseSdkOriginals()
            f.history.publish()
            f.hooks["originalHistory"] = { if (denied) f.directory.deny() else f.open = false }
            assertIs<AppResult.Failed>(f.deliver())
            assertTrue(f.journal.isEmpty())
            assertEquals(0, f.count("update"))
            assertEquals(if (denied) 1 else 0, f.count("closeSpace"))
            assertEquals(1, f.count("close"))
        }
    }

    @Test
    fun uncertainHistoryFailureHasNoAutomaticRetryAndAllowsLaterExplicitRecovery() = runTest {
        val f = SpaceOriginalDeliveryFixture()
        f.history.loseSdkOriginals()
        f.history.publish()
        f.failures["originalHistory"] = f.directory.storageFailure
        assertIs<AppResult.Failed>(f.deliver())
        assertEquals(1, f.count("originalHistory"))
        assertTrue(f.journal.isEmpty())
        f.failures.clear()
        assertIs<AppResult.Success<CollaborationDeliveryReport>>(f.deliver())
        assertEquals(listOf(f.receipt()), f.journal)
        assertEquals(2, f.count("originalHistory"))
    }

    @Test
    fun cancellationAndPreparationDeadlineCloseTheOwnedWriter() = runTest {
        for (cancel in listOf(false, true)) {
            val f = SpaceOriginalDeliveryFixture()
            f.history.loseSdkOriginals()
            val entered = CompletableDeferred<Unit>()
            var stopped = false
            f.hooks["originalHistory"] = {
                entered.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    stopped = true
                }
            }
            val attempt = async { f.deliver() }
            entered.await()
            if (cancel) attempt.cancelAndJoin()
            else {
                advanceTimeBy(15_001)
                runCurrent()
                assertEquals(
                    FailureKind.Timeout,
                    assertIs<AppResult.Failed>(attempt.await()).failure.kind,
                )
            }
            assertTrue(stopped)
            assertTrue(f.journal.isEmpty())
            assertEquals(1, f.count("close"))
        }
    }

    @Test
    fun failedReceiptWriteNeverProjectsOrRecreatesTheMessage() = runTest {
        val f = SpaceOriginalDeliveryFixture()
        f.history.loseSdkOriginals()
        f.history.publish()
        f.failures["append"] = f.directory.storageFailure
        assertIs<AppResult.Failed>(f.deliver())
        assertTrue(f.journal.isEmpty())
        assertEquals(f.queue.record, f.records[f.queue.record.intent.id])
        assertEquals(0, f.count("resume"))
        assertEquals(0, f.count("update"))
    }

    @Test
    fun oneWindowRecoversManyOriginalsInBoundedJournalBatches() = runTest {
        val f = SpaceOriginalDeliveryFixture()
        f.history.loseSdkOriginals()
        val first = f.queue.record
        val staged = first.state as CollaborationMessageState.Staged
        val records =
            (0..32).map { i ->
                first.copy(
                    intent = first.intent.copy(id = f.queue.id(400 + i)),
                    state = staged.copy(transactionId = "original-$i"),
                )
            }
        f.records.clear()
        records.forEach { f.records[it.intent.id] = it }
        f.history.publish(records.mapIndexed { i, record -> f.history.event(record, "\$event-$i") })
        val result = assertIs<AppResult.Success<CollaborationDeliveryReport>>(f.deliver()).value
        assertEquals(records.map { it.intent.id }.toSet(), result.confirmed)
        assertEquals(33, f.journal.size)
        assertEquals(2, f.count("append"))
        assertEquals(1, f.count("originalHistory"))
        assertEquals(0, f.count("resume"))
    }

    @Test
    fun duplicateProviderAliasesKeepOneOriginalWithoutChangingItsTransaction() = runTest {
        val f = SpaceOriginalDeliveryFixture()
        f.history.loseSdkOriginals()
        val event = f.history.event()
        f.history.publish(listOf(event, event.copy(eventId = "\$alias", timestamp = 2)))
        assertIs<AppResult.Success<CollaborationDeliveryReport>>(f.deliver())
        assertEquals(listOf("\$accepted", "\$alias"), f.journal.single().eventIds)
        assertEquals(f.queue.original.transactionId, f.journal.single().transactionId)
        assertEquals(0, f.count("resume"))
    }
}
