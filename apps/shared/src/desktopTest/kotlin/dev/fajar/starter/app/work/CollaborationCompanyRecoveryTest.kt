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
    fun eachLaneKeepsTheExactOriginalAdmissionEvenIfTheOtherHasRemainingWork() = runTest {
        val grant = admission()
        val seen = mutableListOf<CollaborationBackgroundAdmission>()
        val worker =
            CollaborationCompanyRecovery(
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
                CollaborationCompanyRecovery(
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
            CollaborationCompanyRecovery(
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
        val worker = CollaborationCompanyRecovery({ SyncResult.Complete }, { awaitCancellation() })
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
            CollaborationCompanyRecovery(
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
        val worker =
            CollaborationCompanyRecovery({ error("No incoming") }, { error("No outgoing") })
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
