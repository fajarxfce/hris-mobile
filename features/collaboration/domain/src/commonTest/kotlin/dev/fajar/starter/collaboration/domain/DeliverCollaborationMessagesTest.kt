package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class DeliverCollaborationMessagesTest {
    @Test
    fun onlyOriginalMappingsResumeAndEveryAliasIsJournaledBeforeSent() = runTest {
        val f = CollaborationDeliveryFixture()
        f.prepare()
        f.window =
            f.window.copy(
                receipts =
                    listOf(
                        CollaborationQueueReceipt("original", "\$first"),
                        CollaborationQueueReceipt("original", "\$retry"),
                    )
            )
        val result = assertIs<AppResult.Success<CollaborationDeliveryReport>>(f.deliver()).value
        assertEquals(setOf("original"), f.knownTransactions)
        assertEquals(listOf("\$first", "\$retry"), f.journal.single().eventIds)
        assertEquals(setOf(f.records.single().intent.id), result.confirmed)
        assertTrue(result.unresolved.isEmpty())
        assertEquals(CollaborationQueueDeliveryReason.DRAINED, result.nativeReason)
        assertEquals(listOf("resume", "journal", "project", "close"), f.events)
        assertEquals(1, f.windows)
    }

    @Test
    fun emptyNativeQueueDoesNotInventReceiptForUncertainOriginalTransaction() = runTest {
        val f = CollaborationDeliveryFixture()
        f.prepare()
        f.window = f.window.copy(receipts = emptyList())
        val result = assertIs<AppResult.Success<CollaborationDeliveryReport>>(f.deliver()).value
        assertEquals(CollaborationQueueDeliveryReason.UNRESOLVED, result.nativeReason)
        assertEquals(setOf(f.records.single().intent.id), result.unresolved)
        assertIs<CollaborationMessageState.Staged>(f.records.single().state)
        assertTrue(f.journal.isEmpty())
        assertEquals(0, f.projections)
    }

    @Test
    fun pendingStagingAndForeignNativeFamilyKeepRoomPaused() = runTest {
        for (foreign in listOf(false, true)) {
            val f = CollaborationDeliveryFixture()
            f.prepare()
            val state =
                if (foreign)
                    CollaborationMessageState.Staged(
                        f.device.copy(nativeSessionId = deliveryId(999)),
                        "original",
                    )
                else CollaborationMessageState.Staging(f.device, emptySet())
            f.base.records.value = f.records.map { it.copy(state = state) }
            assertIs<AppResult.Failed>(f.deliver())
            assertEquals(0, f.windows)
            assertEquals(1, f.closes)
        }
    }

    @Test
    fun queuedUnmappedTextNeverEnablesNativeQueue() = runTest {
        val f = CollaborationDeliveryFixture()
        f.prepare()
        f.base.records.value =
            f.records.map { it.copy(revision = 0, state = CollaborationMessageState.Queued) }
        val result = assertIs<AppResult.Success<CollaborationDeliveryReport>>(f.deliver()).value
        assertNull(result.nativeReason)
        assertEquals(1, result.unresolved.size)
        assertEquals(0, f.windows)
        assertEquals(1, f.closes)
    }

    @Test
    fun expiredAdmissionAndKnownCompanyDenialNeverAcquireWriter() = runTest {
        val expired = CollaborationDeliveryFixture()
        expired.prepare()
        expired.base.setup.now += 365.days
        assertIs<AppResult.Failed>(expired.deliver())
        assertEquals(0, expired.acquisitions)
        val denied = CollaborationDeliveryFixture()
        denied.prepare()
        denied.base.gate.value = CollaborationListAdmission("denied", denied.base.denied)
        assertEquals(denied.base.denied, assertIs<AppResult.Failed>(denied.deliver()).failure)
        assertEquals(0, denied.acquisitions)
    }

    @Test
    fun lateOwnerChangeClosesAcquiredWriterWithoutEnablingDelivery() = runTest {
        val f = CollaborationDeliveryFixture()
        f.prepare()
        f.afterAcquire = { f.base.setup.session = null }
        assertIs<AppResult.Failed>(f.deliver())
        assertEquals(0, f.windows)
        assertEquals(1, f.closes)
    }

    @Test
    fun authorityExpiryAfterAcknowledgementRetainsFactsForOfflineRecovery() = runTest {
        val f = CollaborationDeliveryFixture()
        f.prepare()
        f.afterResume = { f.base.setup.now += 365.days }
        assertIs<AppResult.Failed>(f.deliver())
        assertEquals(1, f.journal.size)
        assertIs<CollaborationMessageState.Staged>(f.records.single().state)
        assertNull(f.base.gate.value.denial)
        assertEquals(AppResult.Success(1), f.reconcile())
        assertIs<CollaborationMessageState.Sent>(f.records.single().state)
        assertEquals(1, f.windows)
    }

    @Test
    fun lateCompanyDenialKeepsObservedFactsButCannotPublishOrProject() = runTest {
        val f = CollaborationDeliveryFixture()
        f.prepare()
        f.afterResume = { f.base.gate.value = CollaborationListAdmission("denied", f.base.denied) }
        assertEquals(f.base.denied, assertIs<AppResult.Failed>(f.deliver()).failure)
        assertEquals(1, f.journal.size)
        assertEquals(0, f.projections)
        assertEquals(f.base.denied, assertIs<AppResult.Failed>(f.reconcile()).failure)
        assertEquals(1, f.closes)
    }

    @Test
    fun replacementAccountCannotReceiveOriginalAcknowledgement() = runTest {
        val f = CollaborationDeliveryFixture()
        f.prepare()
        f.afterResume = {
            f.base.setup.session =
                f.base.setup.session!!.copy(
                    user = f.base.setup.session!!.user.copy(id = deliveryId(999))
                )
        }
        assertIs<AppResult.Failed>(f.deliver())
        assertTrue(f.journal.isEmpty())
        assertEquals(0, f.projections)
        assertEquals(1, f.closes)
    }

    @Test
    fun cancellationAfterReceivedAckStillJournalsWithinBoundedCleanup() = runTest {
        val f = CollaborationDeliveryFixture()
        f.prepare()
        f.afterResume = { currentCoroutineContext().cancel() }
        val pending = launch { f.deliver() }
        pending.join()
        assertTrue(pending.isCancelled)
        assertEquals(1, f.journal.size)
        assertEquals(0, f.projections)
        assertEquals(1, f.closes)
        assertEquals(AppResult.Success(1), f.reconcile())
    }

    @Test
    fun failedJournalReadNeverEnablesNativeWindow() = runTest {
        val f = CollaborationDeliveryFixture()
        f.prepare()
        f.journalFailure = f.base.storage
        assertEquals(f.base.storage, assertIs<AppResult.Failed>(f.deliver()).failure)
        assertEquals(0, f.windows)
        assertEquals(1, f.closes)
    }

    @Test
    fun lostJournalResponseAndRejectedProjectionKeepOriginalReceiptRecoverable() = runTest {
        for (lostResponse in listOf(false, true)) {
            val f = CollaborationDeliveryFixture()
            f.prepare()
            f.rejectProjection = !lostResponse
            f.appendFailure = if (lostResponse) f.base.storage else null
            assertIs<AppResult.Failed>(f.deliver())
            assertEquals(1, f.journal.size)
            assertIs<CollaborationMessageState.Staged>(f.records.single().state)
            f.rejectProjection = false
            f.appendFailure = null
            assertEquals(AppResult.Success(1), f.reconcile())
            assertEquals(1, f.windows)
        }
    }

    @Test
    fun boundedJournalTimeoutDoesNotReleaseWriterBeforeCleanup() = runTest {
        val f = CollaborationDeliveryFixture()
        f.prepare()
        f.beforeAppend = { awaitCancellation() }
        val result = assertIs<AppResult.Failed>(f.deliver())
        assertEquals("client.collaboration_receipt_pending", result.failure.code)
        assertTrue(f.journal.isEmpty())
        assertEquals(0, f.projections)
        assertEquals(1, f.closes)
        assertEquals(5_000, testScheduler.currentTime)
    }

    @Test
    fun partialNativeFailureRetainsOnlyActuallyAcknowledgedMessages() = runTest {
        val f = CollaborationDeliveryFixture()
        f.prepare(2)
        f.window =
            f.window.copy(pending = setOf("tx-2"), reason = CollaborationQueueDeliveryReason.FAILED)
        val result = assertIs<AppResult.Success<CollaborationDeliveryReport>>(f.deliver()).value
        assertEquals(CollaborationQueueDeliveryReason.FAILED, result.nativeReason)
        assertEquals(setOf(f.records[0].intent.id), result.confirmed)
        assertEquals(setOf(f.records[1].intent.id), result.unresolved)
        assertEquals(1, f.journal.size)
        assertIs<CollaborationMessageState.Staged>(f.records[1].state)
    }

    @Test
    fun appendBatchesRetainPartialCommitAndReconcileWithoutAnotherDelivery() = runTest {
        val f = CollaborationDeliveryFixture()
        f.prepare(33)
        f.window =
            f.window.copy(
                receipts =
                    f.records.mapIndexed { index, record ->
                        CollaborationQueueReceipt(
                            (record.state as CollaborationMessageState.Staged).transactionId,
                            "\$event-$index",
                        )
                    }
            )
        f.rejectAppendAt = 2
        assertIs<AppResult.Failed>(f.deliver())
        assertEquals(32, f.journal.size)
        assertEquals(0, f.projections)
        assertEquals(AppResult.Success(32), f.reconcile())
        assertEquals(32, f.records.count { it.state is CollaborationMessageState.Sent })
        assertEquals(1, f.windows)
    }

    @Test
    fun malformedOrConflictingNativeReceiptCannotConfirmAnotherMessage() = runTest {
        for (unknown in listOf(false, true)) {
            val f = CollaborationDeliveryFixture()
            f.prepare(2)
            f.window =
                f.window.copy(
                    receipts =
                        if (unknown) listOf(CollaborationQueueReceipt("unmapped", "\$first"))
                        else
                            listOf(
                                CollaborationQueueReceipt("original", "\$same"),
                                CollaborationQueueReceipt("tx-2", "\$same"),
                            )
                )
            assertIs<AppResult.Failed>(f.deliver())
            assertTrue(f.journal.isEmpty())
            assertEquals(0, f.projections)
            assertEquals(1, f.closes)
        }
    }

    @Test
    fun postDeliveryCompanyFailureIsRetainedWhileRecipientDenialStaysScoped() = runTest {
        for (company in listOf(false, true)) {
            val f = CollaborationDeliveryFixture()
            f.prepare()
            val denial =
                if (company) f.base.denied
                else
                    Failure(
                        FailureKind.AccessDenied,
                        "Unavailable",
                        code = "collaboration_recipient_unavailable",
                    )
            f.afterResume = { f.control.remoteFailure = denial }
            assertEquals(denial, assertIs<AppResult.Failed>(f.deliver()).failure)
            assertEquals(if (company) denial else null, f.base.gate.value.denial)
            assertEquals(1, f.journal.size)
            assertEquals(0, f.projections)
            assertEquals(1, f.closes)
        }
    }

    @Test
    fun existingPrimaryEventSurvivesNewAliasAndReplay() = runTest {
        val f = CollaborationDeliveryFixture()
        f.prepare()
        f.journal = listOf(f.receipt())
        assertEquals(AppResult.Success(1), f.reconcile())
        f.window =
            f.window.copy(receipts = listOf(CollaborationQueueReceipt("original", "\$second")))
        assertIs<AppResult.Success<*>>(f.deliver())
        assertEquals(listOf("\$first", "\$second"), f.journal.single().eventIds)
        assertEquals(
            "\$first",
            assertIs<CollaborationMessageState.Sent>(f.records.single().state).eventId,
        )
    }
}
