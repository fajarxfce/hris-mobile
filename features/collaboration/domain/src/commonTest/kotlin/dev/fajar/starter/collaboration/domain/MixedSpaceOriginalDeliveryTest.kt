@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class MixedSpaceOriginalDeliveryTest {
    @Test
    fun olderBootstrapStagingCannotBeIgnoredBeforeResumingThePhysicalQueue() = runTest {
        for (company in listOf(false, true)) {
            val f = MixedSpaceDeliveryFixture()
            val id = f.base.queue.id(995)
            val state =
                CollaborationMessageState.Staging(
                    f.base.queue.device.copy(
                        bootstrapOperationId = f.base.queue.id(994),
                        nativeSessionId = f.base.queue.id(993),
                    ),
                    emptySet(),
                    f.original.origin,
                )
            if (company) {
                val previous = f.base.queue.record
                f.base.records[id] =
                    previous.copy(intent = previous.intent.copy(id = id), state = state)
            } else
                f.space.records[id] =
                    f.record.copy(intent = f.record.intent.copy(id = id), state = state)
            assertIs<AppResult.Failed>(f.deliver())
            f.noSend()
            assertTrue(f.space.journal.isEmpty())
            assertTrue(f.base.journal.isEmpty())
        }
    }

    @Test
    fun oneResumeConfirmsBothNamespacesWithoutMovingEitherOriginal() = runTest {
        val f = MixedSpaceDeliveryFixture()
        val report = assertIs<AppResult.Success<CollaborationDeliveryReport>>(f.deliver()).value
        assertEquals(setOf(f.record.intent.id, f.base.queue.record.intent.id), report.confirmed)
        assertTrue(report.unresolved.isEmpty())
        assertEquals(f.record.intent, f.sent().intent)
        assertEquals(
            f.base.queue.record.intent,
            f.base.records.getValue(f.base.queue.record.intent.id).intent,
        )
        assertEquals(
            f.original.transactionId,
            assertIs<CollaborationMessageState.Sent>(f.sent().state).transactionId,
        )
        assertEquals(listOf(f.receipt()), f.space.journal)
        assertEquals(listOf(f.base.receipt()), f.base.journal)
        assertEquals(1, f.base.count("resume"))
        assertEquals(1, f.base.count("close"))
    }

    @Test
    fun newSpaceRoomNeverReadsOrWritesTheCompanyPartition() = runTest {
        val f = MixedSpaceDeliveryFixture()
        f.newRoom()
        val result = assertIs<AppResult.Success<CollaborationDeliveryReport>>(f.deliver()).value
        assertEquals(setOf(f.record.intent.id), result.confirmed)
        for (phase in
            listOf(
                "observe",
                "get",
                "journal",
                "completion",
                "append",
                "update",
                "localHistory",
            )) assertEquals(0, f.base.count(phase), phase)
        assertEquals(f.base.queue.record, f.base.records.getValue(f.base.queue.record.intent.id))
    }

    @Test
    fun sameDeviceStagingAbsentFromSdkBlocksAnyResume() = runTest {
        for (company in listOf(false, true)) {
            val f = MixedSpaceDeliveryFixture()
            val state =
                CollaborationMessageState.Staging(
                    f.base.queue.device,
                    emptySet(),
                    f.original.origin,
                )
            if (company) {
                val r = f.base.queue.record
                f.base.records[f.base.queue.id(995)] =
                    r.copy(intent = r.intent.copy(id = f.base.queue.id(995)), state = state)
            } else
                f.space.records[f.base.queue.id(995)] =
                    f.record.copy(
                        intent = f.record.intent.copy(id = f.base.queue.id(995)),
                        state = state,
                    )
            assertIs<AppResult.Failed>(f.deliver())
            f.noSend()
            assertTrue(f.space.journal.isEmpty())
            assertTrue(f.base.journal.isEmpty())
        }
    }

    @Test
    fun duplicateLogicalOwnershipRejectsBothLedgersBeforeWrites() = runTest {
        val f = MixedSpaceDeliveryFixture()
        val old = f.base.queue.record
        f.base.records[f.record.intent.id] =
            old.copy(intent = old.intent.copy(id = f.record.intent.id), state = f.record.state)
        assertIs<AppResult.Failed>(f.deliver())
        f.noSend()
        assertEquals(0, f.base.count("update"))
        assertEquals(0, f.base.count("space-update"))
    }

    @Test
    fun unknownNativeOriginalCannotBeSkippedToDeliverKnownMessages() = runTest {
        val f = MixedSpaceDeliveryFixture()
        f.base.snapshot =
            f.base.snapshot.copy(
                original =
                    f.base.snapshot.original.copy(
                        messages =
                            f.base.snapshot.original.messages +
                                f.original.copy(
                                    logicalId = f.base.queue.id(996),
                                    transactionId = "unowned",
                                )
                    )
            )
        assertIs<AppResult.Failed>(f.deliver())
        f.noSend()
    }

    @Test
    fun invalidSpaceJournalPreventsAnOtherwiseValidCompanyProjection() = runTest {
        val f = MixedSpaceDeliveryFixture()
        f.base.journal = listOf(f.base.receipt())
        f.space.journal = listOf(f.receipt().copy(transactionId = "wrong"))
        assertIs<AppResult.Failed>(f.deliver())
        f.noSend()
        assertEquals(f.base.queue.record, f.base.records.getValue(f.base.queue.record.intent.id))
        assertEquals(0, f.base.count("update"))
    }

    @Test
    fun oneEventCannotBelongToBothCompleteJournals() = runTest {
        val f = MixedSpaceDeliveryFixture()
        f.base.journal = listOf(f.base.receipt())
        f.space.journal = listOf(f.receipt().copy(eventIds = listOf("\$accepted")))
        assertIs<AppResult.Failed>(f.deliver())
        f.noSend()
        assertEquals(0, f.base.count("update"))
        assertEquals(0, f.base.count("space-update"))
    }

    @Test
    fun validSpaceJournalAndArchiveSettleOriginalWithoutRecipientReadiness() = runTest {
        for (archive in listOf(false, true)) {
            val f = MixedSpaceDeliveryFixture()
            f.base.observed = f.base.observed.copy(recipientReady = false)
            val sent = f.source.sent()
            if (archive) {
                f.space.records[sent.intent.id] = sent
                f.space.archived[sent.intent.id] =
                    CollaborationSpaceMessageCompletion(sent, listOf("\$space-accepted"))
            } else f.space.journal = listOf(f.receipt())
            val report = assertIs<AppResult.Success<CollaborationDeliveryReport>>(f.deliver()).value
            assertEquals(setOf(f.record.intent.id), report.confirmed)
            assertEquals(setOf(f.base.queue.record.intent.id), report.unresolved)
            assertEquals(0, f.base.count("resume"))
            assertEquals(1, f.base.count("settle"))
        }
    }

    @Test
    fun failedAndFalseCommittedSpaceCasAreRecoveredByExactReadback() = runTest {
        for (response in
            listOf(
                AppResult.Success(false),
                AppResult.Failed(Failure(FailureKind.Storage, "lost response")),
            )) {
            val f = MixedSpaceDeliveryFixture()
            f.space.updateResponse = response
            assertIs<AppResult.Success<CollaborationDeliveryReport>>(f.deliver())
            assertIs<CollaborationMessageState.Sent>(f.sent().state)
            assertEquals(1, f.base.count("space-update"))
        }
    }

    @Test
    fun uncommittedSpaceProjectionRetainsBothReceiptsForNextInvocation() = runTest {
        val f = MixedSpaceDeliveryFixture()
        f.space.commitUpdate = false
        assertIs<AppResult.Failed>(f.deliver())
        assertEquals(f.record, f.sent())
        assertEquals(listOf(f.receipt()), f.space.journal)
        assertIs<CollaborationMessageState.Sent>(
            f.base.records.getValue(f.base.queue.record.intent.id).state
        )
        f.space.commitUpdate = true
        val report = assertIs<AppResult.Success<CollaborationDeliveryReport>>(f.deliver()).value
        assertEquals(2, report.confirmed.size)
        assertEquals(1, f.base.count("resume"))
    }

    @Test
    fun secondJournalFailurePreservesFirstAndRecoversMissingSpaceReceiptFromOneWindow() = runTest {
        val f = MixedSpaceDeliveryFixture()
        f.space.commitAppend = false
        f.space.appendResponse = AppResult.Failed(Failure(FailureKind.Storage, "lost second write"))
        assertIs<AppResult.Failed>(f.deliver())
        assertEquals(listOf(f.base.receipt()), f.base.journal)
        assertTrue(f.space.journal.isEmpty())
        f.space.commitAppend = true
        f.space.appendResponse = AppResult.Success(true)
        f.base.history.publish(listOf(f.spaceEvent()))
        val report = assertIs<AppResult.Success<CollaborationDeliveryReport>>(f.deliver()).value
        assertEquals(2, report.confirmed.size)
        assertEquals(1, f.base.count("originalHistory"))
        assertEquals(1, f.base.count("resume"))
    }

    @Test
    fun committedSecondJournalLostResponseRecoversLocallyWithoutResend() = runTest {
        val f = MixedSpaceDeliveryFixture()
        f.space.appendResponse = AppResult.Failed(Failure(FailureKind.Storage, "lost response"))
        assertIs<AppResult.Failed>(f.deliver())
        assertEquals(listOf(f.receipt()), f.space.journal)
        f.space.appendResponse = AppResult.Success(true)
        assertIs<AppResult.Success<CollaborationDeliveryReport>>(f.deliver())
        assertEquals(1, f.base.count("resume"))
        assertEquals(0, f.base.count("originalHistory"))
    }

    @Test
    fun disappearedOriginalsAcrossBothLedgersShareOneHistoryWindow() = runTest {
        val f = MixedSpaceDeliveryFixture()
        f.base.history.loseSdkOriginals()
        f.base.history.publish(listOf(f.base.history.event(), f.spaceEvent()))
        val report = assertIs<AppResult.Success<CollaborationDeliveryReport>>(f.deliver()).value
        assertEquals(2, report.confirmed.size)
        assertEquals(1, f.base.count("localHistory"))
        assertEquals(1, f.base.count("originalHistory"))
        f.noSend()
    }

    @Test
    fun absentHistoryIsUnresolvedAndNeverCreatesAnotherOriginal() = runTest {
        val f = MixedSpaceDeliveryFixture()
        f.base.history.loseSdkOriginals()
        val report = assertIs<AppResult.Success<CollaborationDeliveryReport>>(f.deliver()).value
        assertEquals(2, report.unresolved.size)
        assertTrue(report.confirmed.isEmpty())
        assertEquals(CollaborationQueueDeliveryReason.UNRESOLVED, report.nativeReason)
        assertEquals(1, f.base.count("originalHistory"))
        f.noSend()
    }

    @Test
    fun wrongSpaceSenderHashOrBodyNeverConfirmsTheRetainedIntent() = runTest {
        for (variant in 0..2) {
            val f = MixedSpaceDeliveryFixture()
            f.base.history.loseSdkOriginals()
            val event =
                when (variant) {
                    0 -> f.spaceEvent().copy(sender = "@other:matrix.test")
                    1 -> f.spaceEvent().copy(contentHash = "b".repeat(64))
                    else -> f.spaceEvent().copy(text = "different")
                }
            f.base.history.publish(listOf(event))
            val report = assertIs<AppResult.Success<CollaborationDeliveryReport>>(f.deliver()).value
            assertTrue(report.confirmed.isEmpty())
            assertEquals(f.record, f.sent())
            f.noSend()
        }
    }

    @Test
    fun lateDenialAtEverySpaceReadStopsResumeAndClosesTheOwnedWriter() = runTest {
        for (phase in listOf("space-observe", "space-journal", "space-get")) {
            val f = MixedSpaceDeliveryFixture()
            f.base.hooks[phase] = { f.base.directory.deny() }
            assertEquals(f.base.directory.denied, assertIs<AppResult.Failed>(f.deliver()).failure)
            f.noSend()
            assertEquals(1, f.base.count("close"))
            assertEquals(1, f.base.count("closeSpace"))
        }
    }

    @Test
    fun denialDuringObservedCallbackStoresFactsWithoutPublishingEitherState() = runTest {
        val f = MixedSpaceDeliveryFixture()
        f.base.onResume = { observer ->
            f.base.directory.deny()
            observer(f.progress())
            AppResult.Success(f.progress())
        }
        assertEquals(f.base.directory.denied, assertIs<AppResult.Failed>(f.deliver()).failure)
        assertEquals(listOf(f.receipt()), f.space.journal)
        assertEquals(listOf(f.base.receipt()), f.base.journal)
        assertEquals(f.record, f.sent())
        assertEquals(f.base.queue.record, f.base.records.getValue(f.base.queue.record.intent.id))
    }

    @Test
    fun cancellationRetainsLateCallbacksInTheirCapturedNamespaces() = runTest {
        val f = MixedSpaceDeliveryFixture()
        val callback =
            CompletableDeferred<suspend (CollaborationQueueDelivery) -> AppResult<Unit>>()
        f.base.onResume = { observer ->
            callback.complete(observer)
            awaitCancellation()
        }
        val task = launch { f.deliver() }
        val captured = callback.await()
        task.cancelAndJoin()
        f.base.native.setup.session = null
        assertIs<AppResult.Success<Unit>>(captured(f.progress()))
        assertEquals(listOf(f.receipt()), f.space.journal)
        assertEquals(listOf(f.base.receipt()), f.base.journal)
        assertEquals(f.record, f.sent())
        assertEquals(1, f.base.count("close"))
    }

    @Test
    fun duplicateAndLateAliasesKeepBothOriginalTransactionMappings() = runTest {
        val f = MixedSpaceDeliveryFixture()
        f.base.onResume = { observer ->
            assertIs<AppResult.Success<Unit>>(observer(f.progress()))
            val late =
                f.progress()
                    .copy(
                        receipts =
                            listOf(
                                CollaborationQueueReceipt(f.original.transactionId, "\$late-space")
                            )
                    )
            assertIs<AppResult.Success<Unit>>(observer(late))
            f.base.history.loseSdkOriginals()
            AppResult.Success(late)
        }
        assertIs<AppResult.Success<CollaborationDeliveryReport>>(f.deliver())
        assertEquals(listOf("\$space-accepted", "\$late-space"), f.space.journal.single().eventIds)
        assertEquals(1, f.base.count("space-update"))
        assertEquals(1, f.base.count("update"))
    }

    @Test
    fun laterCallbackCannotReuseAnEventAlreadyOwnedByTheOtherLedger() = runTest {
        val f = MixedSpaceDeliveryFixture()
        f.base.onResume = { observer ->
            assertIs<AppResult.Success<Unit>>(observer(f.progress()))
            val late =
                f.progress()
                    .copy(
                        receipts =
                            listOf(
                                CollaborationQueueReceipt(f.original.transactionId, "\$accepted")
                            )
                    )
            val rejected = assertIs<AppResult.Failed>(observer(late))
            rejected
        }
        assertIs<AppResult.Failed>(f.deliver())
        assertEquals(listOf(f.receipt()), f.space.journal)
        assertEquals(1, f.base.count("space-append"))
    }

    @Test
    fun oneInvalidReceiptInCallbackRejectsBothWrites() = runTest {
        val f = MixedSpaceDeliveryFixture()
        f.base.onResume = { observer ->
            observer(
                f.progress()
                    .copy(
                        receipts =
                            f.progress().receipts +
                                CollaborationQueueReceipt("unknown", "\$invalid")
                    )
            )
            AppResult.Failed(Failure(FailureKind.Conflict, "rejected"))
        }
        assertIs<AppResult.Failed>(f.deliver())
        assertTrue(f.space.journal.isEmpty())
        assertTrue(f.base.journal.isEmpty())
    }

    @Test
    fun competingInvocationDoesNotAcquireAnotherWriterAndCancellationReleasesIt() = runTest {
        val f = MixedSpaceDeliveryFixture()
        val entered = CompletableDeferred<Unit>()
        f.base.hooks["space-journal"] = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val first = launch { f.deliver() }
        entered.await()
        assertIs<AppResult.Failed>(f.deliver())
        assertEquals(1, f.base.count("acquire"))
        first.cancelAndJoin()
        f.base.hooks.clear()
        assertIs<AppResult.Success<CollaborationDeliveryReport>>(f.deliver())
        assertEquals(2, f.base.count("close"))
    }

    @Test
    fun preparationAndDeliveryStorageRemainBounded() = runTest {
        for (phase in listOf("space-journal", "space-update")) {
            val f = MixedSpaceDeliveryFixture()
            val entered = CompletableDeferred<Unit>()
            f.base.hooks[phase] = {
                entered.complete(Unit)
                awaitCancellation()
            }
            val task = async { f.deliver() }
            entered.await()
            advanceTimeBy(45_001)
            runCurrent()
            assertTrue(task.isCompleted)
            assertIs<AppResult.Failed>(task.await())
            assertEquals(1, f.base.count("close"))
        }
    }

    @Test
    fun cancellationAfterSpaceCasKeepsItsCommittedStateAndOriginalReceipt() = runTest {
        val f = MixedSpaceDeliveryFixture()
        f.base.hooks["space-after-update"] = { throw CancellationException("caller gone") }
        assertFailsWith<CancellationException> { f.deliver() }
        assertIs<CollaborationMessageState.Sent>(f.sent().state)
        assertEquals(listOf(f.receipt()), f.space.journal)
        assertEquals(1, f.base.count("close"))
    }

    @Test
    fun falselySettledInventoryPreventsResumeOfEitherNamespace() = runTest {
        val f = MixedSpaceDeliveryFixture()
        f.space.journal = listOf(f.receipt())
        f.removeSettled = false
        assertIs<AppResult.Failed>(f.deliver())
        assertEquals(0, f.base.count("resume"))
        assertEquals(1, f.base.count("close"))
    }

    @Test
    fun changedRoomBindingBeforeResumeCannotReuseTheOriginalCapture() = runTest {
        val f = MixedSpaceDeliveryFixture()
        f.base.hooks["binding"] = { if (f.base.count("binding") > 1) f.base.retained = null }
        assertIs<AppResult.Failed>(f.deliver())
        f.noSend()
    }
}
