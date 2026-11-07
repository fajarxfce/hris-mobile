package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class SpaceRoomRequestResolutionTest {
    @Test
    fun exactFenceIsRetainedWithoutAnotherSendAttemptOrArchive() = runTest {
        val f = SpaceRoomRequestResolutionFixture()
        assertEquals(AppResult.Success(f.resolved()), f.resolve()(f.conversation, f.command.id))
        assertEquals(f.resolved(), f.local.active)
        assertEquals(listOf(f.conversation to f.command.intent), f.requests)
        assertEquals(1, f.local.active?.attempts)
        assertEquals(0, f.local.archives)
        assertEquals(0, f.local.base.admits)
        assertTrue(f.local.opens.isEmpty() && f.local.gets.isEmpty())
        assertNull(f.local.archived)
    }

    @Test
    fun originalReceiptIsRecordedWithoutPretendingThatItIsAFence() = runTest {
        val f = SpaceRoomRequestResolutionFixture()
        f.onResolve = {
            AppResult.Success(
                CollaborationSpaceRoomRequestResolution.Recorded(f.command.intent, f.receipt)
            )
        }
        val expected = f.resolved(CollaborationSpaceConversationOutcome.Recorded(f.receipt))
        assertEquals(AppResult.Success(expected), f.resolve()(f.conversation, f.command.id))
        assertEquals(expected, f.local.active)
        assertEquals(0, f.local.archives)
    }

    @Test
    fun terminalResultsRemainReadableOfflineAfterNativeAgeAndDirectoryDenial() = runTest {
        for (receipt in listOf(false, true)) {
            val f = SpaceRoomRequestResolutionFixture()
            val record =
                if (receipt) f.resolved(CollaborationSpaceConversationOutcome.Recorded(f.receipt))
                else f.resolved()
            f.local.active = record
            f.native.setup.now += 365.days
            f.native.setup.workspace = null
            f.local.base.deny()
            assertEquals(AppResult.Success(record), f.resolve()(f.conversation, f.command.id))
            assertTrue(f.requests.isEmpty() && f.local.updates.isEmpty())
            assertEquals(0, f.local.base.admits)
        }
    }

    @Test
    fun pendingOriginalIsNeverFencedOrMarkedAsDispatched() = runTest {
        val f = SpaceRoomRequestResolutionFixture()
        f.local.active =
            f.original.copy(
                revision = 0,
                attempts = 0,
                outcome = CollaborationSpaceConversationOutcome.Pending,
            )
        val before = f.local.active
        assertIs<AppResult.Failed>(f.resolve()(f.conversation, f.command.id))
        assertEquals(before, f.local.active)
        assertTrue(f.requests.isEmpty() && f.local.updates.isEmpty())
    }

    @Test
    fun changedNativeParentResolvesExactOldIntentWithoutAnOldDeviceLogin() = runTest {
        val f = SpaceRoomRequestResolutionFixture()
        val renewed = f.owner.copy(parent = f.owner.parent.copy(nativeSessionId = f.local.peer))
        f.native.setup.session =
            f.native.session.copy(binding = f.native.session.binding.copy(serverId = f.local.peer))
        val conversation = f.conversation.copy(owner = renewed)
        assertEquals(AppResult.Success(f.resolved()), f.resolve()(conversation, f.command.id))
        assertEquals(listOf(conversation to f.command.intent), f.requests)
        assertEquals(f.command, f.local.active?.command)
    }

    @Test
    fun maximumSendAttemptsStillAllowsOneExplicitResolution() = runTest {
        val f = SpaceRoomRequestResolutionFixture()
        f.local.active = f.original.copy(revision = 1000, attempts = 1000)
        val result =
            assertIs<AppResult.Success<CollaborationSpaceConversationCommandRecord>>(
                    f.resolve()(f.conversation, f.command.id)
                )
                .value
        assertEquals(1000, result.attempts)
        assertEquals(1002L, result.revision)
        assertEquals(CollaborationSpaceConversationOutcome.Fenced(f.evidence), result.outcome)
    }

    @Test
    fun missingWrongKindPeerAndConversationRejectBeforeTransport() = runTest {
        for (variant in 0..4) {
            val f = SpaceRoomRequestResolutionFixture()
            when (variant) {
                0 -> f.local.active = null
                1 -> f.local.active = f.original.copy(command = f.local.command)
                2 ->
                    f.local.active =
                        f.original.copy(command = f.command.copy(peerAccountId = f.evidence.id))
                3 ->
                    f.local.active =
                        f.original.copy(
                            command =
                                f.command.copy(
                                    intent = f.command.intent.copy(conversationId = f.evidence.id)
                                )
                        )
                4 -> f.local.active = f.original.copy(revision = 9_007_199_254_740_990L)
            }
            assertIs<AppResult.Failed>(f.resolve()(f.conversation, f.command.id))
            assertTrue(f.requests.isEmpty() && f.local.updates.isEmpty())
        }
    }

    @Test
    fun lostHttpResponseRetainsOriginalUncertaintyAndNextCallUsesSameIntent() = runTest {
        val f = SpaceRoomRequestResolutionFixture()
        f.onResolve = { throw CancellationException("Lost fence response") }
        assertFailsWith<CancellationException> { f.resolve()(f.conversation, f.command.id) }
        assertIs<CollaborationSpaceConversationOutcome.Unconfirmed>(f.local.active?.outcome)
        f.onResolve = {
            AppResult.Success(CollaborationSpaceRoomRequestResolution.Fenced(f.evidence))
        }
        val result =
            assertIs<AppResult.Success<CollaborationSpaceConversationCommandRecord>>(
                    f.resolve()(f.conversation, f.command.id)
                )
                .value
        assertEquals(f.command, result.command)
        assertEquals(1, result.attempts)
        assertEquals(2, f.requests.size)
        assertTrue(f.requests.all { it.second == f.command.intent })
    }

    @Test
    fun observedFenceSurvivesCallerCancellationAndReopensWithoutTransport() = runTest {
        val f = SpaceRoomRequestResolutionFixture()
        f.onResolve = {
            currentCoroutineContext().cancel()
            AppResult.Success(CollaborationSpaceRoomRequestResolution.Fenced(f.evidence))
        }
        val work = async { f.resolve()(f.conversation, f.command.id) }
        assertFailsWith<CancellationException> { work.await() }
        assertEquals(f.resolved(), f.local.active)
        assertEquals(AppResult.Success(f.resolved()), f.resolve()(f.conversation, f.command.id))
        assertEquals(1, f.requests.size)
    }

    @Test
    fun lostLocalOutcomeResponseIsRecoveredOnlyFromExactPersistedEvidence() = runTest {
        val f = SpaceRoomRequestResolutionFixture()
        f.local.onUpdate = { expected, outcome, attempts ->
            val result = f.local.commitUpdate(expected, outcome, attempts)
            if (outcome is CollaborationSpaceConversationOutcome.Fenced)
                AppResult.Failed(f.local.base.storageFailure)
            else result
        }
        assertEquals(AppResult.Success(f.resolved()), f.resolve()(f.conversation, f.command.id))
        assertEquals(f.resolved(), f.local.active)
    }

    @Test
    fun uncommittedOutcomeFailureDoesNotReportResolution() = runTest {
        val f = SpaceRoomRequestResolutionFixture()
        f.local.onUpdate = { expected, outcome, attempts ->
            if (outcome is CollaborationSpaceConversationOutcome.Fenced)
                AppResult.Failed(f.local.base.storageFailure)
            else f.local.commitUpdate(expected, outcome, attempts)
        }
        assertEquals(
            AppResult.Failed(f.local.base.storageFailure),
            f.resolve()(f.conversation, f.command.id),
        )
        assertIs<CollaborationSpaceConversationOutcome.Unconfirmed>(f.local.active?.outcome)
        assertEquals(1, f.requests.size)
    }

    @Test
    fun failedOrContendedMarkNeverStartsHttp() = runTest {
        for (failure in listOf(false, true)) {
            val f = SpaceRoomRequestResolutionFixture()
            f.local.onUpdate = { _, _, _ ->
                if (failure) AppResult.Failed(f.local.base.storageFailure)
                else AppResult.Success(false)
            }
            assertIs<AppResult.Failed>(f.resolve()(f.conversation, f.command.id))
            assertEquals(f.original, f.local.active)
            assertTrue(f.requests.isEmpty())
        }
    }

    @Test
    fun competingDifferentTerminalOutcomeIsNeverOverwritten() = runTest {
        val f = SpaceRoomRequestResolutionFixture()
        f.local.onUpdate = { expected, outcome, attempts ->
            if (outcome is CollaborationSpaceConversationOutcome.Fenced) {
                f.local.commitUpdate(
                    expected,
                    CollaborationSpaceConversationOutcome.Recorded(f.receipt),
                    attempts,
                )
                AppResult.Success(false)
            } else f.local.commitUpdate(expected, outcome, attempts)
        }
        assertIs<AppResult.Failed>(f.resolve()(f.conversation, f.command.id))
        assertEquals(
            CollaborationSpaceConversationOutcome.Recorded(f.receipt),
            f.local.active?.outcome,
        )
    }

    @Test
    fun changedOwnerAfterResponseCannotCaptureIntoReplacementAccount() = runTest {
        val f = SpaceRoomRequestResolutionFixture()
        f.onResolve = {
            f.native.setup.session = null
            AppResult.Success(CollaborationSpaceRoomRequestResolution.Fenced(f.evidence))
        }
        assertIs<AppResult.Failed>(f.resolve()(f.conversation, f.command.id))
        assertIs<CollaborationSpaceConversationOutcome.Unconfirmed>(f.local.active?.outcome)
        assertEquals(1, f.local.updates.size)
    }

    @Test
    fun peerDenialDoesNotCloseOtherContactsAndSurvivesFailedRetry() = runTest {
        val f = SpaceRoomRequestResolutionFixture()
        val denied =
            Failure(
                FailureKind.AccessDenied,
                "Peer unavailable",
                code = "collaboration_peer_unavailable",
            )
        f.onResolve = { AppResult.Failed(denied) }
        assertEquals(AppResult.Failed(denied), f.resolve()(f.conversation, f.command.id))
        f.onResolve = { AppResult.Failed(f.local.base.unavailable) }
        assertEquals(AppResult.Failed(denied), f.resolve()(f.conversation, f.command.id))
        assertEquals(
            CollaborationSpaceConversationOutcome.Unconfirmed(denied),
            f.local.active?.outcome,
        )
        assertEquals(0, f.local.base.restrictions)
        assertEquals(1, f.local.active?.attempts)
    }

    @Test
    fun cancelledGlobalDenialStaysGatedEvenIfOutcomeStorageFails() = runTest {
        val f = SpaceRoomRequestResolutionFixture()
        f.local.base.afterRestrict = { AppResult.Failed(f.local.base.storageFailure) }
        f.local.onUpdate = { expected, outcome, attempts ->
            if (outcome == CollaborationSpaceConversationOutcome.Unconfirmed(f.local.base.denied))
                AppResult.Failed(f.local.base.storageFailure)
            else f.local.commitUpdate(expected, outcome, attempts)
        }
        f.onResolve = {
            currentCoroutineContext().cancel()
            AppResult.Failed(f.local.base.denied)
        }
        val work = async { f.resolve()(f.conversation, f.command.id) }
        assertFailsWith<CancellationException> { work.await() }
        assertEquals(1, f.local.base.restrictions)
        assertEquals(
            f.local.base.denied,
            assertIs<AppResult.Success<CollaborationSpaceDirectoryAdmission>>(
                    f.local.base.gate.value
                )
                .value
                .denial,
        )
        f.local.onUpdate = { expected, outcome, attempts ->
            f.local.commitUpdate(expected, outcome, attempts)
        }
        f.onResolve = { AppResult.Failed(f.local.base.unavailable) }
        assertEquals(
            AppResult.Failed(f.local.base.denied),
            f.resolve()(f.conversation, f.command.id),
        )
        assertEquals(
            CollaborationSpaceConversationOutcome.Unconfirmed(f.local.base.denied),
            f.local.active?.outcome,
        )
    }

    @Test
    fun confirmedGlobalDenialIsNotWeakenedByPeerFailure() = runTest {
        val f = SpaceRoomRequestResolutionFixture()
        f.local.base.deny()
        f.onResolve = {
            AppResult.Failed(
                Failure(
                    FailureKind.AccessDenied,
                    "Peer unavailable",
                    code = "collaboration_peer_unavailable",
                )
            )
        }
        assertEquals(
            AppResult.Failed(f.local.base.denied),
            f.resolve()(f.conversation, f.command.id),
        )
        assertEquals(
            CollaborationSpaceConversationOutcome.Unconfirmed(f.local.base.denied),
            f.local.active?.outcome,
        )
        assertEquals(0, f.local.base.admits)
    }

    @Test
    fun wrongOriginalReceiptOrFenceIsNeverPersistedAsResolved() = runTest {
        for (variant in 0..5) {
            val f = SpaceRoomRequestResolutionFixture()
            val response =
                when (variant) {
                    0 ->
                        CollaborationSpaceRoomRequestResolution.Recorded(
                            f.command.intent.copy(operationId = f.evidence.id),
                            f.receipt,
                        )
                    1 ->
                        CollaborationSpaceRoomRequestResolution.Recorded(
                            f.command.intent,
                            f.receipt.copy(id = f.evidence.id),
                        )
                    2 ->
                        CollaborationSpaceRoomRequestResolution.Recorded(
                            f.command.intent,
                            f.receipt.copy(version = 1000000),
                        )
                    3 ->
                        CollaborationSpaceRoomRequestResolution.Fenced(
                            f.evidence.copy(
                                original =
                                    f.command.intent.copy(
                                        binding =
                                            f.command.binding.copy(nativeSessionId = f.evidence.id)
                                    )
                            )
                        )
                    4 ->
                        CollaborationSpaceRoomRequestResolution.Fenced(
                            f.evidence.copy(id = "00000000-0000-0000-0000-000000000000")
                        )
                    else ->
                        CollaborationSpaceRoomRequestResolution.Fenced(
                            f.evidence.copy(createdAt = f.conversation.createdAt - 1.seconds)
                        )
                }
            f.onResolve = { AppResult.Success(response) }
            assertIs<AppResult.Failed>(f.resolve()(f.conversation, f.command.id))
            assertIs<CollaborationSpaceConversationOutcome.Unconfirmed>(f.local.active?.outcome)
        }
    }

    @Test
    fun nativeExpiryAndClockRollbackBeforeDispatchDoNotStartHttp() = runTest {
        for (rollback in listOf(false, true)) {
            val f = SpaceRoomRequestResolutionFixture()
            f.local.base.beforeAdmission = {
                f.native.setup.now += if (rollback) -1.seconds else 3600.seconds
            }
            assertIs<AppResult.Failed>(f.resolve()(f.conversation, f.command.id))
            assertTrue(f.requests.isEmpty() && f.local.updates.isEmpty())
        }
    }

    @Test
    fun monotonicBudgetRejectsDispatchDespiteUnchangedWallClock() = runTest {
        val f = SpaceRoomRequestResolutionFixture()
        f.local.base.beforeAdmission = { f.native.time += 15.seconds }
        assertIs<AppResult.Failed>(f.resolve()(f.conversation, f.command.id))
        assertTrue(f.requests.isEmpty() && f.local.updates.isEmpty())
    }

    @Test
    fun transportAndResultCaptureAreBounded() = runTest {
        val f = SpaceRoomRequestResolutionFixture()
        f.onResolve = { awaitCancellation() }
        assertIs<AppResult.Failed>(f.resolve()(f.conversation, f.command.id))
        assertIs<CollaborationSpaceConversationOutcome.Unconfirmed>(f.local.active?.outcome)
        assertEquals(1, f.requests.size)
        f.onResolve = {
            AppResult.Success(CollaborationSpaceRoomRequestResolution.Fenced(f.evidence))
        }
        f.local.onUpdate = { expected, outcome, attempts ->
            if (outcome is CollaborationSpaceConversationOutcome.Fenced) awaitCancellation()
            else f.local.commitUpdate(expected, outcome, attempts)
        }
        assertIs<AppResult.Failed>(f.resolve()(f.conversation, f.command.id))
        assertIs<CollaborationSpaceConversationOutcome.Unconfirmed>(f.local.active?.outcome)
        assertEquals(2, f.requests.size)
    }

    @Test
    fun observedResultSurvivesAuthorityExpiryWithoutAdmittingContent() = runTest {
        val f = SpaceRoomRequestResolutionFixture()
        f.onResolve = {
            f.native.setup.now += 3600.seconds
            AppResult.Success(CollaborationSpaceRoomRequestResolution.Fenced(f.evidence))
        }
        assertEquals(AppResult.Success(f.resolved()), f.resolve()(f.conversation, f.command.id))
        assertEquals(0, f.local.base.admits)
        assertEquals(0, f.local.archives)
    }
}
