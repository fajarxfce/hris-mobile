package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.CancelCollaborationText
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class CancelCollaborationTextTest {
    @Test
    fun ownedOfflineCancellationRetainsTheExactOriginalAcrossLostReplyAndClockChanges() = runTest {
        val f = CancellationFixture()
        f.prepare()
        f.base.setup.now += 365.days
        f.replyFailure = f.base.storage
        assertEquals(AppResult.Failed(f.base.storage), f.cancel())
        assertEquals(CollaborationMessageState.Cancelled, f.base.records.value.single().state)
        f.replyFailure = null
        f.base.setup.now -= 730.days
        assertEquals(AppResult.Success(Unit), f.cancel())
        assertEquals(1, f.commits)
        assertEquals(0, f.base.setup.calls)
        assertEquals(f.expected.intent, f.base.records.value.single().intent)
    }

    @Test
    fun cancelledOriginalReplayNeverSchedulesOrCreatesAnotherSend() = runTest {
        val f = CancellationFixture()
        f.prepare()
        assertEquals(AppResult.Success(Unit), f.cancel())
        f.base.scheduledKeys.clear()
        val replay = assertIs<AppResult.Success<CollaborationMessageRecord>>(f.base.queue()).value
        assertEquals(CollaborationMessageState.Cancelled, replay.state)
        assertEquals(f.expected.intent, replay.intent)
        assertEquals(1, f.base.insertions)
        assertTrue(f.base.scheduledKeys.isEmpty())
        assertIs<AppResult.Failed>(f.base.queue(text = "New text with old ID"))
        assertTrue(f.base.scheduledKeys.isEmpty())
    }

    @Test
    fun invalidOrStartedExpectedStateNeverReachesCancellationStorage() = runTest {
        val f = CancellationFixture()
        f.prepare()
        val device =
            CollaborationMessageDevice(cancelId(7), cancelId(8), cancelId(9), "!room:example.test")
        val states =
            listOf(
                CollaborationMessageState.Cancelled,
                CollaborationMessageState.Staging(device, emptySet()),
                CollaborationMessageState.Staged(device, "transaction"),
                CollaborationMessageState.Sent(device, "transaction", "$" + "event"),
            )
        for (state in states) {
            assertFalse(collaborationMessageTransition(state, CollaborationMessageState.Cancelled))
            assertFalse(collaborationMessageTransition(CollaborationMessageState.Cancelled, state))
            assertIs<AppResult.Failed>(f.cancel(expected = f.expected.copy(state = state)))
        }
        assertFalse(
            collaborationMessageTransition(
                CollaborationMessageState.Queued,
                CollaborationMessageState.Cancelled,
            )
        )
        for (expected in
            listOf(
                f.expected.copy(revision = 1),
                f.expected.copy(intent = f.expected.intent.copy(id = "invalid")),
                f.expected.copy(intent = f.expected.intent.copy(text = "")),
            )) assertIs<AppResult.Failed>(f.cancel(expected = expected))
        assertEquals(0, f.calls)
    }

    @Test
    fun staleFullRecordCannotReportCancellationWhenDeliveryHasStarted() = runTest {
        val f = CancellationFixture()
        f.prepare()
        f.reject = true
        assertEquals(
            "client.collaboration_cancellation_unavailable",
            assertIs<AppResult.Failed>(f.cancel()).failure.code,
        )
        assertEquals(0, f.commits)
        assertEquals(f.expected, f.base.records.value.single())
    }

    @Test
    fun callerOwnershipCannotBeReinterpretedAsAnotherParticipantOrSession() = runTest {
        val f = CancellationFixture()
        f.prepare()
        for (access in
            listOf(
                f.access.copy(accountId = cancelId(4)),
                f.access.copy(sessionId = "replacement"),
                f.access.copy(companyId = cancelId(99)),
                f.access.copy(audience = "https://other.example.test/"),
            )) assertEquals(
            "client.session_changed",
            assertIs<AppResult.Failed>(f.cancel(access)).failure.code,
        )
        assertIs<AppResult.Failed>(
            f.cancel(
                expected =
                    f.expected.copy(
                        intent =
                            f.expected.intent.copy(
                                owner = f.expected.intent.owner.copy(accountId = cancelId(4))
                            )
                    )
            )
        )
        assertEquals(0, f.calls)
    }

    @Test
    fun knownDenialWinsDuringPendingAndFailedAdmissionChecks() = runTest {
        val f = CancellationFixture()
        f.prepare()
        f.base.beforeAdmission = {
            f.base.gate.value = CollaborationListAdmission("denial", f.base.denied)
            f.base.setup.sessionFailure = f.base.storage
        }
        assertEquals(AppResult.Failed(f.base.denied), f.cancel())
        assertEquals(0, f.calls)
    }

    @Test
    fun lateDenialOrOwnerLossCannotPublishACommittedCancellation() = runTest {
        for (denied in listOf(false, true)) {
            val f = CancellationFixture()
            f.prepare()
            f.afterCommit = {
                f.base.setup.session = null
                if (denied) f.base.gate.value = CollaborationListAdmission("denial", f.base.denied)
            }
            val result = assertIs<AppResult.Failed>(f.cancel())
            if (denied) assertEquals(f.base.denied, result.failure)
            else assertEquals("client.session_changed", result.failure.code)
            assertEquals(1, f.commits)
        }
    }

    @Test
    fun failedLocalAdmissionCannotBeTreatedAsPermissionToCancel() = runTest {
        val f = CancellationFixture()
        f.prepare()
        f.base.gateFailure = f.base.storage
        assertEquals(AppResult.Failed(f.base.storage), f.cancel())
        assertEquals(0, f.calls)
    }

    @Test
    fun disposedWaiterDoesNotPublishOrUndoTheCommittedDecision() = runTest {
        val f = CancellationFixture()
        f.prepare()
        val committed = CompletableDeferred<Unit>()
        f.afterCommit = {
            committed.complete(Unit)
            awaitCancellation()
        }
        val request = launch {
            f.cancel()
            fail("Cancelled caller must not publish")
        }
        committed.await()
        request.cancelAndJoin()
        assertEquals(1, f.commits)
        f.afterCommit = {}
        assertEquals(AppResult.Success(Unit), f.cancel())
        assertEquals(1, f.commits)
    }
}

private class CancellationFixture {
    val base = MessageIntentFixture()
    lateinit var expected: CollaborationMessageRecord
    lateinit var access: CollaborationLocalAccess
    var calls = 0
    var commits = 0
    var reject = false
    var replyFailure: Failure? = null
    var afterCommit: suspend () -> Unit = {}
    private val repository =
        object : CollaborationMessageCancellationRepository {
            override suspend fun cancel(
                access: CollaborationLocalAccess,
                expected: CollaborationMessageRecord,
            ): AppResult<Boolean> {
                calls++
                assertEquals(this@CancellationFixture.access, access)
                val current = base.records.value.single()
                if (reject) return AppResult.Success(false)
                if (current == expected) {
                    base.records.value =
                        listOf(expected.copy(state = CollaborationMessageState.Cancelled))
                    commits++
                    afterCommit()
                } else if (current != expected.copy(state = CollaborationMessageState.Cancelled))
                    return AppResult.Success(false)
                return replyFailure?.let { AppResult.Failed(it) } ?: AppResult.Success(true)
            }
        }
    private val cancellation =
        CancelCollaborationText(
            repository,
            base.lists,
            base.setup.sessions,
            base.setup.workspaces,
            base.server,
            base.build,
            base.clock,
        )

    suspend fun prepare() {
        expected = assertIs<AppResult.Success<CollaborationMessageRecord>>(base.queue()).value
        access =
            CollaborationLocalAccess(
                base.setup.session!!.id,
                base.server.audience,
                expected.intent.owner.accountId,
                expected.intent.owner.companyId,
            )
    }

    suspend fun cancel(
        access: CollaborationLocalAccess = this.access,
        expected: CollaborationMessageRecord = this.expected,
    ) = cancellation("selection", access, expected)
}

private fun cancelId(value: Int) = "00000000-0000-0000-0000-" + value.toString().padStart(12, '0')
