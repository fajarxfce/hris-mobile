package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.ReconcileCollaborationMessages
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class ReconcileCollaborationMessagesTest {
    @Test
    fun interruptedProjectionReconcilesOfflineAndKeepsOriginalPrimaryEvent() = runTest {
        val f = ReceiptReconciliationFixture()
        f.prepare()
        f.base.setup.now += 365.days
        assertEquals(AppResult.Success(1), f.reconcile())
        assertEquals("\$first", assertIs<CollaborationMessageState.Sent>(f.record.state).eventId)
        f.journal = f.journal.map { it.copy(eventIds = it.eventIds.reversed()) }
        assertEquals(AppResult.Success(0), f.reconcile())
        assertEquals("\$first", assertIs<CollaborationMessageState.Sent>(f.record.state).eventId)
        assertEquals(1, f.writes)
        assertEquals(0, f.base.setup.calls)
    }

    @Test
    fun absentReceiptDoesNotInferAcknowledgementFromMissingNativeWork() = runTest {
        val f = ReceiptReconciliationFixture()
        f.prepare()
        f.journal = emptyList()
        assertEquals(AppResult.Success(0), f.reconcile())
        assertIs<CollaborationMessageState.Staged>(f.record.state)
        assertEquals(0, f.writes)
    }

    @Test
    fun receiptCannotRetargetContentTransactionOrOriginalDeviceFamily() = runTest {
        for (kind in 0..5) {
            val f = ReceiptReconciliationFixture()
            f.prepare()
            val receipt = f.journal.single()
            f.journal =
                listOf(
                    when (kind) {
                        0 -> receipt.copy(intent = receipt.intent.copy(text = "Changed"))
                        1 -> receipt.copy(transactionId = "replacement")
                        2 ->
                            receipt.copy(
                                device = receipt.device.copy(nativeSessionId = reconciliationId(9))
                            )
                        3 -> receipt.copy(eventIds = emptyList())
                        4 -> receipt.copy(eventIds = listOf("not-an-event"))
                        else -> receipt.copy(intent = receipt.intent.copy(id = reconciliationId(9)))
                    }
                )
            assertIs<AppResult.Failed>(f.reconcile())
            assertEquals(0, f.writes)
        }
    }

    @Test
    fun unmappedIntentAndConflictingWholeJournalCannotPartiallyProject() = runTest {
        val f = ReceiptReconciliationFixture()
        f.prepare()
        val original = f.record
        for (state in
            listOf(
                CollaborationMessageState.Queued,
                CollaborationMessageState.Staging(f.device, emptySet()),
            )) {
            f.base.records.value = listOf(original.copy(state = state))
            assertIs<AppResult.Failed>(f.reconcile())
        }
        val second = original.copy(intent = original.intent.copy(id = reconciliationId(8)))
        f.base.records.value = listOf(original, second)
        f.journal += f.journal.single().copy(intent = second.intent, transactionId = "second")
        assertIs<AppResult.Failed>(f.reconcile())
        assertEquals(0, f.writes)
    }

    @Test
    fun deniedOrFailedAdmissionCannotExposeOrAdvanceJournal() = runTest {
        val f = ReceiptReconciliationFixture()
        f.prepare()
        f.base.gate.value = CollaborationListAdmission("denied", f.base.denied)
        assertEquals(f.base.denied, assertIs<AppResult.Failed>(f.reconcile()).failure)
        assertEquals(0, f.reads)
        f.base.gate.value = CollaborationListAdmission("admitted", null)
        f.base.gateFailure = f.base.storage
        assertEquals(f.base.storage, assertIs<AppResult.Failed>(f.reconcile()).failure)
        assertEquals(0, f.reads)
    }

    @Test
    fun lateAccountChangeDuringGateReadPreventsProjectionAndPublication() = runTest {
        val f = ReceiptReconciliationFixture()
        f.prepare()
        f.base.beforeAdmission = { if (f.reads > 0) f.base.setup.session = null }
        assertIs<AppResult.Failed>(f.reconcile())
        assertEquals(0, f.writes)
    }

    @Test
    fun receivedCompanyDenialWinsOverStorageFailureWithoutDroppingFacts() = runTest {
        val f = ReceiptReconciliationFixture()
        f.prepare()
        f.readFailure = f.base.storage
        f.afterRead = {
            f.base.gate.value = CollaborationListAdmission("denied", f.base.denied)
            f.base.setup.sessionFailure = f.base.storage
        }
        assertEquals(f.base.denied, assertIs<AppResult.Failed>(f.reconcile()).failure)
        assertEquals(1, f.journal.size)
        assertEquals(0, f.writes)
    }

    @Test
    fun rejectedCasAndLostResponseRecoverWithoutAnotherNativeSend() = runTest {
        val f = ReceiptReconciliationFixture()
        f.prepare()
        f.rejectWrite = true
        assertIs<AppResult.Failed>(f.reconcile())
        assertIs<CollaborationMessageState.Staged>(f.record.state)
        f.rejectWrite = false
        f.writeFailure = f.base.storage
        assertIs<AppResult.Failed>(f.reconcile())
        assertIs<CollaborationMessageState.Sent>(f.record.state)
        assertEquals(AppResult.Success(0), f.reconcile())
        assertEquals(2, f.writes)
        assertEquals(0, f.base.setup.calls)
    }

    @Test
    fun cancellationAfterCommitKeepsReceiptAndReleasesReconciliationOwner() = runTest {
        val f = ReceiptReconciliationFixture()
        f.prepare()
        val committed = CompletableDeferred<Unit>()
        f.afterWrite = {
            committed.complete(Unit)
            awaitCancellation()
        }
        val pending = launch { f.reconcile() }
        committed.await()
        pending.cancelAndJoin()
        f.afterWrite = {}
        assertEquals(AppResult.Success(0), f.reconcile())
        assertEquals(1, f.writes)
        assertEquals(1, f.journal.size)
    }

    @Test
    fun denialAfterCommittedProjectionPreventsSuccessButRetainsAcknowledgement() = runTest {
        val f = ReceiptReconciliationFixture()
        f.prepare()
        f.afterWrite = { f.base.gate.value = CollaborationListAdmission("denied", f.base.denied) }
        assertEquals(f.base.denied, assertIs<AppResult.Failed>(f.reconcile()).failure)
        assertIs<CollaborationMessageState.Sent>(f.record.state)
        assertEquals(f.base.denied, assertIs<AppResult.Failed>(f.reconcile()).failure)
        assertEquals(1, f.writes)
    }
}

private class ReceiptReconciliationFixture {
    val base = MessageIntentFixture()
    val device =
        CollaborationMessageDevice(
            reconciliationId(1),
            reconciliationId(2),
            reconciliationId(3),
            "!room:example.test",
        )
    var journal = emptyList<CollaborationMessageReceipt>()
    var reads = 0
    var writes = 0
    var rejectWrite = false
    var readFailure: Failure? = null
    var writeFailure: Failure? = null
    var afterRead: suspend () -> Unit = {}
    var afterWrite: suspend () -> Unit = {}
    val record
        get() = base.records.value.single()

    private val receipts =
        object : CollaborationReceiptRepository {
            override suspend fun read(
                access: CollaborationLocalAccess,
                conversationId: String,
            ): AppResult<List<CollaborationMessageReceipt>> {
                reads++
                afterRead()
                return readFailure?.let { AppResult.Failed(it) } ?: AppResult.Success(journal)
            }

            override suspend fun append(
                access: CollaborationLocalAccess,
                conversationId: String,
                receipts: List<CollaborationMessageReceipt>,
            ): AppResult<Boolean> = error("Reconciliation never invents receipts")
        }
    private val messages =
        object : CollaborationMessageRepository by base.messages {
            override suspend fun update(
                access: CollaborationLocalAccess,
                expected: CollaborationMessageRecord,
                state: CollaborationMessageState,
            ): AppResult<Boolean> {
                writes++
                if (rejectWrite || expected !in base.records.value) return AppResult.Success(false)
                base.records.value =
                    base.records.value.map {
                        if (it == expected) it.copy(revision = it.revision + 1, state = state)
                        else it
                    }
                afterWrite()
                return writeFailure?.let { AppResult.Failed(it) } ?: AppResult.Success(true)
            }
        }
    private val useCase =
        ReconcileCollaborationMessages(
            messages,
            receipts,
            base.lists,
            base.setup.sessions,
            base.setup.workspaces,
            base.server,
            base.build,
            base.clock,
        )

    suspend fun prepare() {
        val queued = assertIs<AppResult.Success<CollaborationMessageRecord>>(base.queue()).value
        base.records.value =
            listOf(
                queued.copy(
                    revision = 2,
                    state = CollaborationMessageState.Staged(device, "original"),
                )
            )
        journal =
            listOf(
                CollaborationMessageReceipt(
                    queued.intent,
                    device,
                    "original",
                    listOf("\$first", "\$retry-alias"),
                )
            )
    }

    suspend fun reconcile() = useCase("selection", base.records.value.first().intent.conversationId)
}

private fun reconciliationId(value: Int) =
    "c2000000-0000-4000-8000-${value.toString().padStart(12, '0')}"
