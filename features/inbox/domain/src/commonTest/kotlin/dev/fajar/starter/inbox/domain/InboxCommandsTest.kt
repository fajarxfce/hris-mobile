package dev.fajar.starter.inbox.domain

import dev.fajar.starter.common.result.*
import dev.fajar.starter.inbox.domain.entities.*
import dev.fajar.starter.inbox.domain.policies.*
import dev.fajar.starter.sync.domain.SyncResult
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class InboxCommandsTest {
    @Test
    fun intentIsDurableBeforeTransmissionAndDuplicatePreparationRetainsItsIdentity() = runTest {
        val fixture = InboxCommandFixture()
        val original = assertIs<AppResult.Success<InboxCommandRecord>>(fixture.queue()).value
        assertEquals(
            original,
            assertIs<AppResult.Success<InboxCommandRecord>>(fixture.queue()).value,
        )
        assertTrue(fixture.transmitted.isEmpty())
        fixture.transmit = {
            val stored = fixture.records.value.single()
            assertIs<InboxCommandOutcome.Unconfirmed>(stored.outcome)
            assertEquals(1, stored.attempts)
            assertEquals(original.command, it)
            AppResult.Success(InboxActionReceipt(it.itemId, it.expectedVersion + 1))
        }
        val sent =
            assertIs<AppResult.Success<InboxCommandRecord>>(
                    fixture.replay(fixture.inbox.workspace.id, original.command.id)
                )
                .value
        assertEquals(InboxCommandOutcome.Confirmed(2), sent.outcome)
        assertEquals(sent, fixture.records.value.single())
        fixture.replay(fixture.inbox.workspace.id, original.command.id)
        assertEquals(1, fixture.transmitted.size)
    }

    @Test
    fun anotherActionCannotReplaceThePendingResourceCommand() = runTest {
        val fixture = InboxCommandFixture()
        val first = assertIs<AppResult.Success<InboxCommandRecord>>(fixture.queue()).value
        assertEquals(
            "client.inbox_action_pending",
            assertIs<AppResult.Failed>(fixture.queue(InboxAction.ACKNOWLEDGE)).failure.code,
        )
        assertEquals(listOf(first), fixture.records.value)
    }

    @Test
    fun onlyCurrentAdmittedContentCanCreateAnAction() = runTest {
        val fixture = InboxCommandFixture()
        assertIs<AppResult.Failed>(fixture.queue(version = 0))
        fixture.publish(fixture.inbox.item.copy(acknowledgementRequired = false))
        assertEquals(
            "client.inbox_action_unavailable",
            assertIs<AppResult.Failed>(fixture.queue(InboxAction.ACKNOWLEDGE)).failure.code,
        )
        fixture.publish(fixture.inbox.item.copy(readAt = fixture.inbox.clock.now()))
        assertIs<AppResult.Failed>(fixture.queue())
        val page = (fixture.inbox.snapshot.value as AppResult.Success).value
        fixture.inbox.snapshot.value =
            AppResult.Success(
                page.copy(
                    checkpoint = page.checkpoint!!.copy(refreshAfter = fixture.inbox.clock.now())
                )
            )
        assertEquals(
            "client.inbox_review_required",
            assertIs<AppResult.Failed>(fixture.queue()).failure.code,
        )
        assertTrue(fixture.records.value.isEmpty())
    }

    @Test
    fun lateLocalCommitRetainsTheOriginalIntentButCannotPublishToAnotherCompany() = runTest {
        val fixture = InboxCommandFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.beforeInsert = {
            entered.complete(Unit)
            withContext(NonCancellable) { release.await() }
        }
        val pending = async { fixture.queue() }
        entered.await()
        fixture.inbox.workspaceState.value =
            AppResult.Success(
                fixture.inbox.workspace.copy(
                    id = "other",
                    company = fixture.inbox.workspace.company.copy(id = "other-company"),
                )
            )
        release.complete(Unit)
        assertEquals(
            FailureKind.AccessDenied,
            assertIs<AppResult.Failed>(pending.await()).failure.kind,
        )
        assertEquals(
            fixture.inbox.workspace.company.id,
            fixture.records.value.single().command.companyId,
        )
        assertTrue(fixture.transmitted.isEmpty())
    }

    @Test
    fun tokenRotationDoesNotInvalidateTheSameAccountAndWorkspace() = runTest {
        val fixture = InboxCommandFixture()
        fixture.beforeInsert = {
            fixture.inbox.sessionState.value =
                AppResult.Success(
                    fixture.inbox.session.copy(
                        tokens = fixture.inbox.session.tokens.copy(accessToken = "rotated")
                    )
                )
        }
        assertIs<AppResult.Success<InboxCommandRecord>>(fixture.queue())
    }

    @Test
    fun aLateReadAfterInsertionCannotAdmitACommandForAReplacedWorkspace() = runTest {
        val fixture = InboxCommandFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.beforeObserve = {
            if (fixture.records.value.isNotEmpty()) {
                entered.complete(Unit)
                withContext(NonCancellable) { release.await() }
            }
        }
        val pending = async { fixture.queue() }
        entered.await()
        fixture.inbox.workspaceState.value = AppResult.Success(null)
        release.complete(Unit)
        assertIs<AppResult.Failed>(pending.await())
        assertEquals(1, fixture.records.value.size)
        assertTrue(fixture.transmitted.isEmpty())
    }

    @Test
    fun theWorkerRetiresOnlyConfirmedReceiptsReflectedInAnAdmittedReplica() = runTest {
        val fixture = InboxCommandFixture()
        val original = assertIs<AppResult.Success<InboxCommandRecord>>(fixture.queue()).value
        fixture.replay(fixture.inbox.workspace.id, original.command.id)
        assertEquals(SyncResult.Complete, fixture.replay())
        assertEquals(1, fixture.records.value.size)
        fixture.publish(fixture.inbox.item.copy(version = 2, readAt = fixture.inbox.clock.now()))
        val page = (fixture.inbox.snapshot.value as AppResult.Success).value
        fixture.inbox.snapshot.value =
            AppResult.Success(
                page.copy(
                    checkpoint = page.checkpoint!!.copy(refreshAfter = fixture.inbox.clock.now())
                )
            )
        fixture.replay()
        assertEquals(1, fixture.records.value.size)
        fixture.inbox.snapshot.value = AppResult.Success(page)
        fixture.replay()
        assertTrue(fixture.records.value.isEmpty())
        assertEquals(listOf(original.command), fixture.transmitted)
        assertEquals(0, fixture.itemCalls)

        fixture.records.value =
            listOf(
                original.copy(
                    outcome = InboxCommandOutcome.Unconfirmed(),
                    attempts = INBOX_AUTOMATIC_ATTEMPTS,
                )
            )
        fixture.replay()
        assertIs<InboxCommandOutcome.Unconfirmed>(fixture.records.value.single().outcome)
    }

    @Test
    fun confirmedEvidenceCanRetireWhenAPublishedReplicaNoLongerExposesTheItem() = runTest {
        val fixture = InboxCommandFixture()
        val original = assertIs<AppResult.Success<InboxCommandRecord>>(fixture.queue()).value
        fixture.replay(fixture.inbox.workspace.id, original.command.id)
        fixture.inbox.snapshot.value = AppResult.Success(InboxCachePage(null, emptyList(), null))
        fixture.replay()
        assertEquals(1, fixture.records.value.size)
        fixture.inbox.snapshot.value =
            AppResult.Success(InboxCachePage(fixture.inbox.checkpoint, emptyList(), null))
        fixture.replay()
        assertTrue(fixture.records.value.isEmpty())
        assertEquals(listOf(original.command), fixture.transmitted)
    }

    @Test
    fun anInitialZeroVersionCanBeDeliveredWithoutChangingItsPrecondition() = runTest {
        val fixture = InboxCommandFixture()
        fixture.publish(fixture.inbox.item.copy(version = 0))
        val original =
            assertIs<AppResult.Success<InboxCommandRecord>>(fixture.queue(version = 0)).value
        val result =
            assertIs<AppResult.Success<InboxCommandRecord>>(
                    fixture.replay(fixture.inbox.workspace.id, original.command.id)
                )
                .value
        assertEquals(0, fixture.transmitted.single().expectedVersion)
        assertEquals(InboxCommandOutcome.Confirmed(1), result.outcome)
    }

    @Test
    fun lostResponseAndLaterRejectionNeverReplaceTheOriginalCommand() = runTest {
        val fixture = InboxCommandFixture()
        val record =
            assertIs<AppResult.Success<InboxCommandRecord>>(fixture.queue(InboxAction.ACKNOWLEDGE))
                .value
        fixture.transmit = { AppResult.Failed(Failure(FailureKind.Network, "offline")) }
        fixture.replay(fixture.inbox.workspace.id, record.command.id)
        assertIs<InboxCommandOutcome.Unconfirmed>(fixture.records.value.single().outcome)
        fixture.transmit = {
            AppResult.Failed(Failure(FailureKind.Conflict, "stale", code = "stale_version"))
        }
        fixture.replay(fixture.inbox.workspace.id, record.command.id)
        val retained = fixture.records.value.single()
        assertEquals(record.command, retained.command)
        assertEquals(2, retained.attempts)
        assertIs<InboxCommandOutcome.Unconfirmed>(retained.outcome)
        assertEquals(listOf(record.command, record.command), fixture.transmitted)
        assertEquals(
            "client.inbox_action_unconfirmed",
            assertIs<AppResult.Failed>(
                    fixture.review(fixture.inbox.workspace.id, record.command.id)
                )
                .failure
                .code,
        )
        assertEquals(0, fixture.itemCalls)
    }

    @Test
    fun definiteConflictRequiresFreshContentBeforeAnotherOperationIsAllowed() = runTest {
        val fixture = InboxCommandFixture()
        val original = assertIs<AppResult.Success<InboxCommandRecord>>(fixture.queue()).value
        fixture.transmit = {
            AppResult.Failed(Failure(FailureKind.Conflict, "stale", code = "stale_version"))
        }
        fixture.replay(fixture.inbox.workspace.id, original.command.id)
        assertIs<InboxCommandOutcome.Rejected>(fixture.records.value.single().outcome)
        fixture.canonical = fixture.inbox.item.copy(version = 2, readAt = fixture.inbox.clock.now())
        assertEquals(
            "client.inbox_updates_pending",
            assertIs<AppResult.Failed>(
                    fixture.review(fixture.inbox.workspace.id, original.command.id)
                )
                .failure
                .code,
        )
        fixture.publish(fixture.canonical)
        assertEquals(
            AppResult.Success(true),
            fixture.review(fixture.inbox.workspace.id, original.command.id),
        )
        val next =
            assertIs<AppResult.Success<InboxCommandRecord>>(
                    fixture.queue(InboxAction.ACKNOWLEDGE, 2)
                )
                .value
        assertNotEquals(original.command.id, next.command.id)
        assertEquals(2, next.command.expectedVersion)
    }

    @Test
    fun cancellationAndLateTechnicalFailureLeaveUnconfirmedEvidence() = runTest {
        val fixture = InboxCommandFixture()
        val original = assertIs<AppResult.Success<InboxCommandRecord>>(fixture.queue()).value
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.transmit = {
            entered.complete(Unit)
            withContext(NonCancellable) { release.await() }
            AppResult.Failed(Failure(FailureKind.Conflict, "stale", code = "stale_version"))
        }
        val pending = async { fixture.replay(fixture.inbox.workspace.id, original.command.id) }
        entered.await()
        pending.cancel()
        release.complete(Unit)
        assertFailsWith<CancellationException> { pending.await() }
        assertEquals(InboxCommandOutcome.Unconfirmed(), fixture.records.value.single().outcome)
        assertEquals(1, fixture.records.value.single().revision)
    }

    @Test
    fun aReceiptMayBeRetainedForItsAccountAfterTheCompanyScreenChanges() = runTest {
        val fixture = InboxCommandFixture()
        val original = assertIs<AppResult.Success<InboxCommandRecord>>(fixture.queue()).value
        fixture.transmit = {
            fixture.inbox.workspaceState.value = AppResult.Success(null)
            AppResult.Success(InboxActionReceipt(it.itemId, 2))
        }
        assertIs<AppResult.Failed>(fixture.replay(fixture.inbox.workspace.id, original.command.id))
        assertEquals(InboxCommandOutcome.Confirmed(2), fixture.records.value.single().outcome)
    }

    @Test
    fun competingSendersRecoverOneReceiptWithoutSendingTwice() = runTest {
        val fixture = InboxCommandFixture()
        val original = assertIs<AppResult.Success<InboxCommandRecord>>(fixture.queue()).value
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.transmit = {
            entered.complete(Unit)
            release.await()
            AppResult.Success(InboxActionReceipt(it.itemId, 2))
        }
        val first = async { fixture.replay(fixture.inbox.workspace.id, original.command.id) }
        entered.await()
        val second = async { fixture.replay(fixture.inbox.workspace.id, original.command.id) }
        runCurrent()
        release.complete(Unit)
        assertEquals(first.await(), second.await())
        assertEquals(1, fixture.transmitted.size)
    }

    @Test
    fun automaticAttemptsAreBoundedAndExplicitRetryKeepsTheSameRequest() = runTest {
        val fixture = InboxCommandFixture()
        val original = assertIs<AppResult.Success<InboxCommandRecord>>(fixture.queue()).value
        fixture.transmit = { AppResult.Failed(Failure(FailureKind.Network, "offline")) }
        repeat(INBOX_AUTOMATIC_ATTEMPTS) { assertIs<SyncResult.Retry>(fixture.replay()) }
        assertEquals(SyncResult.Complete, fixture.replay())
        assertEquals(INBOX_AUTOMATIC_ATTEMPTS, fixture.transmitted.size)
        fixture.transmit = { AppResult.Success(InboxActionReceipt(it.itemId, 2)) }
        assertIs<AppResult.Success<InboxCommandRecord>>(
            fixture.replay(fixture.inbox.workspace.id, original.command.id)
        )
        assertTrue(fixture.transmitted.all { it == original.command })
    }

    @Test
    fun storageFailurePreventsTransmissionAndPreservesItsClassification() = runTest {
        val fixture = InboxCommandFixture()
        val original = assertIs<AppResult.Success<InboxCommandRecord>>(fixture.queue()).value
        val failure = Failure(FailureKind.Storage, "fixture", code = "fixture.storage")
        fixture.updateFailure = failure
        assertEquals(
            AppResult.Failed(failure),
            fixture.replay(fixture.inbox.workspace.id, original.command.id),
        )
        assertEquals(listOf(original), fixture.records.value)
        assertTrue(fixture.transmitted.isEmpty())
        fixture.inbox.workspaceState.value = AppResult.Failed(failure)
        assertEquals(AppResult.Failed(failure), fixture.queue())
    }

    @Test
    fun malformedReceiptsRemainUnconfirmedAndNoOpReceiptsAreAccepted() = runTest {
        val fixture = InboxCommandFixture()
        val original = assertIs<AppResult.Success<InboxCommandRecord>>(fixture.queue()).value
        fixture.transmit = { AppResult.Success(InboxActionReceipt("other", 4)) }
        fixture.replay(fixture.inbox.workspace.id, original.command.id)
        assertIs<InboxCommandOutcome.Unconfirmed>(fixture.records.value.single().outcome)
        fixture.transmit = { AppResult.Success(InboxActionReceipt(it.itemId, it.expectedVersion)) }
        fixture.replay(fixture.inbox.workspace.id, original.command.id)
        assertEquals(InboxCommandOutcome.Confirmed(1), fixture.records.value.single().outcome)
    }

    @Test
    fun scopeReplacementHidesCommandsButCredentialExpiryRetainsTheirLocalStatus() = runTest {
        val start = kotlin.time.Instant.parse("2026-10-11T00:00:00Z")
        val fixture =
            InboxCommandFixture(
                InboxFixture(sessionLifetime = 30.minutes) {
                    start + testScheduler.currentTime.milliseconds
                }
            )
        fixture.queue()
        val results = mutableListOf<AppResult<InboxCommandRecord?>>()
        val observation =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                fixture.observe(fixture.inbox.workspace.id, fixture.inbox.item.id).toList(results)
            }
        assertNotNull(assertIs<AppResult.Success<InboxCommandRecord?>>(results.last()).value)
        fixture.inbox.workspaceState.value = AppResult.Success(null)
        runCurrent()
        assertIs<AppResult.Failed>(results.last())
        fixture.inbox.workspaceState.value = AppResult.Success(fixture.inbox.workspace)
        runCurrent()
        val expiresIn =
            fixture.inbox.session.binding.expiresAtEpochMillis - start.toEpochMilliseconds()
        advanceTimeBy(expiresIn)
        runCurrent()
        assertNotNull(assertIs<AppResult.Success<InboxCommandRecord?>>(results.last()).value)
        observation.cancel()
    }
}
