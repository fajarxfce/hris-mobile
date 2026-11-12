@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class ObserveCollaborationThreadTest {

    @Test
    fun slowConsumerCannotReceiveQueuedContentAfterCompanyDenial() = runTest {
        val f = ThreadObservationFixture()
        assertIs<AppResult.Success<*>>(f.owner.queue())
        val first = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val values = mutableListOf<AppResult<CollaborationRead<CollaborationThreadSnapshot>>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe().collect {
                    values += it
                    if (values.size == 1) {
                        first.complete(Unit)
                        release.await()
                    }
                }
            }
        first.await()
        f.owner.records.value = emptyList()
        runCurrent()
        f.owner.gate.value = CollaborationListAdmission("denied", f.owner.denied)
        runCurrent()
        assertEquals(0, f.history.active)
        assertEquals(0, f.owner.activeMessageObservers)
        release.complete(Unit)
        runCurrent()
        assertEquals(2, values.size)
        assertEquals(AppResult.Failed(f.owner.denied), values.last())
        job.cancelAndJoin()
    }

    @Test
    fun pendingRecipientMetadataDoesNotDelayTheFirstMessageWindow() = runTest {
        val f = ThreadObservationFixture()
        assertIs<AppResult.Success<*>>(f.owner.queue())
        f.recipientObservations.beforeObserve = { awaitCancellation() }
        val read =
            assertIs<AppResult.Success<CollaborationRead<CollaborationThreadSnapshot>>>(
                    f.observe().first()
                )
                .value
                .value
        assertEquals(f.owner.records.value, read.commands)
        assertEquals(f.history.page, read.history)
        assertFalse(read.recipientUnavailable)
        assertEquals(0, f.owner.activeMessageObservers)
    }

    @Test
    fun recipientObservationChangesDoNotReplaceCommandsOrHistory() = runTest {
        val f = ThreadObservationFixture()
        assertIs<AppResult.Success<*>>(f.owner.queue())
        val values = mutableListOf<AppResult<CollaborationRead<CollaborationThreadSnapshot>>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe().toList(values)
            }
        runCurrent()
        val original =
            assertIs<AppResult.Success<CollaborationRead<CollaborationThreadSnapshot>>>(
                    values.last()
                )
                .value
                .value
        assertFalse(original.recipientUnavailable)
        val waiting =
            CollaborationRecipientObservation(
                f.conversation,
                "a1000000-0000-4000-8000-000000001001",
                false,
            )
        f.recipientObservations.values.value = mapOf(f.conversation to waiting)
        runCurrent()
        val changed =
            assertIs<AppResult.Success<CollaborationRead<CollaborationThreadSnapshot>>>(
                    values.last()
                )
                .value
                .value
        assertTrue(changed.recipientUnavailable)
        assertEquals(original.commands, changed.commands)
        assertEquals(original.history, changed.history)
        val count = values.size
        f.recipientObservations.values.value = mapOf(f.conversation to waiting.copy())
        runCurrent()
        assertEquals(count, values.size)
        job.cancelAndJoin()
        assertEquals(0, f.owner.activeMessageObservers)
    }

    @Test
    fun historyAndQueueStartWhileContextIsPendingAndOpenWithExpiredCredentials() = runTest {
        val f = ThreadObservationFixture()
        f.owner.setup.now += 365.days
        val context = CompletableDeferred<Unit>()
        val history = CompletableDeferred<Unit>()
        val commands = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.owner.afterConversation = {
            context.complete(Unit)
            release.await()
        }
        f.history.beforeRead = { history.complete(Unit) }
        f.beforeCommands = { commands.complete(Unit) }
        val read = async { f.observe().first() }
        context.await()
        history.await()
        commands.await()
        assertFalse(read.isCompleted)
        release.complete(Unit)
        val result =
            assertIs<AppResult.Success<CollaborationRead<CollaborationThreadSnapshot>>>(
                    read.await()
                )
                .value
        assertEquals(f.owner.summary, result.value.context.summary)
        assertEquals(f.history.page, result.value.history)
        assertEquals(f.self, result.value.context.self)
        assertTrue(result.value.commands.isEmpty())
        assertEquals(0, f.owner.setup.calls)
        assertEquals(0, f.history.active)
        assertEquals(0, f.owner.activeMessageObservers)
    }

    @Test
    fun pendingLocalCommandsAndChangesShareTheOwnedHistoryWindow() = runTest {
        val f = ThreadObservationFixture()
        assertIs<AppResult.Success<*>>(f.owner.queue())
        val initial =
            assertIs<AppResult.Success<CollaborationRead<CollaborationThreadSnapshot>>>(
                    f.observe().first()
                )
                .value
                .value
        assertEquals(f.owner.records.value, initial.commands)
        assertEquals(f.history.page, initial.history)
        val changed = async { f.observe().take(2).toList() }
        runCurrent()
        f.owner.records.value = emptyList()
        val results = changed.await()
        assertEquals(2, results.size)
        assertTrue(
            assertIs<AppResult.Success<CollaborationRead<CollaborationThreadSnapshot>>>(
                    results.last()
                )
                .value
                .value
                .commands
                .isEmpty()
        )
    }

    @Test
    fun missingOrForeignContextCannotPublishContentOrGuessIdentity() = runTest {
        for (kind in 0..4) {
            val f = ThreadObservationFixture()
            val summary = f.owner.summary!!
            f.owner.summary =
                when (kind) {
                    0 -> null
                    1 -> summary.copy(conversation = summary.conversation.copy(companyId = "other"))
                    2 -> summary.copy(peer = summary.peer.copy(accountId = "other"))
                    3 ->
                        summary.copy(
                            conversation = summary.conversation.copy(firstAccountId = "other")
                        )
                    else -> summary.copy(conversation = summary.conversation.copy(id = "other"))
                }
            assertIs<AppResult.Failed>(f.observe().first())
            assertEquals(0, f.identityReads)
        }
    }

    @Test
    fun unknownIdentityRemainsUnknownAndInvalidIdentityOrFailedReadCannotPublish() = runTest {
        val f = ThreadObservationFixture()
        f.self = null
        assertNull(
            assertIs<AppResult.Success<CollaborationRead<CollaborationThreadSnapshot>>>(
                    f.observe().first()
                )
                .value
                .value
                .context
                .self
        )
        f.self = CollaborationSelfIdentity("invalid")
        assertIs<AppResult.Failed>(f.observe().first())
        f.identityFailure = f.owner.storage
        assertEquals(AppResult.Failed(f.owner.storage), f.observe().first())
        f.identityFailure = null
        f.self = null
        f.commandFailure = f.owner.storage
        assertEquals(AppResult.Failed(f.owner.storage), f.observe().first())
    }

    @Test
    fun denialCancelsPendingReadsAndOnlyAdmissionReopensThem() = runTest {
        val f = ThreadObservationFixture()
        val entered = CompletableDeferred<Unit>()
        val denied = CompletableDeferred<Unit>()
        val restored = CompletableDeferred<Unit>()
        f.history.beforeRead = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val job = launch {
            f.observe().collect {
                if (it == AppResult.Failed(f.owner.denied)) denied.complete(Unit)
                if (it is AppResult.Success) restored.complete(Unit)
            }
        }
        entered.await()
        f.owner.gate.value = CollaborationListAdmission("denied", f.owner.denied)
        denied.await()
        assertEquals(0, f.history.active)
        assertEquals(0, f.owner.activeMessageObservers)
        assertFalse(restored.isCompleted)
        f.history.beforeRead = {}
        f.owner.gate.value = CollaborationListAdmission("admitted", null)
        restored.await()
        job.cancelAndJoin()
        assertEquals(0, f.history.active)
        assertEquals(0, f.owner.activeMessageObservers)
    }

    @Test
    fun lateAccountWorkspaceAndKeyLossCannotPublishTheCapturedSnapshot() = runTest {
        for (kind in 0..2) {
            val f = ThreadObservationFixture()
            f.history.beforeRead = {
                when (kind) {
                    0 -> f.owner.setup.session = null
                    1 -> f.owner.setup.workspace = f.owner.setup.workspace!!.copy(id = "other")
                    else -> f.owner.setup.workspace = null
                }
            }
            assertIs<AppResult.Failed>(f.observe().first())
            assertEquals(0, f.history.active)
        }
    }

    @Test
    fun lateDenialRetainsPriorityOverFailedOwnershipRead() = runTest {
        val f = ThreadObservationFixture()
        f.beforeIdentity = {
            f.owner.gate.value = CollaborationListAdmission("denied", f.owner.denied)
            f.owner.setup.sessionFailure = f.owner.storage
        }
        assertEquals(AppResult.Failed(f.owner.denied), f.observe().first())
    }

    @Test
    fun unselectedWorkspaceCannotPublishEvenWhenTheAccountStillOwnsCachedFacts() = runTest {
        val f = ThreadObservationFixture()
        f.owner.setup.workspace = f.owner.setup.workspace!!.copy(id = "other")
        val result = assertIs<AppResult.Failed>(f.observe().first())
        assertEquals(FailureKind.AccessDenied, result.failure.kind)
        assertEquals("client.workspace_changed", result.failure.code)
        assertEquals(0, f.history.active)
        assertEquals(0, f.owner.activeMessageObservers)
    }

    @Test
    fun missingOrFailedSessionDoesNotStartPrivateReads() = runTest {
        val f = ThreadObservationFixture()
        f.owner.setup.sessionFailure = f.owner.storage
        assertEquals(AppResult.Failed(f.owner.storage), f.observe().first())
        f.owner.setup.sessionFailure = null
        f.owner.setup.session = null
        assertIs<AppResult.Failed>(f.observe().first())
        assertEquals(0, f.history.started)
        assertEquals(0, f.commandsStarted)
        assertEquals(0, f.identityReads)
    }

    @Test
    fun cancelledUncooperativeContextCannotStartIdentityOrPublish() = runTest {
        val f = ThreadObservationFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.owner.afterConversation = {
            withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
            }
        }
        val read = async { f.observe().first() }
        entered.await()
        read.cancel()
        release.complete(Unit)
        read.join()
        assertTrue(read.isCancelled)
        assertEquals(0, f.identityReads)
        assertEquals(0, f.history.active)
        assertEquals(0, f.owner.activeMessageObservers)
    }

    @Test
    fun invalidScopeWindowAndExistingDenialDoNotStartContentReads() = runTest {
        val f = ThreadObservationFixture()
        assertIs<AppResult.Failed>(f.observe(expected = f.access.copy(accountId = "other")).first())
        val anchor = "0000000000000001/m-" + "a".repeat(64)
        for (window in
            listOf(
                CollaborationHistoryWindow(limit = 151),
                CollaborationHistoryWindow(overlap = 50),
                CollaborationHistoryWindow(after = "invalid"),
                CollaborationHistoryWindow(after = anchor, before = anchor),
            )) assertIs<AppResult.Failed>(f.observe(window = window).first())
        f.owner.gate.value = CollaborationListAdmission("denied", f.owner.denied)
        assertEquals(AppResult.Failed(f.owner.denied), f.observe().first())
        assertEquals(0, f.history.started)
        assertEquals(0, f.commandsStarted)
        assertEquals(0, f.identityReads)
    }

    @Test
    fun duplicateForeignOrUnboundedCommandsCannotPublish() = runTest {
        val f = ThreadObservationFixture()
        assertIs<AppResult.Success<*>>(f.owner.queue())
        val record = f.owner.records.value.single()
        for (records in
            listOf(
                listOf(record, record),
                List(129) { record },
                listOf(record.copy(intent = record.intent.copy(conversationId = "other"))),
                listOf(
                    record.copy(
                        intent =
                            record.intent.copy(
                                owner = record.intent.owner.copy(accountId = "other")
                            )
                    )
                ),
            )) {
            f.owner.records.value = records
            assertIs<AppResult.Failed>(f.observe().first())
        }
    }

    @Test
    fun snapshotIsFrozenBeforeTheSuspendingFinalGuard() = runTest {
        val f = ThreadObservationFixture()
        assertIs<AppResult.Success<*>>(f.owner.queue())
        val original = f.owner.records.value.single()
        val mutable = f.owner.records.value.toMutableList()
        f.owner.records.value = emptyList()
        f.owner.records.value = mutable
        assertTrue(f.owner.records.value === mutable)
        f.owner.beforeAdmission = { mutable.clear() }
        val read =
            assertIs<AppResult.Success<CollaborationRead<CollaborationThreadSnapshot>>>(
                    f.observe().first()
                )
                .value
                .value
        assertEquals(listOf(original), read.commands)
        assertTrue(mutable.isEmpty())
    }
}
