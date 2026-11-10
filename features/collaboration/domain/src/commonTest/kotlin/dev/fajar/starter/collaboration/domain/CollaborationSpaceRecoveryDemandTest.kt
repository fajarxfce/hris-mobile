@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.ReadCollaborationSpaceRecoveryDemand
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class CollaborationSpaceRecoveryDemandTest {
    @Test
    fun emptySdkAndEmptyLedgersAreIdleWithoutTransportOrMutation() = runTest {
        val f = Fixture()
        assertFalse(f.demand())
        assertEquals(2, f.base.count("inspect"))
        f.noDelivery()
    }

    @Test
    fun sdkOriginalStillRequiresSettlementEvenWhenAppLedgersAreEmpty() = runTest {
        val f = Fixture()
        f.base.snapshot = f.base.queue.snapshot
        assertTrue(f.demand())
        assertEquals(0, f.base.count("observe"))
        f.noDelivery()
    }

    @Test
    fun pendingCommandRequiresRecoveryDespiteAnEmptySdk() = runTest {
        for (queued in listOf(false, true)) {
            val f = Fixture()
            val state = if (queued) CollaborationMessageState.Queued else f.base.queue.record.state
            f.base.records[f.base.queue.record.intent.id] = f.base.queue.record.copy(state = state)
            assertTrue(f.demand())
            f.noDelivery()
        }
    }

    @Test
    fun ackBeforeSentProjectionRemainsWorkAndDoesNotWriteThroughTheRead() = runTest {
        val f = Fixture()
        f.base.records[f.base.queue.record.intent.id] = f.base.queue.record
        f.base.journal = listOf(f.base.receipt())
        assertTrue(f.demand())
        assertEquals(f.base.queue.record, f.base.records.values.single())
        f.noDelivery()
    }

    @Test
    fun exactSentAndJournalAreIdleWithoutHistoricalRetirement() = runTest {
        val f = Fixture()
        val sent = f.base.queue.sent()
        f.base.records[sent.intent.id] = sent
        f.base.journal = listOf(f.base.receipt())
        assertFalse(f.demand())
        assertEquals(sent, f.base.records.values.single())
        assertTrue(f.base.archived.isEmpty())
        f.noDelivery()
    }

    @Test
    fun archivedSentAndLaterReceiptAliasAreIdleWithOriginalCompletion() = runTest {
        val f = Fixture()
        val sent = f.base.queue.sent()
        f.base.records[sent.intent.id] = sent
        f.base.archived[sent.intent.id] =
            CollaborationMessageCompletion(sent, listOf("$" + "accepted"))
        f.base.journal = listOf(f.base.receipt("$" + "later"))
        assertFalse(f.demand())
        f.noDelivery()
    }

    @Test
    fun sentWithoutAcknowledgmentOrReceiptWithoutProjectionRemainsWork() = runTest {
        for (missingRecord in listOf(false, true)) {
            val f = Fixture()
            if (missingRecord) f.base.journal = listOf(f.base.receipt())
            else f.base.records[f.base.queue.record.intent.id] = f.base.queue.sent()
            assertTrue(f.demand())
            f.noDelivery()
        }
    }

    @Test
    fun completeAppLedgerDoesNotHideAnUnsettledSdkOriginal() = runTest {
        val f = Fixture()
        f.base.records[f.base.queue.record.intent.id] = f.base.queue.sent()
        f.base.journal = listOf(f.base.receipt())
        f.base.snapshot = f.base.queue.snapshot
        assertTrue(f.demand())
        f.noDelivery()
    }

    @Test
    fun sdkAppendDuringStorageReadRemainsWorkEvenForMutableRepositoryLists() = runTest {
        val f = Fixture()
        val raw = mutableListOf<CollaborationQueuedOriginal>()
        f.base.snapshot =
            f.base.snapshot.copy(original = f.base.snapshot.original.copy(messages = raw))
        f.base.hooks["journal"] = { if (raw.isEmpty()) raw += f.base.queue.original }
        assertTrue(f.demand())
        f.noDelivery()
    }

    @Test
    fun commandOrReceiptAppearingDuringTheReadRequiresAnotherPass() = runTest {
        for (stage in listOf("observe", "journal")) {
            val f = Fixture()
            f.base.hooks[stage] = {
                if (f.base.count(stage) == 2) {
                    if (stage == "observe")
                        f.base.records[f.base.queue.record.intent.id] = f.base.queue.record
                    else f.base.journal = listOf(f.base.receipt())
                }
            }
            assertTrue(f.demand())
            f.noDelivery()
        }
    }

    @Test
    fun malformedOrForeignSdkAndLedgerNeverBecomeIdle() = runTest {
        for (variant in 0..4) {
            val f = Fixture()
            when (variant) {
                0 ->
                    f.base.snapshot =
                        f.base.snapshot.copy(
                            runtime = f.base.queue.runtime.copy(deviceId = f.base.queue.id(999))
                        )
                1 ->
                    f.base.snapshot =
                        f.base.snapshot.copy(
                            original =
                                f.base.snapshot.original.copy(
                                    messages = List(129) { f.base.queue.original }
                                )
                        )
                2 -> f.base.journal = listOf(f.base.receipt(), f.base.receipt())
                3 ->
                    f.base.records[f.base.queue.record.intent.id] =
                        f.base.queue.record.copy(
                            intent =
                                f.base.queue.record.intent.copy(
                                    conversationId = f.base.queue.id(999)
                                )
                        )
                else -> f.base.retained = null
            }
            assertIs<AppResult.Failed>(f.read())
            f.noDelivery()
        }
    }

    @Test
    fun changedCompletionCannotDischargeAnArchivedReceipt() = runTest {
        val f = Fixture()
        val sent = f.base.queue.sent()
        f.base.journal = listOf(f.base.receipt())
        f.base.archived[sent.intent.id] =
            CollaborationMessageCompletion(
                sent.copy(intent = sent.intent.copy(id = f.base.queue.id(999))),
                listOf("$" + "accepted"),
            )
        assertIs<AppResult.Failed>(f.read())
        f.noDelivery()
    }

    @Test
    fun ownerReplacementOrConfirmedDenialDuringLocalReadsPreventsIdleResult() = runTest {
        for (stage in listOf("binding", "inspect", "observe", "journal", "completion", "open")) {
            for (denied in listOf(false, true)) {
                val f = Fixture()
                if (stage == "completion") {
                    f.base.records[f.base.queue.record.intent.id] = f.base.queue.sent()
                    f.base.journal = listOf(f.base.receipt())
                }
                f.base.hooks[stage] = {
                    if (denied) f.base.directory.deny() else f.base.native.setup.session = null
                }
                val result = assertIs<AppResult.Failed>(f.read())
                if (denied) assertEquals(f.base.directory.denied, result.failure)
                f.noDelivery()
            }
        }
    }

    @Test
    fun localStorageFailureDoesNotFallBackToTransport() = runTest {
        val f = Fixture()
        f.base.failures["journal"] = f.base.directory.storageFailure
        assertEquals(f.base.directory.storageFailure, assertIs<AppResult.Failed>(f.read()).failure)
        f.noDelivery()
    }

    @Test
    fun cancellationAndDeadlineTerminateTheOwningRead() = runTest {
        val f = Fixture()
        val entered = CompletableDeferred<Unit>()
        f.base.hooks["inspect"] = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val task = async { f.read() }
        entered.await()
        task.cancelAndJoin()
        assertTrue(task.isCancelled)
        f.noDelivery()
        val g = Fixture()
        g.base.hooks["journal"] = { awaitCancellation() }
        assertEquals(FailureKind.Timeout, assertIs<AppResult.Failed>(g.read()).failure.kind)
        g.noDelivery()
    }

    @Test
    fun closedRuntimeCannotCompleteIdleProgress() = runTest {
        val f = Fixture()
        f.base.open = false
        assertIs<AppResult.Failed>(f.read())
        f.noDelivery()
    }

    private class Fixture {
        val base =
            SpaceOriginalDeliveryFixture().apply {
                records.clear()
                snapshot = snapshot.copy(original = snapshot.original.copy(messages = emptyList()))
            }
        private val runtimes =
            object : CollaborationSpaceRuntimeRepository by base.runtimes {
                override suspend fun inspectOriginalQueue(
                    runtime: CollaborationSpaceRuntimeReady,
                    matrixRoomId: String,
                ): AppResult<CollaborationSpaceOriginalQueueSnapshot> {
                    assertEquals(base.queue.runtime, runtime)
                    assertEquals(base.queue.device.roomId, matrixRoomId)
                    return base.read("inspect") { base.snapshot }
                }
            }
        private val useCase =
            ReadCollaborationSpaceRecoveryDemand(
                base.bindings,
                runtimes,
                base.messages,
                base.receipts,
                base.completions,
                base.space.messages,
                base.space.receipts,
                base.space.completions,
                base.directory.directory,
                base.native.setup.sessions,
                base.native.server,
            )

        suspend fun read() = useCase(base.queue.runtime, base.model.conversation)

        suspend fun demand() = assertIs<AppResult.Success<Boolean>>(read()).value

        fun noDelivery() {
            for (name in
                listOf(
                    "transport",
                    "acquire",
                    "resume",
                    "settle",
                    "append",
                    "update",
                )) assertEquals(0, base.count(name), name)
        }
    }
}
