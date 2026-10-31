package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class CollaborationConversationCommandTest {
    @Test
    fun retainedDenialSurvivesFailedRetriesAndClearsOnlyAfterConfirmedSuccess() = runTest {
        val f = ConversationCommandFixture()
        val record = prepare(f)
        val denied =
            Failure(
                FailureKind.AccessDenied,
                "Peer unavailable",
                code = "collaboration_peer_unavailable",
            )
        f.remoteFailure = denied
        assertEquals(AppResult.Failed(denied), f.replay()("selection", record.command.id))
        for (kind in listOf(FailureKind.Network, FailureKind.Timeout, FailureKind.Conflict)) {
            f.remoteFailure = Failure(kind, "Retry failed")
            assertEquals(AppResult.Failed(denied), f.replay()("selection", record.command.id))
            assertEquals(
                denied,
                assertIs<CollaborationConversationOutcome.Unconfirmed>(
                        f.values.getValue(record.command.id).outcome
                    )
                    .failure,
            )
        }
        f.remoteFailure = null
        val success =
            assertIs<AppResult.Success<CollaborationConversationCommandRecord>>(
                    f.replay()("selection", record.command.id)
                )
                .value
        assertIs<CollaborationConversationOutcome.Confirmed>(success.outcome)
        assertTrue(f.sent.all { it.first == record.command.id })
    }

    @Test
    fun concurrentPreparationKeepsOneImmutableIntentAndDoesNotSend() = runTest {
        val f = ConversationCommandFixture()
        val release = CompletableDeferred<Unit>()
        var entered = 0
        f.beforeStorage = {
            if (it == "insert") {
                if (++entered == 2) release.complete(Unit)
                release.await()
            }
        }
        val action = CollaborationConversationAction.Open(COMMAND_PEER)
        val first = async { f.prepare()("selection", action) }
        val second = async { f.prepare()("selection", action) }
        val a =
            assertIs<AppResult.Success<CollaborationConversationCommandRecord>>(first.await()).value
        val b =
            assertIs<AppResult.Success<CollaborationConversationCommandRecord>>(second.await())
                .value
        assertEquals(a, b)
        assertEquals(1, f.values.size)
        assertTrue(f.sent.isEmpty())
    }

    @Test
    fun retryAfterLostResponsePreservesOperationAcrossAppUpgradeAndRequiresReconciliation() =
        runTest {
            val f = ConversationCommandFixture()
            val record = prepare(f)
            f.remoteFailure = Failure(FailureKind.Network, "Response lost")
            assertIs<AppResult.Failed>(f.replay()("selection", record.command.id))
            assertIs<CollaborationConversationOutcome.Unconfirmed>(
                f.values.getValue(record.command.id).outcome
            )
            f.remoteFailure = null
            f.build = f.build.copy(number = 6)
            val replay = f.replay()
            val confirmed =
                assertIs<AppResult.Success<CollaborationConversationCommandRecord>>(
                        replay("selection", record.command.id)
                    )
                    .value
            assertIs<CollaborationConversationOutcome.Confirmed>(confirmed.outcome)
            assertEquals(2, confirmed.attempts)
            assertEquals(f.sent[0], f.sent[1])
            assertEquals(AppResult.Success(confirmed), replay("selection", record.command.id))
            assertEquals(2, f.sent.size)
            assertEquals(AppResult.Success(true), f.reconcile()("selection", record.command.id))
            assertTrue(f.values.isEmpty())
        }

    @Test
    fun cancelledRequestLeavesUncertaintyDurableAndNextAttemptUsesOriginalOperation() = runTest {
        val f = ConversationCommandFixture()
        val record = prepare(f)
        val entered = CompletableDeferred<Unit>()
        f.beforeRemote = {
            if (it == "open") {
                assertIs<CollaborationConversationOutcome.Unconfirmed>(
                    f.values.getValue(record.command.id).outcome
                )
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        val work = async { f.replay()("selection", record.command.id) }
        entered.await()
        work.cancelAndJoin()
        assertEquals(1, f.values.getValue(record.command.id).attempts)
        f.beforeRemote = {}
        assertIs<AppResult.Success<*>>(f.replay()("selection", record.command.id))
        assertEquals(f.sent[0], f.sent[1])
    }

    @Test
    fun currentDenialOverridesLateResponseAndKeepsUnconfirmedIntent() = runTest {
        for (failed in listOf(false, true)) {
            val f = ConversationCommandFixture()
            val record = prepare(f)
            f.remoteFailure =
                if (failed) Failure(FailureKind.Network, "Network unavailable") else null
            f.beforeRemote = { f.setup.workspace = f.setup.selected.copy(permissions = emptySet()) }
            assertEquals(
                "permission_denied",
                assertIs<AppResult.Failed>(f.replay()("selection", record.command.id)).failure.code,
            )
            assertIs<CollaborationConversationOutcome.Unconfirmed>(
                f.values.getValue(record.command.id).outcome
            )
        }
    }

    @Test
    fun serverDenialWinsOverFailedOutcomePersistence() = runTest {
        val f = ConversationCommandFixture()
        val record = prepare(f)
        f.remoteFailure =
            Failure(
                FailureKind.AccessDenied,
                "Peer unavailable",
                code = "collaboration_peer_unavailable",
            )
        f.failOutcomeWrite = true
        assertEquals(
            AppResult.Failed(f.remoteFailure!!),
            f.replay()("selection", record.command.id),
        )
        assertIs<CollaborationConversationOutcome.Unconfirmed>(
            f.values.getValue(record.command.id).outcome
        )
    }

    @Test
    fun storedPreparationCannotPublishAfterSelectionReplacement() = runTest {
        val f = ConversationCommandFixture()
        f.beforeStorage = {
            if (it == "insert") f.setup.workspace = f.setup.selected.copy(id = "replacement")
        }
        assertIs<AppResult.Failed>(
            f.prepare()("selection", CollaborationConversationAction.Open(COMMAND_PEER))
        )
        assertEquals(1, f.values.size)
        assertTrue(f.sent.isEmpty())
    }

    @Test
    fun roomRetryChecksCurrentDeviceCreatorVersionAndWaitBeforePreparing() = runTest {
        val f = ConversationCommandFixture()
        val action = CollaborationConversationAction.RequestRoom(COMMAND_ROOM, COMMAND_DEVICE, 1, 3)
        f.room =
            CollaborationDirectRoom(
                COMMAND_ROOM,
                3,
                CollaborationDirectRoomState.FAILED,
                null,
                "account",
                true,
                (f.setup.now + 1.seconds).toEpochMilliseconds(),
            )
        assertIs<AppResult.Failed>(f.prepare()("selection", action))
        assertTrue(f.values.isEmpty())
        f.setup.now += 2.seconds
        val record =
            assertIs<AppResult.Success<CollaborationConversationCommandRecord>>(
                    f.prepare()("selection", action)
                )
                .value
        assertIs<AppResult.Failed>(f.prepare()("selection", action.copy(deviceVersion = 2)))
        assertIs<AppResult.Success<*>>(f.replay()("selection", record.command.id))
        assertEquals(action, f.sent.single().second)
        assertEquals(AppResult.Success(false), f.reconcile()("selection", record.command.id))
        f.room =
            f.room.copy(
                version = 4,
                state = CollaborationDirectRoomState.QUEUED,
                retryable = false,
                retryAt = null,
            )
        assertEquals(AppResult.Success(true), f.reconcile()("selection", record.command.id))
    }

    @Test
    fun wrongConversationPeerOrFailedObservationCannotRetireReceipt() = runTest {
        val f = ConversationCommandFixture()
        val record = prepare(f)
        assertIs<AppResult.Success<*>>(f.replay()("selection", record.command.id))
        f.conversation = f.conversation.copy(secondAccountId = "other")
        assertEquals(AppResult.Success(false), f.reconcile()("selection", record.command.id))
        assertEquals(1, f.values.size)
        f.remoteFailure = Failure(FailureKind.Network, "Unavailable")
        assertIs<AppResult.Failed>(f.reconcile()("selection", record.command.id))
        assertEquals(1, f.values.size)
    }

    @Test
    fun replacedRevisionAndLateDenialPreventReceiptRetirement() = runTest {
        val f = ConversationCommandFixture()
        val record = prepare(f)
        assertIs<AppResult.Success<*>>(f.replay()("selection", record.command.id))
        f.beforeStorage = {
            if (it == "remove")
                f.values[record.command.id] =
                    f.values.getValue(record.command.id).copy(revision = 90)
        }
        assertEquals(AppResult.Success(false), f.reconcile()("selection", record.command.id))
        assertEquals(90, f.values.getValue(record.command.id).revision)
        f.beforeStorage = {}
        f.beforeRemote = { f.setup.workspace = f.setup.selected.copy(permissions = emptySet()) }
        assertEquals(
            "permission_denied",
            assertIs<AppResult.Failed>(f.reconcile()("selection", record.command.id)).failure.code,
        )
        assertEquals(1, f.values.size)
    }

    @Test
    fun rejectedPreparationAndAttemptLimitNeverReachTransport() = runTest {
        val f = ConversationCommandFixture()
        assertIs<AppResult.Failed>(
            f.prepare()("selection", CollaborationConversationAction.Open("../invalid"))
        )
        assertTrue(f.values.isEmpty())
        val record = prepare(f)
        f.values[record.command.id] =
            record.copy(attempts = 1000, outcome = CollaborationConversationOutcome.Unconfirmed())
        assertEquals(
            "client.collaboration_attempt_limit",
            assertIs<AppResult.Failed>(f.replay()("selection", record.command.id)).failure.code,
        )
        assertTrue(f.sent.isEmpty())
    }

    private suspend fun prepare(f: ConversationCommandFixture) =
        assertIs<AppResult.Success<CollaborationConversationCommandRecord>>(
                f.prepare()("selection", CollaborationConversationAction.Open(COMMAND_PEER))
            )
            .value
}
