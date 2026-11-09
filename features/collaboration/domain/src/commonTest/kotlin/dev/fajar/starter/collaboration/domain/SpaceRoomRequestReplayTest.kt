package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class SpaceRoomRequestReplayTest {
    @Test
    fun pendingOriginalIsMarkedBeforeItsSingleRequestAndReceiptIsRetained() = runTest {
        val f = SpaceRoomRequestReplayFixture()
        assertEquals(AppResult.Success(f.recorded()), f.replay()(f.conversation, f.command.id))
        assertEquals(f.recorded(), f.local.active)
        assertEquals(listOf(f.conversation to f.command.intent.request), f.requests)
        assertEquals(0, f.local.archives)
        assertEquals(0, f.local.metadataWrites)
        assertEquals(0, f.local.base.admits)
        assertTrue(f.local.opens.isEmpty() && f.local.gets.isEmpty())
        assertTrue(f.resolution.requests.isEmpty())
    }

    @Test
    fun recordedAndFencedFactsReturnOfflineWithoutReopeningTheirOriginals() = runTest {
        for (fenced in listOf(false, true)) {
            val f = SpaceRoomRequestReplayFixture()
            val record = if (fenced) f.resolution.resolved() else f.recorded()
            f.local.active = record
            f.native.setup.now += 365.days
            f.native.setup.workspace = null
            f.local.base.deny()
            assertEquals(AppResult.Success(record), f.replay()(f.conversation, f.command.id))
            assertTrue(f.requests.isEmpty() && f.local.updates.isEmpty())
            assertEquals(0, f.local.base.admits)
        }
    }

    @Test
    fun renewedNativeParentDoesNotReplaceOriginalDeviceOrObservedParticipantRevision() = runTest {
        val f = SpaceRoomRequestReplayFixture()
        val current =
            f.conversation.copy(
                owner =
                    f.conversation.owner.copy(
                        parent = f.conversation.owner.parent.copy(nativeSessionId = f.local.peer)
                    )
            )
        f.native.setup.session =
            f.native.session.copy(binding = f.native.session.binding.copy(serverId = f.local.peer))
        assertEquals(AppResult.Success(f.recorded()), f.replay()(current, f.command.id))
        assertEquals(listOf(current to f.command.intent.request), f.requests)
        assertEquals(f.command, f.local.active?.command)
    }

    @Test
    fun lostHttpResponseRetainsUncertaintyAndExplicitReplayUsesTheSameRequest() = runTest {
        val f = SpaceRoomRequestReplayFixture()
        f.onRequest = { throw CancellationException("Lost response") }
        assertFailsWith<CancellationException> { f.replay()(f.conversation, f.command.id) }
        assertIs<CollaborationSpaceConversationOutcome.Unconfirmed>(f.local.active?.outcome)
        assertEquals(1, f.local.active?.attempts)
        f.onRequest = { AppResult.Success(f.receipt) }
        val result =
            assertIs<AppResult.Success<CollaborationSpaceConversationCommandRecord>>(
                    f.replay()(f.conversation, f.command.id)
                )
                .value
        assertEquals(f.command, result.command)
        assertEquals(2, result.attempts)
        assertEquals(2, f.requests.size)
        assertTrue(f.requests.all { it.second == f.command.intent.request })
    }

    @Test
    fun observedReceiptSurvivesCallerCancellationAndDoesNotSendOnReopen() = runTest {
        val f = SpaceRoomRequestReplayFixture()
        f.onRequest = {
            currentCoroutineContext().cancel()
            AppResult.Success(f.receipt)
        }
        val work = async { f.replay()(f.conversation, f.command.id) }
        assertFailsWith<CancellationException> { work.await() }
        assertEquals(f.recorded(), f.local.active)
        assertEquals(AppResult.Success(f.recorded()), f.replay()(f.conversation, f.command.id))
        assertEquals(1, f.requests.size)
    }

    @Test
    fun interruptedOutcomeWriteOnlyRecoversAnExactlyPersistedReceipt() = runTest {
        for (committed in listOf(false, true)) {
            val f = SpaceRoomRequestReplayFixture()
            f.local.onUpdate = { expected, outcome, attempts ->
                if (outcome is CollaborationSpaceConversationOutcome.Recorded) {
                    if (committed) f.local.commitUpdate(expected, outcome, attempts)
                    AppResult.Failed(f.local.base.storageFailure)
                } else f.local.commitUpdate(expected, outcome, attempts)
            }
            val result = f.replay()(f.conversation, f.command.id)
            if (committed) assertEquals(AppResult.Success(f.recorded()), result)
            else {
                assertEquals(AppResult.Failed(f.local.base.storageFailure), result)
                assertIs<CollaborationSpaceConversationOutcome.Unconfirmed>(f.local.active?.outcome)
            }
            assertEquals(1, f.requests.size)
        }
    }

    @Test
    fun failedContendedOrLostMarkDoesNotSendUntilAnExplicitOriginalReplay() = runTest {
        for (variant in 0..2) {
            val f = SpaceRoomRequestReplayFixture()
            f.local.onUpdate = { expected, outcome, attempts ->
                if (variant == 2) f.local.commitUpdate(expected, outcome, attempts)
                if (variant == 1) AppResult.Success(false)
                else AppResult.Failed(f.local.base.storageFailure)
            }
            assertIs<AppResult.Failed>(f.replay()(f.conversation, f.command.id))
            assertTrue(f.requests.isEmpty())
            assertEquals(f.command, f.local.active?.command)
            f.local.onUpdate = { expected, outcome, attempts ->
                f.local.commitUpdate(expected, outcome, attempts)
            }
            assertIs<AppResult.Success<*>>(f.replay()(f.conversation, f.command.id))
            assertEquals(1, f.requests.size)
        }
    }

    @Test
    fun competingFenceIsNeverOverwrittenByALateReceipt() = runTest {
        val f = SpaceRoomRequestReplayFixture()
        f.local.onUpdate = { expected, outcome, attempts ->
            if (outcome is CollaborationSpaceConversationOutcome.Recorded) {
                f.local.commitUpdate(
                    expected,
                    CollaborationSpaceConversationOutcome.Fenced(f.resolution.evidence),
                    attempts,
                )
                AppResult.Success(false)
            } else f.local.commitUpdate(expected, outcome, attempts)
        }
        assertIs<AppResult.Failed>(f.replay()(f.conversation, f.command.id))
        assertIs<CollaborationSpaceConversationOutcome.Fenced>(f.local.active?.outcome)
        assertEquals(1, f.requests.size)
    }

    @Test
    fun concurrentOriginalAttemptsRecoverTheSameDurableReceiptWithoutReplacement() = runTest {
        val f = SpaceRoomRequestReplayFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.onRequest = {
            if (f.requests.size == 1) {
                entered.complete(Unit)
                release.await()
            }
            AppResult.Success(f.receipt)
        }
        val first = async { f.replay()(f.conversation, f.command.id) }
        entered.await()
        val second =
            assertIs<AppResult.Success<CollaborationSpaceConversationCommandRecord>>(
                f.replay()(f.conversation, f.command.id)
            )
        release.complete(Unit)
        assertEquals(second, first.await())
        assertEquals(second.value, f.local.active)
        assertEquals(2, second.value.attempts)
        assertEquals(f.command, second.value.command)
        assertEquals(2, f.requests.size)
        assertTrue(f.requests.all { it.second == f.command.intent.request })
    }

    @Test
    fun changedOwnerAtEverySuspendedBoundaryRejectsLateWork() = runTest {
        for (boundary in listOf("find", "admission", "mark", "response", "capture")) {
            val f = SpaceRoomRequestReplayFixture()
            when (boundary) {
                "find" -> f.local.beforeFind = { f.native.setup.session = null }
                "admission" -> f.local.base.beforeAdmission = { f.native.setup.session = null }
                "mark" -> f.local.onUpdate = { expected, outcome, attempts ->
                        f.local.commitUpdate(expected, outcome, attempts).also {
                            f.native.setup.session = null
                        }
                    }
                "response" -> f.onRequest = {
                        f.native.setup.session = null
                        AppResult.Success(f.receipt)
                    }
                "capture" -> f.local.onUpdate = { expected, outcome, attempts ->
                        f.local.commitUpdate(expected, outcome, attempts).also {
                            if (outcome is CollaborationSpaceConversationOutcome.Recorded)
                                f.native.setup.session = null
                        }
                    }
            }
            assertIs<AppResult.Failed>(f.replay()(f.conversation, f.command.id))
            assertEquals(if (boundary in listOf("response", "capture")) 1 else 0, f.requests.size)
        }
    }

    @Test
    fun maximumAttemptsAndInsufficientRevisionCapacityNeverDispatch() = runTest {
        for (revision in listOf(1000L, 9_007_199_254_740_990L)) {
            val f = SpaceRoomRequestReplayFixture()
            f.local.active =
                f.resolution.original.copy(
                    revision = revision,
                    attempts = if (revision == 1000L) 1000 else 1,
                )
            val before = f.local.active
            assertIs<AppResult.Failed>(f.replay()(f.conversation, f.command.id))
            assertEquals(before, f.local.active)
            assertTrue(f.requests.isEmpty() && f.local.updates.isEmpty())
        }
    }

    @Test
    fun malformedOriginalOrReceiptNeverBecomesAnAcceptedRequest() = runTest {
        for (variant in 0..5) {
            val f = SpaceRoomRequestReplayFixture()
            when (variant) {
                0 -> f.local.active = null
                1 -> f.local.active = f.original.copy(command = f.local.command)
                2 ->
                    f.local.active =
                        f.original.copy(
                            command = f.command.copy(peerAccountId = f.resolution.evidence.id)
                        )
                3 ->
                    f.local.active =
                        f.original.copy(
                            command =
                                f.command.copy(
                                    intent =
                                        f.command.intent.copy(
                                            conversationId = f.resolution.evidence.id
                                        )
                                )
                        )
                4 -> f.onRequest = {
                        AppResult.Success(f.receipt.copy(id = f.resolution.evidence.id))
                    }
                5 -> f.onRequest = { AppResult.Success(f.receipt.copy(version = 1_000_000)) }
            }
            assertIs<AppResult.Failed>(f.replay()(f.conversation, f.command.id))
            assertEquals(if (variant < 4) 0 else 1, f.requests.size)
            assertFalse(f.local.active?.outcome is CollaborationSpaceConversationOutcome.Recorded)
        }
    }

    @Test
    fun peerDenialSurvivesUnavailableRetryWithoutClosingOtherContacts() = runTest {
        val f = SpaceRoomRequestReplayFixture()
        val denial =
            Failure(
                FailureKind.AccessDenied,
                "Peer unavailable",
                code = "collaboration_peer_unavailable",
            )
        f.onRequest = { AppResult.Failed(denial) }
        assertEquals(AppResult.Failed(denial), f.replay()(f.conversation, f.command.id))
        f.onRequest = { AppResult.Failed(f.local.base.unavailable) }
        assertEquals(AppResult.Failed(denial), f.replay()(f.conversation, f.command.id))
        assertEquals(
            CollaborationSpaceConversationOutcome.Unconfirmed(denial),
            f.local.active?.outcome,
        )
        assertEquals(0, f.local.base.restrictions)
    }

    @Test
    fun cancelledGlobalDenialRetainsRestrictionDespiteFailedOutcomeStorage() = runTest {
        val f = SpaceRoomRequestReplayFixture()
        f.local.base.afterRestrict = { AppResult.Failed(f.local.base.storageFailure) }
        f.local.onUpdate = { expected, outcome, attempts ->
            if (outcome == CollaborationSpaceConversationOutcome.Unconfirmed(f.local.base.denied))
                AppResult.Failed(f.local.base.storageFailure)
            else f.local.commitUpdate(expected, outcome, attempts)
        }
        f.onRequest = {
            currentCoroutineContext().cancel()
            AppResult.Failed(f.local.base.denied)
        }
        val work = async { f.replay()(f.conversation, f.command.id) }
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
        f.onRequest = { AppResult.Failed(f.local.base.unavailable) }
        assertEquals(
            AppResult.Failed(f.local.base.denied),
            f.replay()(f.conversation, f.command.id),
        )
        assertEquals(0, f.local.base.admits)
    }

    @Test
    fun admissionUsesWallMonotonicAndOriginalHardDeadlines() = runTest {
        for (variant in 0..3) {
            val f = SpaceRoomRequestReplayFixture()
            f.local.base.beforeAdmission = {
                when (variant) {
                    0 -> f.native.setup.now -= 1.seconds
                    1 -> f.native.setup.now += 3600.seconds
                    2 -> f.native.time += 15.seconds
                }
            }
            if (variant == 3)
                f.local.onUpdate = { expected, outcome, attempts ->
                    f.local.commitUpdate(expected, outcome, attempts).also {
                        f.native.time += 15.seconds
                    }
                }
            assertIs<AppResult.Failed>(f.replay()(f.conversation, f.command.id))
            assertTrue(f.requests.isEmpty())
        }
    }

    @Test
    fun expiryAfterAnObservedReceiptDoesNotEraseTheOwnedFact() = runTest {
        val f = SpaceRoomRequestReplayFixture()
        f.onRequest = {
            f.native.setup.now += 3600.seconds
            AppResult.Success(f.receipt)
        }
        assertEquals(AppResult.Success(f.recorded()), f.replay()(f.conversation, f.command.id))
        assertEquals(f.recorded(), f.local.active)
    }

    @Test
    fun sameFamilyRenewalCannotExtendTheAttemptInitialHardDeadline() = runTest {
        val f = SpaceRoomRequestReplayFixture()
        val session = f.native.session
        f.native.setup.session =
            session.copy(
                binding =
                    session.binding.copy(
                        expiresAtEpochMillis =
                            (f.native.setup.now + 3.seconds).toEpochMilliseconds()
                    )
            )
        f.local.onUpdate = { expected, outcome, attempts ->
            f.local.commitUpdate(expected, outcome, attempts).also {
                f.native.setup.session = session
                f.native.setup.now += 4.seconds
            }
        }
        assertIs<AppResult.Failed>(f.replay()(f.conversation, f.command.id))
        assertTrue(f.requests.isEmpty())
        assertIs<CollaborationSpaceConversationOutcome.Unconfirmed>(f.local.active?.outcome)
    }

    @Test
    fun blockedRequestAndOutcomeCaptureHaveFiniteLifetimes() = runTest {
        val f = SpaceRoomRequestReplayFixture()
        f.onRequest = { awaitCancellation() }
        assertIs<AppResult.Failed>(f.replay()(f.conversation, f.command.id))
        assertIs<CollaborationSpaceConversationOutcome.Unconfirmed>(f.local.active?.outcome)
        assertEquals(1, f.requests.size)
        val blocked = SpaceRoomRequestReplayFixture()
        blocked.local.onUpdate = { expected, outcome, attempts ->
            if (outcome is CollaborationSpaceConversationOutcome.Recorded) awaitCancellation()
            else blocked.local.commitUpdate(expected, outcome, attempts)
        }
        assertIs<AppResult.Failed>(blocked.replay()(blocked.conversation, blocked.command.id))
        assertIs<CollaborationSpaceConversationOutcome.Unconfirmed>(blocked.local.active?.outcome)
        assertEquals(1, blocked.requests.size)
    }
}
