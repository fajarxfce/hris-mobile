@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.app.work

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.sync.domain.SyncResult
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class CollaborationCompanyRecoveryTest {
    @Test
    fun deviceSyncAndIncomingWorkUseTheSameAdmissionBeforeOutgoingDelivery() = runTest {
        val grant = admission()
        val calls = mutableListOf<String>()
        val worker =
            CollaborationCompanyRecovery(
                {
                    assertSame(grant, it)
                    calls += "device"
                    SyncResult.Complete
                },
                {
                    assertSame(grant, it)
                    calls += "incoming"
                    SyncResult.Complete
                },
                {
                    assertSame(grant, it)
                    calls += "outgoing"
                    SyncResult.Complete
                },
                {
                    assertSame(grant, it)
                    calls += "push"
                    SyncResult.Complete
                },
            )
        assertEquals(SyncResult.Complete, worker(grant))
        assertEquals(listOf("device", "incoming", "outgoing", "push"), calls)
    }

    @Test
    fun aTimedOutInitialSyncKeepsTheOtherTurnsAndRemainsPending() = runTest {
        val calls = mutableListOf<String>()
        val worker =
            CollaborationCompanyRecovery(
                {
                    calls += "device"
                    awaitCancellation()
                },
                {
                    calls += "incoming"
                    SyncResult.Complete
                },
                {
                    calls += "outgoing"
                    SyncResult.Complete
                },
                {
                    calls += "push"
                    SyncResult.Complete
                },
            )
        assertEquals(
            "client.collaboration_device_sync_timeout",
            assertIs<SyncResult.Retry>(worker(admission())).failure?.code,
        )
        assertEquals(listOf("device", "incoming", "outgoing", "push"), calls)
        assertEquals(15_000L, testScheduler.currentTime)
    }

    @Test
    fun slowDeviceSyncDoesNotDelayIncomingAndEveryTurnFitsTheExistingCompanyBudget() = runTest {
        val calls = mutableListOf<String>()
        val incomingComplete = CompletableDeferred<Unit>()
        val worker =
            CollaborationCompanyRecovery(
                {
                    incomingComplete.await()
                    awaitCancellation()
                },
                {
                    calls += "incoming"
                    incomingComplete.complete(Unit)
                    awaitCancellation()
                },
                {
                    calls += "outgoing"
                    awaitCancellation()
                },
                {
                    calls += "push"
                    awaitCancellation()
                },
            )
        val result = withTimeout(50_000) { worker(admission()) }
        assertIs<SyncResult.Retry>(result)
        assertEquals(listOf("incoming", "outgoing", "push"), calls)
        assertEquals(45_000L, testScheduler.currentTime)
    }

    @Test
    fun cancellationDrainsBothInitialChildrenBeforeReturningWithoutStartingOutgoing() = runTest {
        val started = mutableSetOf<String>()
        val closed = mutableSetOf<String>()
        suspend fun hold(name: String): SyncResult {
            started += name
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    delay(50)
                    closed += name
                }
            }
        }
        val worker =
            CollaborationCompanyRecovery(
                { hold("device") },
                { hold("incoming") },
                { error("No outgoing after cancellation") },
                { error("No push after cancellation") },
            )
        val running = launch { worker(admission()) }
        runCurrent()
        assertEquals(setOf("device", "incoming"), started)
        running.cancelAndJoin()
        assertEquals(started, closed)
    }

    @Test
    fun pushTimeoutCannotPreventEitherMessageLaneAndKeepsTheOriginalAdmission() = runTest {
        val grant = admission()
        val events = mutableListOf<String>()
        val worker =
            company(
                {
                    assertSame(grant, it)
                    events += "incoming"
                    SyncResult.Complete
                },
                {
                    assertSame(grant, it)
                    events += "outgoing"
                    SyncResult.Complete
                },
                {
                    assertSame(grant, it)
                    events += "push"
                    awaitCancellation()
                },
            )
        assertEquals(
            "client.collaboration_push_registration_timeout",
            assertIs<SyncResult.Retry>(worker(grant)).failure?.code,
        )
        assertEquals(listOf("incoming", "outgoing", "push"), events)
        assertEquals(15_000L, testScheduler.currentTime)
    }

    @Test
    fun failedMessageLanesStillAllowRegistrationButItsSuccessCannotEraseFailure() = runTest {
        val failure = SyncResult.Retry(Failure(FailureKind.Network, "Fictional network failure"))
        var registrations = 0
        val worker =
            company(
                { failure },
                { SyncResult.Retry() },
                {
                    registrations++
                    SyncResult.Complete
                },
            )
        assertEquals(failure, worker(admission()))
        assertEquals(1, registrations)
    }

    @Test
    fun eachLaneKeepsTheExactOriginalAdmissionEvenIfTheOtherHasRemainingWork() = runTest {
        val grant = admission()
        val seen = mutableListOf<CollaborationBackgroundAdmission>()
        val worker =
            company(
                {
                    seen += it
                    delay(10.seconds)
                    SyncResult.Retry()
                },
                {
                    seen += it
                    SyncResult.Complete
                },
            )
        assertEquals(SyncResult.Retry(), worker(grant))
        assertEquals(2, seen.size)
        seen.forEach { assertSame(grant, it) }
        assertEquals(10_000L, testScheduler.currentTime)
    }

    @Test
    fun failedOrBlockedIncomingStillGivesOutgoingAFiniteTurn() = runTest {
        val failed = Failure(FailureKind.Network, "Temporarily unavailable")
        for (result in listOf(SyncResult.Retry(failed), SyncResult.Blocked(failed))) {
            var sent = false
            val worker =
                company(
                    { result },
                    {
                        sent = true
                        SyncResult.Complete
                    },
                )
            assertEquals(result, worker(admission()))
            assertTrue(sent)
        }
    }

    @Test
    fun incomingTimeoutIsJoinedBeforeOutgoingGetsItsOwnBudget() = runTest {
        val events = mutableListOf<String>()
        val worker =
            company(
                {
                    try {
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) {
                            delay(2.seconds)
                            events += "closed"
                        }
                    }
                },
                {
                    events += "send"
                    delay(14.seconds)
                    SyncResult.Complete
                },
            )
        val result = assertIs<SyncResult.Retry>(worker(admission()))
        assertEquals("client.collaboration_incoming_timeout", result.failure?.code)
        assertEquals(listOf("closed", "send"), events)
        assertEquals(31_000L, testScheduler.currentTime)
    }

    @Test
    fun outgoingTimeoutCannotTurnSuccessfulIncomingIntoComplete() = runTest {
        val worker = company({ SyncResult.Complete }, { awaitCancellation() })
        val result = assertIs<SyncResult.Retry>(worker(admission()))
        assertEquals("client.collaboration_outgoing_timeout", result.failure?.code)
        assertEquals(15_000L, testScheduler.currentTime)
    }

    @Test
    fun externalCancellationDrainsIncomingAndPreventsOutgoing() = runTest {
        val entered = CompletableDeferred<Unit>()
        var closed = false
        var sent = false
        val worker =
            company(
                {
                    try {
                        entered.complete(Unit)
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) {
                            delay(1.seconds)
                            closed = true
                        }
                    }
                },
                {
                    sent = true
                    SyncResult.Complete
                },
            )
        val job = async { worker(admission()) }
        entered.await()
        job.cancelAndJoin()
        assertTrue(closed)
        assertFalse(sent)
        assertFailsWith<CancellationException> { job.await() }
    }

    @Test
    fun alreadyCancelledEntryInvokesNeitherLane() = runTest {
        val worker = company({ error("No incoming") }, { error("No outgoing") })
        val cancelled = Job().apply { cancel() }
        assertFailsWith<CancellationException> { withContext(cancelled) { worker(admission()) } }
    }

    @Test
    fun remainingWorkRetainsRealFailurePriorityRegardlessOfLaneOrder() {
        val failure = SyncResult.Retry(Failure(FailureKind.Network, "Pending recovery"))
        val blocked = SyncResult.Blocked(Failure(FailureKind.AccessDenied, "Restricted"))
        val healthy = SyncResult.Retry()
        for ((a, b, expected) in
            listOf(
                Triple(failure, healthy, failure),
                Triple(healthy, failure, failure),
                Triple(blocked, healthy, healthy),
                Triple(healthy, blocked, healthy),
                Triple(blocked, failure, failure),
                Triple(failure, blocked, failure),
                Triple(SyncResult.Complete, blocked, blocked),
                Triple(SyncResult.Complete, SyncResult.Complete, SyncResult.Complete),
            )) assertEquals(expected, combineCollaborationRecovery(a, b))
    }

    private fun company(
        incoming: suspend (CollaborationBackgroundAdmission) -> SyncResult,
        outgoing: suspend (CollaborationBackgroundAdmission) -> SyncResult,
        push: suspend (CollaborationBackgroundAdmission) -> SyncResult = { SyncResult.Complete },
    ) = CollaborationCompanyRecovery({ SyncResult.Complete }, incoming, outgoing, push)

    private fun admission(): CollaborationBackgroundAdmission {
        val now = Clock.System.now()
        val owner =
            CollaborationOwner(
                "local",
                "https://hris.example.test/",
                "account",
                "company",
                "native",
            )
        return CollaborationBackgroundAdmission(
            CollaborationAdmissionObservation(owner, "device", 0, 0, 0, now, now + 60.seconds),
            now,
            now + 60.seconds,
        )
    }
}
