@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.ReadCollaborationSpaceRecoveryDemand
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class CollaborationMixedRecoveryDemandTest {
    @Test
    fun emptyMixedLedgersAreIdleAfterBothWereRechecked() = runTest {
        val f = Fixture()
        assertFalse(f.demand())
        for (stage in
            listOf(
                "observe",
                "journal",
                "space-observe",
                "space-journal",
                "inspect",
                "binding",
            )) assertEquals(2, f.base.count(stage), stage)
    }

    @Test
    fun newRoomReadsOnlySpaceFactsAndNeverNeedsCompanyCommands() = runTest {
        val f = Fixture(newRoom = true)
        assertFalse(f.demand())
        f.noCompanyReads()
        assertEquals(2, f.base.count("space-observe"))
    }

    @Test
    fun queuedStagingAndStagedSpaceCommandsRemainWorkWithEmptySdk() = runTest {
        for (newRoom in listOf(false, true)) {
            for (kind in 0..2) {
                val f = Fixture(newRoom)
                val staged = f.record.state as CollaborationMessageState.Staged
                val state =
                    when (kind) {
                        0 -> CollaborationMessageState.Queued
                        1 ->
                            CollaborationMessageState.Staging(
                                staged.device,
                                setOf("before"),
                                staged.origin,
                            )
                        else -> staged
                    }
                f.space.records[f.record.intent.id] =
                    f.record.copy(revision = if (kind == 0) 0 else 2, state = state)
                assertTrue(f.demand())
                if (newRoom) f.noCompanyReads()
                assertEquals(0, f.base.count("space-completion"))
            }
        }
    }

    @Test
    fun completeCompanyLedgerCannotHidePendingSpaceCommand() = runTest {
        val f = Fixture()
        f.confirmCompany()
        f.space.records[f.record.intent.id] = f.record
        assertTrue(f.demand())
        assertEquals(1, f.base.count("space-observe"))
    }

    @Test
    fun nonemptyOriginalSdkRequestsRecoveryWithoutReadingEitherLedger() = runTest {
        for (newRoom in listOf(false, true)) {
            val f = Fixture(newRoom)
            f.base.snapshot =
                f.base.snapshot.copy(
                    original = f.base.snapshot.original.copy(messages = listOf(f.mixed.original))
                )
            assertTrue(f.demand())
            for (stage in
                listOf("observe", "journal", "space-observe", "space-journal")) assertEquals(
                0,
                f.base.count(stage),
            )
        }
    }

    @Test
    fun exactSpaceSentAndJournalAreIdleInBothRoomOrigins() = runTest {
        for (newRoom in listOf(false, true)) {
            val f = Fixture(newRoom)
            val sent = f.confirmSpace()
            assertFalse(f.demand())
            assertEquals(sent, f.space.records[sent.intent.id])
            assertTrue(f.space.archived.isEmpty())
            if (newRoom) f.noCompanyReads()
        }
    }

    @Test
    fun archivedSpaceOriginalAcceptsLaterAliasWithoutLosingOriginalProof() = runTest {
        val f = Fixture()
        val sent = f.confirmSpace()
        f.space.archived[sent.intent.id] =
            CollaborationSpaceMessageCompletion(sent, listOf("\$space-accepted"))
        f.space.journal = listOf(f.space.journal.single().copy(eventIds = listOf("\$later")))
        assertFalse(f.demand())
        assertEquals(1, f.base.count("space-completion"))
    }

    @Test
    fun spaceSentWithoutAnyAcknowledgementRemainsWork() = runTest {
        val f = Fixture()
        f.confirmSpace()
        f.space.journal = emptyList()
        assertTrue(f.demand())
    }

    @Test
    fun spaceReceiptWithoutRecordOrArchiveRemainsWork() = runTest {
        val f = Fixture()
        f.confirmSpace()
        f.space.records.clear()
        assertTrue(f.demand())
    }

    @Test
    fun cancellationRequiresNoReceiptButCannotAbsorbOne() = runTest {
        for (withReceipt in listOf(false, true)) {
            val f = Fixture()
            f.confirmSpace()
            f.space.records[f.record.intent.id] =
                f.record.copy(revision = 0, state = CollaborationMessageState.Cancelled)
            if (!withReceipt) f.space.journal = emptyList()
            assertEquals(withReceipt, f.demand())
        }
    }

    @Test
    fun bothNamespacesCannotClaimOneLogicalMessageId() = runTest {
        val f = Fixture()
        f.confirmCompany()
        val sent = f.confirmSpace()
        val intent = sent.intent.copy(id = f.base.queue.record.intent.id)
        f.space.records.clear()
        f.space.records[intent.id] = sent.copy(intent = intent)
        f.space.journal = listOf(f.space.journal.single().copy(intent = intent))
        assertIs<AppResult.Failed>(f.read())
    }

    @Test
    fun physicalTransactionCannotBeSharedAcrossNamespacesOrBootstrapAliases() = runTest {
        for (alias in listOf(false, true)) {
            val f = Fixture()
            f.confirmCompany()
            val sent = f.confirmSpace()
            val state = sent.state as CollaborationMessageState.Sent
            val device =
                if (alias) state.device.copy(bootstrapOperationId = f.base.queue.id(901))
                else state.device
            f.space.records[sent.intent.id] =
                sent.copy(
                    state =
                        state.copy(
                            device = device,
                            transactionId = f.base.queue.original.transactionId,
                        )
                )
            f.space.journal =
                listOf(
                    f.space.journal
                        .single()
                        .copy(device = device, transactionId = f.base.queue.original.transactionId)
                )
            assertIs<AppResult.Failed>(f.read())
        }
    }

    @Test
    fun originalArchiveAliasCannotBeHiddenByALaterSpaceJournalAlias() = runTest {
        val f = Fixture()
        f.confirmCompany()
        val sent = f.confirmSpace()
        f.space.archived[sent.intent.id] =
            CollaborationSpaceMessageCompletion(sent, listOf("\$space-accepted", "\$accepted"))
        f.space.journal = listOf(f.space.journal.single().copy(eventIds = listOf("\$later")))
        assertIs<AppResult.Failed>(f.read())
    }

    @Test
    fun cancelledCrossNamespaceIdentityCollisionDoesNotDischargeWork() = runTest {
        val f = Fixture()
        val id = f.base.queue.record.intent.id
        f.base.records[id] =
            f.base.queue.record.copy(revision = 0, state = CollaborationMessageState.Cancelled)
        f.space.records[id] =
            f.record.copy(
                intent = f.record.intent.copy(id = id),
                revision = 0,
                state = CollaborationMessageState.Cancelled,
            )
        assertIs<AppResult.Failed>(f.read())
    }

    @Test
    fun malformedTerminalSpaceRecordsCannotDisappearBehindIdleFiltering() = runTest {
        for (kind in 0..3) {
            val f = Fixture()
            val sent = f.confirmSpace()
            f.space.records[sent.intent.id] =
                when (kind) {
                    0 -> sent.copy(revision = -1)
                    1 -> sent.copy(intent = sent.intent.copy(text = ""))
                    2 ->
                        sent.copy(
                            intent =
                                sent.intent.copy(
                                    owner = sent.intent.owner.copy(spaceId = f.base.queue.id(902))
                                )
                        )
                    else -> sent.copy(state = CollaborationMessageState.Cancelled)
                }
            assertIs<AppResult.Failed>(f.read())
        }
    }

    @Test
    fun malformedCompanyTerminalRecordsCannotDischargeSpaceWork() = runTest {
        for (kind in 0..2) {
            val f = Fixture()
            val sent = f.base.queue.sent()
            f.base.records[sent.intent.id] =
                when (kind) {
                    0 -> sent.copy(revision = -1)
                    1 -> sent.copy(intent = sent.intent.copy(text = ""))
                    else -> sent.copy(state = CollaborationMessageState.Cancelled)
                }
            assertIs<AppResult.Failed>(f.read())
        }
    }

    @Test
    fun duplicateOrOverflowSpaceInventoryFailsBeforeIdleDecision() = runTest {
        for (size in listOf(2, 129)) {
            val f = Fixture()
            f.rows = { flowOf(AppResult.Success(List(size) { f.record })) }
            assertIs<AppResult.Failed>(f.read())
        }
    }

    @Test
    fun foreignSpaceJournalCannotRequestAnArchiveInAnotherRoom() = runTest {
        val f = Fixture()
        f.confirmSpace()
        val receipt = f.space.journal.single()
        f.space.journal =
            listOf(
                receipt.copy(intent = receipt.intent.copy(conversationId = f.base.queue.id(903)))
            )
        assertIs<AppResult.Failed>(f.read())
        assertEquals(0, f.base.count("space-completion"))
    }

    @Test
    fun changedCompletionCannotConfirmTheRequestedOriginal() = runTest {
        for (kind in 0..2) {
            val f = Fixture()
            val sent = f.confirmSpace()
            val changed =
                when (kind) {
                    0 -> sent.copy(intent = sent.intent.copy(id = f.base.queue.id(904)))
                    1 -> sent.copy(intent = sent.intent.copy(conversationId = f.base.queue.id(905)))
                    else ->
                        sent.copy(
                            intent =
                                sent.intent.copy(
                                    owner = sent.intent.owner.copy(accountId = f.base.queue.id(906))
                                )
                        )
                }
            f.space.records.clear()
            f.space.archived[sent.intent.id] =
                CollaborationSpaceMessageCompletion(changed, listOf("\$space-accepted"))
            assertIs<AppResult.Failed>(f.read())
        }
    }

    @Test
    fun companyAppendWhileReadingSpaceRequiresAnotherPass() = runTest {
        for (stage in listOf("space-observe", "space-journal", "space-completion")) {
            val f = Fixture()
            if (stage == "space-completion") f.confirmSpace()
            f.base.hooks[stage] = {
                f.base.records[f.base.queue.record.intent.id] = f.base.queue.record
            }
            assertTrue(f.demand())
        }
    }

    @Test
    fun spaceAppendAfterInitialReadRequiresAnotherPass() = runTest {
        val f = Fixture()
        f.base.hooks["space-observe"] = {
            if (f.base.count("space-observe") == 2) f.space.records[f.record.intent.id] = f.record
        }
        assertTrue(f.demand())
    }

    @Test
    fun finalBindingReplacementNeverCompletesAnOldRoom() = runTest {
        val f = Fixture()
        f.base.hooks["binding"] = { if (f.base.count("binding") == 2) f.base.retained = null }
        assertIs<AppResult.Failed>(f.read())
    }

    @Test
    fun mutableJournalAliasesAreDetachedBeforePostReadAdmissionSuspends() = runTest {
        val f = Fixture()
        f.confirmCompany()
        f.confirmSpace()
        val aliases = mutableListOf("\$space-accepted")
        f.space.journal = listOf(f.space.journal.single().copy(eventIds = aliases))
        f.base.directory.beforeAdmission = {
            if (f.base.count("space-journal") == 1 && aliases.size == 1) aliases += "\$accepted"
        }
        // A later change is work, not a cross-ledger collision in the previously captured facts.
        assertTrue(f.demand())
    }

    @Test
    fun archivedAliasesAreCapturedBeforeAnyLaterAdmissionRead() = runTest {
        val f = Fixture()
        f.confirmCompany()
        val sent = f.confirmSpace()
        val aliases = mutableListOf("\$space-accepted")
        f.space.archived[sent.intent.id] = CollaborationSpaceMessageCompletion(sent, aliases)
        f.base.directory.beforeAdmission = {
            if (f.base.count("space-completion") == 1 && aliases.size == 1) aliases += "\$accepted"
        }
        assertFalse(f.demand())
        assertEquals(2, aliases.size)
    }

    @Test
    fun replacedOwnerOrConfirmedDenialAtEverySpaceReadPreventsPublication() = runTest {
        for (stage in listOf("space-observe", "space-journal", "space-completion")) {
            for (denied in listOf(false, true)) {
                val f = Fixture()
                f.confirmSpace()
                f.base.hooks[stage] = {
                    if (denied) f.base.directory.deny() else f.base.native.setup.session = null
                }
                val failure = assertIs<AppResult.Failed>(f.read()).failure
                if (denied) assertEquals(f.base.directory.denied, failure)
            }
        }
    }

    @Test
    fun spaceStorageFailureNeverAcquiresTransportOrWrites() = runTest {
        for (stage in listOf("space-observe", "space-journal", "space-completion")) {
            val f = Fixture()
            f.confirmSpace()
            f.base.failures[stage] = f.base.directory.storageFailure
            assertEquals(
                f.base.directory.storageFailure,
                assertIs<AppResult.Failed>(f.read()).failure,
            )
        }
    }

    @Test
    fun emptyCompletedSpaceObservationIsControlledFailure() = runTest {
        val f = Fixture()
        f.rows = { emptyFlow() }
        assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(f.read()).failure.kind)
    }

    @Test
    fun cancelledOrStalledSpaceObservationReleasesItsCollector() = runTest {
        for (cancelled in listOf(false, true)) {
            val f = Fixture()
            val entered = CompletableDeferred<Unit>()
            var stopped = false
            f.rows = {
                flow {
                    try {
                        entered.complete(Unit)
                        awaitCancellation()
                    } finally {
                        stopped = true
                    }
                }
            }
            if (cancelled) {
                val task = async { f.read() }
                entered.await()
                task.cancelAndJoin()
                assertTrue(task.isCancelled)
            } else
                assertEquals(FailureKind.Timeout, assertIs<AppResult.Failed>(f.read()).failure.kind)
            assertTrue(stopped)
        }
    }

    @Test
    fun newRoomStillRequiresCurrentRuntimeAndOwnedBinding() = runTest {
        for (kind in 0..1) {
            val f = Fixture(newRoom = true)
            if (kind == 0) f.base.open = false
            else
                f.base.snapshot =
                    f.base.snapshot.copy(
                        runtime = f.base.queue.runtime.copy(deviceId = f.base.queue.id(907))
                    )
            assertIs<AppResult.Failed>(f.read())
            f.noCompanyReads()
        }
    }

    @Test
    fun expiredCredentialsDoNotHideIdleLocalFacts() = runTest {
        val f = Fixture(newRoom = true)
        val session = requireNotNull(f.base.native.setup.session)
        f.base.native.setup.session =
            session.copy(
                tokens = session.tokens.copy(expiresAtEpochMillis = 0),
                binding = session.binding.copy(expiresAtEpochMillis = 0),
            )
        assertFalse(f.demand())
        f.noCompanyReads()
    }

    private class Fixture(newRoom: Boolean = false) {
        val mixed = MixedSpaceDeliveryFixture()
        val base = mixed.base
        val space = mixed.space
        val record: CollaborationSpaceMessageRecord
        var rows: (() -> Flow<AppResult<List<CollaborationSpaceMessageRecord>>>)? = null

        init {
            if (newRoom) mixed.newRoom()
            record = space.records.getValue(mixed.record.intent.id)
            base.records.clear()
            space.records.clear()
            base.snapshot =
                base.snapshot.copy(original = base.snapshot.original.copy(messages = emptyList()))
        }

        private val runtimes =
            object : CollaborationSpaceRuntimeRepository by base.runtimes {
                override suspend fun inspectOriginalQueue(
                    runtime: CollaborationSpaceRuntimeReady,
                    matrixRoomId: String,
                ): AppResult<CollaborationSpaceOriginalQueueSnapshot> {
                    assertEquals(base.queue.runtime, runtime)
                    assertEquals(base.snapshot.original.device.roomId, matrixRoomId)
                    return base.read("inspect") { base.snapshot }
                }
            }
        private val messages =
            object : CollaborationSpaceMessageRepository by space.messages {
                override fun observe(
                    access: CollaborationSpaceLocalAccess,
                    conversationId: String?,
                ) = rows?.invoke() ?: space.messages.observe(access, conversationId)
            }
        private val useCase =
            ReadCollaborationSpaceRecoveryDemand(
                base.bindings,
                runtimes,
                base.messages,
                base.receipts,
                base.completions,
                messages,
                space.receipts,
                space.completions,
                base.directory.directory,
                base.native.setup.sessions,
                base.native.server,
            )

        suspend fun read() =
            useCase(base.queue.runtime, base.model.conversation).also {
                for (stage in
                    listOf(
                        "transport",
                        "acquire",
                        "resume",
                        "settle",
                        "append",
                        "update",
                        "space-append",
                        "space-update",
                        "close",
                        "closeSpace",
                    )) assertEquals(0, base.count(stage), stage)
            }

        suspend fun demand() = assertIs<AppResult.Success<Boolean>>(read()).value

        fun noCompanyReads() {
            for (stage in listOf("observe", "journal", "completion")) assertEquals(
                0,
                base.count(stage),
                stage,
            )
        }

        fun confirmCompany() {
            val sent = base.queue.sent()
            base.records[sent.intent.id] = sent
            base.journal = listOf(base.receipt())
        }

        fun confirmSpace(): CollaborationSpaceMessageRecord {
            val staged = record.state as CollaborationMessageState.Staged
            val sent =
                record.copy(
                    revision = 3,
                    state =
                        CollaborationMessageState.Sent(
                            staged.device,
                            staged.transactionId,
                            "\$space-accepted",
                            staged.origin,
                        ),
                )
            space.records[sent.intent.id] = sent
            space.journal =
                listOf(
                    CollaborationSpaceMessageReceipt(
                        sent.intent,
                        staged.device,
                        staged.transactionId,
                        requireNotNull(staged.origin),
                        listOf("\$space-accepted"),
                    )
                )
            return sent
        }
    }
}
