@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class SpaceOriginalDeliveryTest {
    @Test
    fun reorderingPausedInventoryDoesNotInvalidateItsExactOriginalSet() = runTest {
        val f = SpaceOriginalDeliveryFixture()
        val second =
            f.queue.original.copy(transactionId = "second-transaction", logicalId = f.queue.id(102))
        val staged = f.queue.record.state as CollaborationMessageState.Staged
        val record =
            f.queue.record.copy(
                intent = f.queue.record.intent.copy(id = requireNotNull(second.logicalId)),
                state = staged.copy(transactionId = second.transactionId),
            )
        f.records[record.intent.id] = record
        f.snapshot =
            f.snapshot.copy(
                original = f.snapshot.original.copy(messages = listOf(f.queue.original, second))
            )
        f.observed = f.observed.copy(recipientReady = false)
        f.hooks["snapshot"] = {
            if (f.count("snapshot") == 2)
                f.snapshot =
                    f.snapshot.copy(
                        original =
                            f.snapshot.original.copy(
                                messages = f.snapshot.original.messages.reversed()
                            )
                    )
        }
        val result = assertIs<AppResult.Success<CollaborationDeliveryReport>>(f.deliver()).value
        assertEquals(setOf(f.queue.record.intent.id, record.intent.id), result.unresolved)
        assertEquals(0, f.count("resume"))
    }

    @Test
    fun capturedDenialDuringCallbackKeepsReceiptButSuppressesProjection() = runTest {
        val f = SpaceOriginalDeliveryFixture()
        f.onResume = { observer ->
            f.directory.deny()
            val saved = assertIs<AppResult.Failed>(observer(f.delivery()))
            assertEquals(f.directory.denied, saved.failure)
            saved
        }
        assertEquals(f.directory.denied, assertIs<AppResult.Failed>(f.deliver()).failure)
        assertEquals(listOf(f.receipt()), f.journal)
        assertEquals(f.queue.record, f.records[f.queue.record.intent.id])
        assertEquals(1, f.count("close"))
        assertEquals(1, f.count("closeSpace"))
    }

    @Test
    fun unrelatedOwnedArchiveReceiptDoesNotBorrowOrReplaceCapturedOriginal() = runTest {
        val f = SpaceOriginalDeliveryFixture()
        val source = f.queue.record
        val staged = source.state as CollaborationMessageState.Staged
        val previous =
            source.copy(
                intent = source.intent.copy(id = f.queue.id(103)),
                state = staged.copy(transactionId = "previous-transaction"),
            )
        val sent = f.queue.sent(previous, "\$previous")
        f.records[sent.intent.id] = sent
        f.archived[sent.intent.id] = CollaborationMessageCompletion(sent, listOf("\$previous"))
        f.journal =
            listOf(
                CollaborationMessageReceipt(
                    sent.intent,
                    f.queue.device,
                    "previous-transaction",
                    listOf("\$previous"),
                )
            )
        val result = assertIs<AppResult.Success<CollaborationDeliveryReport>>(f.deliver()).value
        assertEquals(setOf(source.intent.id, sent.intent.id), result.confirmed)
        assertEquals(1, f.count("resume"))
        assertEquals(sent, f.records[sent.intent.id])
    }

    @Test
    fun drainedWithoutReceiptLeavesOriginalUnresolved() = runTest {
        val f = SpaceOriginalDeliveryFixture()
        f.onResume = {
            AppResult.Success(
                CollaborationQueueDelivery(
                    emptyList(),
                    emptySet(),
                    CollaborationQueueDeliveryReason.DRAINED,
                )
            )
        }
        val result = assertIs<AppResult.Success<CollaborationDeliveryReport>>(f.deliver()).value
        assertEquals(setOf(f.queue.record.intent.id), result.unresolved)
        assertEquals(CollaborationQueueDeliveryReason.UNRESOLVED, result.nativeReason)
        assertEquals(0, f.count("update"))
        assertTrue(f.journal.isEmpty())
    }

    @Test
    fun preparationClockLimitsDoNotRenewWithRotatedNativeCredentials() = runTest {
        for (variant in 0..2) {
            val f = SpaceOriginalDeliveryFixture()
            val session = f.native.session
            f.native.setup.session =
                session.copy(
                    binding =
                        session.binding.copy(
                            expiresAtEpochMillis =
                                (f.native.setup.now + 2.seconds).toEpochMilliseconds()
                        )
                )
            f.hooks["journal"] = {
                when (variant) {
                    0 -> {
                        f.native.setup.now += 3.seconds
                        f.native.setup.session = session
                    }
                    1 -> f.native.setup.now -= 1.seconds
                    else -> f.native.time += 2.seconds
                }
            }
            assertIs<AppResult.Failed>(f.deliver())
            assertEquals(0, f.count("resume"))
            assertEquals(1, f.count("close"))
        }
    }

    @Test
    fun nativeCredentialRejectionDoesNotRestrictOrganizationDirectory() = runTest {
        val f = SpaceOriginalDeliveryFixture()
        val failed = Failure(FailureKind.Unauthorized, "SDK credential rejected")
        f.failures["resume"] = failed
        assertEquals(failed, assertIs<AppResult.Failed>(f.deliver()).failure)
        assertEquals(0, f.directory.restrictions)
        assertEquals(0, f.count("closeSpace"))
        assertEquals(1, f.count("close"))
    }

    @Test
    fun hangingPostAckProjectionIsCancelledWithinTheOwningDeliveryDeadline() = runTest {
        val f = SpaceOriginalDeliveryFixture()
        f.hooks["update"] = { awaitCancellation() }
        val entered = CompletableDeferred<Unit>()
        f.hooks["resume"] = { entered.complete(Unit) }
        val task = async { f.deliver() }
        entered.await()
        advanceTimeBy(30_001)
        runCurrent()
        if (!task.isCompleted) {
            task.cancelAndJoin()
            fail("Delivery owner left post-ACK storage unbounded")
        }
        assertEquals(FailureKind.Timeout, assertIs<AppResult.Failed>(task.await()).failure.kind)
        assertEquals(listOf(f.receipt()), f.journal)
        assertEquals(f.queue.record, f.records[f.queue.record.intent.id])
        assertEquals(1, f.count("close"))
    }

    @Test
    fun exactOriginalIsResumedOnceAndJournaledInItsOriginalNamespace() = runTest {
        val f = SpaceOriginalDeliveryFixture()
        val result = assertIs<AppResult.Success<CollaborationDeliveryReport>>(f.deliver()).value
        assertEquals(setOf(f.queue.record.intent.id), result.confirmed)
        assertTrue(result.unresolved.isEmpty())
        assertEquals(listOf(f.receipt()), f.journal)
        assertEquals(f.queue.sent(), f.records[f.queue.record.intent.id])
        assertEquals(1, f.count("transport"))
        assertEquals(1, f.count("resume"))
        assertEquals(1, f.count("close"))
        advanceTimeBy(120_000)
        assertEquals(1, f.count("transport"))
    }

    @Test
    fun journalBeforeArchiveRepairsAndSettlesWithoutRecipientReadiness() = runTest {
        for (sent in listOf(false, true)) {
            val f = SpaceOriginalDeliveryFixture()
            f.observed = f.observed.copy(recipientReady = false)
            f.journal = listOf(f.receipt())
            if (sent) f.records[f.queue.record.intent.id] = f.queue.sent()
            assertIs<AppResult.Success<CollaborationDeliveryReport>>(f.deliver())
            assertEquals(1, f.count("settle"))
            assertEquals(0, f.count("resume"))
            assertEquals(1, f.count("close"))
            assertTrue(f.archived.isEmpty())
        }
    }

    @Test
    fun uncertainCommittedCasIsReadBackWithoutSecondWriteOrResume() = runTest {
        for (response in
            listOf(
                AppResult.Success(false),
                AppResult.Failed(Failure(FailureKind.Storage, "Lost response")),
            )) {
            val f = SpaceOriginalDeliveryFixture()
            f.journal = listOf(f.receipt())
            f.updateResponse = response
            assertIs<AppResult.Success<CollaborationDeliveryReport>>(f.deliver())
            assertEquals(1, f.count("update"))
            assertEquals(0, f.count("resume"))
            assertEquals(f.queue.sent(), f.records[f.queue.record.intent.id])
        }
    }

    @Test
    fun uncommittedCasLeavesJournalAndOriginalUnchanged() = runTest {
        val f = SpaceOriginalDeliveryFixture()
        f.journal = listOf(f.receipt())
        f.commitUpdate = false
        f.updateResponse = AppResult.Failed(f.directory.storageFailure)
        assertEquals(f.directory.storageFailure, assertIs<AppResult.Failed>(f.deliver()).failure)
        assertEquals(f.queue.record, f.records[f.queue.record.intent.id])
        assertEquals(0, f.count("settle"))
        assertEquals(0, f.count("resume"))
        assertEquals(listOf(f.receipt()), f.journal)
    }

    @Test
    fun archivedPrimaryAndLaterAliasAreKeptWithoutResend() = runTest {
        val f = SpaceOriginalDeliveryFixture()
        val sent = f.queue.sent()
        f.records[sent.intent.id] = sent
        f.archived[sent.intent.id] = CollaborationMessageCompletion(sent, listOf("\$accepted"))
        f.journal = listOf(f.receipt("\$later"))
        assertIs<AppResult.Success<CollaborationDeliveryReport>>(f.deliver())
        assertEquals(sent, f.records[sent.intent.id])
        assertEquals(0, f.count("update"))
        assertEquals(0, f.count("resume"))
    }

    @Test
    fun emptySdkDoesNotAcknowledgeMissingOwnedOriginalOrRetireWork() = runTest {
        val f = SpaceOriginalDeliveryFixture()
        f.snapshot = f.snapshot.copy(original = f.snapshot.original.copy(messages = emptyList()))
        val result = assertIs<AppResult.Success<CollaborationDeliveryReport>>(f.deliver()).value
        assertEquals(setOf(f.queue.record.intent.id), result.unresolved)
        assertTrue(result.confirmed.isEmpty())
        assertEquals(CollaborationQueueDeliveryReason.UNRESOLVED, result.nativeReason)
        assertEquals(0, f.count("resume"))
        assertEquals(f.queue.record, f.records[f.queue.record.intent.id])
    }

    @Test
    fun unreadyRecipientLeavesOriginalPausedWithNoPolling() = runTest {
        val f = SpaceOriginalDeliveryFixture()
        f.observed = f.observed.copy(recipientReady = false)
        val result = assertIs<AppResult.Success<CollaborationDeliveryReport>>(f.deliver()).value
        assertEquals(setOf(f.queue.record.intent.id), result.unresolved)
        assertEquals(0, f.count("resume"))
        assertEquals(0, f.count("update"))
        advanceTimeBy(120_000)
        assertEquals(1, f.count("transport"))
    }

    @Test
    fun foreignTransportOrBindingNeverAcquiresPhysicalWriter() = runTest {
        for (variant in 0..4) {
            val f = SpaceOriginalDeliveryFixture()
            when (variant) {
                0 -> f.retained = null
                1 ->
                    f.observed =
                        f.observed.copy(
                            conversation = f.model.conversation.copy(id = f.queue.id(299))
                        )
                2 ->
                    f.observed =
                        f.observed.copy(device = f.observed.device.copy(id = f.queue.id(299)))
                3 -> f.observed = f.observed.copy(matrixUserId = "@foreign:example.test")
                else -> f.open = false
            }
            assertIs<AppResult.Failed>(f.deliver())
            assertEquals(0, f.count("acquire"))
            assertEquals(0, f.count("update"))
        }
    }

    @Test
    fun malformedDuplicateOrForeignInventoryNeverProjectsEvenAnExistingJournal() = runTest {
        for (variant in 0..3) {
            val f = SpaceOriginalDeliveryFixture()
            f.journal = listOf(f.receipt())
            val original = f.queue.original
            val values =
                when (variant) {
                    0 -> listOf(original, original)
                    1 ->
                        listOf(
                            original.copy(
                                origin = original.origin.copy(contentHash = "b".repeat(64))
                            )
                        )
                    2 -> listOf(original.copy(logicalId = f.queue.id(999)))
                    else -> List(129) { original }
                }
            f.snapshot = f.snapshot.copy(original = f.snapshot.original.copy(messages = values))
            assertIs<AppResult.Failed>(f.deliver())
            assertEquals(0, f.count("update"))
            assertEquals(0, f.count("resume"))
            assertEquals(1, f.count("close"))
        }
    }

    @Test
    fun successfulSettlementMustMatchExactRemainingInventory() = runTest {
        for (settlement in CollaborationQueueSettlement.entries) {
            val f = SpaceOriginalDeliveryFixture()
            f.journal = listOf(f.receipt())
            f.settlement = settlement
            f.removeSettled = false
            assertIs<AppResult.Failed>(f.deliver())
            assertEquals(0, f.count("resume"))
            assertEquals(1, f.count("close"))
        }
    }

    @Test
    fun cancellationAtEveryPreparationSuspensionClosesAnyAcquiredWriter() = runTest {
        for (stage in
            listOf(
                "binding",
                "transport",
                "acquire",
                "snapshot",
                "observe",
                "journal",
                "update",
                "get",
                "completion",
                "settle",
            )) {
            val f = SpaceOriginalDeliveryFixture()
            f.journal = listOf(f.receipt())
            if (stage == "completion") f.records[f.queue.record.intent.id] = f.queue.sent()
            val entered = CompletableDeferred<Unit>()
            f.hooks[stage] = {
                entered.complete(Unit)
                awaitCancellation()
            }
            val task = async { f.deliver() }
            entered.await()
            task.cancelAndJoin()
            assertTrue(task.isCancelled)
            assertEquals(
                if (stage in listOf("binding", "transport", "acquire")) 0 else 1,
                f.count("close"),
                stage,
            )
            assertEquals(0, f.count("resume"), stage)
        }
    }

    @Test
    fun denialOrAccountReplacementAfterEveryPreparationReadStopsDelivery() = runTest {
        for (stage in
            listOf(
                "binding",
                "transport",
                "acquire",
                "snapshot",
                "observe",
                "journal",
                "completion",
                "settle",
            )) {
            for (denied in listOf(false, true)) {
                val f = SpaceOriginalDeliveryFixture()
                f.journal = listOf(f.receipt())
                f.records[f.queue.record.intent.id] = f.queue.sent()
                f.hooks[stage] = {
                    if (denied) f.directory.deny() else f.native.setup.session = null
                }
                val result = assertIs<AppResult.Failed>(f.deliver())
                if (denied) assertEquals(f.directory.denied, result.failure)
                assertEquals(0, f.count("resume"), stage)
            }
        }
    }

    @Test
    fun remoteDenialSurvivesFailedPersistenceCancellationAndCleanup() = runTest {
        val f = SpaceOriginalDeliveryFixture()
        f.failures["transport"] = f.directory.denied
        f.directory.afterRestrict = { AppResult.Failed(f.directory.storageFailure) }
        f.hooks["transport"] = { currentCoroutineContext().cancel() }
        f.failures["closeSpace"] = f.directory.storageFailure
        val task = async { f.deliver() }
        task.join()
        assertTrue(task.isCancelled)
        assertEquals(1, f.directory.restrictions)
        assertEquals(1, f.count("closeSpace"))
        assertEquals(0, f.count("acquire"))
    }

    @Test
    fun lateAckIsJournaledButNeverProjectedThroughCancelledInvocation() = runTest {
        val f = SpaceOriginalDeliveryFixture()
        val entered = CompletableDeferred<Unit>()
        f.onResume = { observer ->
            try {
                entered.complete(Unit)
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    assertIs<AppResult.Success<Unit>>(observer(f.delivery()))
                }
            }
        }
        val task = async { f.deliver() }
        entered.await()
        task.cancelAndJoin()
        assertEquals(listOf(f.receipt()), f.journal)
        assertEquals(f.queue.record, f.records[f.queue.record.intent.id])
        assertEquals(0, f.count("update"))
        assertEquals(1, f.count("close"))
    }

    @Test
    fun deliveryWindowCanOutlivePreparationBudgetWithoutStartingAnotherTransportRead() = runTest {
        val f = SpaceOriginalDeliveryFixture()
        f.onResume = { observer ->
            delay(20_000)
            f.native.time += 20.seconds
            f.native.setup.now += 20.seconds
            val delivered = f.delivery()
            assertIs<AppResult.Success<Unit>>(observer(delivered))
            AppResult.Success(delivered)
        }
        assertIs<AppResult.Success<CollaborationDeliveryReport>>(f.deliver())
        assertEquals(1, f.count("transport"))
        assertEquals(1, f.count("resume"))
    }

    @Test
    fun duplicateInvocationIsRejectedPromptlyAndWriterIsReleasedAfterCancellation() = runTest {
        val f = SpaceOriginalDeliveryFixture()
        val entered = CompletableDeferred<Unit>()
        f.hooks["resume"] = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val task = async { f.deliver() }
        entered.await()
        assertEquals(
            "client.collaboration_original_busy",
            assertIs<AppResult.Failed>(f.deliver()).failure.code,
        )
        assertEquals(1, f.count("transport"))
        task.cancelAndJoin()
        f.hooks.clear()
        assertIs<AppResult.Success<CollaborationDeliveryReport>>(f.deliver())
        assertEquals(2, f.count("close"))
    }

    @Test
    fun callbackPersistenceFailureLeavesOriginalUnacknowledgedAndCleanupFailureIsExplicit() =
        runTest {
            val f = SpaceOriginalDeliveryFixture()
            f.failures["append"] = f.directory.storageFailure
            assertEquals(
                f.directory.storageFailure,
                assertIs<AppResult.Failed>(f.deliver()).failure,
            )
            assertEquals(f.queue.record, f.records[f.queue.record.intent.id])
            assertEquals(1, f.count("close"))
            val g = SpaceOriginalDeliveryFixture()
            g.failures["close"] = g.directory.storageFailure
            assertEquals(
                g.directory.storageFailure,
                assertIs<AppResult.Failed>(g.deliver()).failure,
            )
            assertEquals(listOf(g.receipt()), g.journal)
        }
}
