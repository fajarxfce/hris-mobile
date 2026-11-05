package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class ReconcileCollaborationReplacementReferencesTest {
    @Test
    fun retainedReplacementResolvesOfflineWithoutBecomingAnOriginalOrReopeningExhaustion() =
        runTest {
            val f = HistoryReferenceReconciliationFixture()
            val event = f.references.last().event
            f.replacementFacts[event.eventId] = fact(event)
            f.progress = f.progress.copy(next = null)
            f.local.setup.now += 365.days
            assertEquals(
                AppResult.Success(CollaborationHistoryReconciliation(3, 0, null)),
                f.reconcile(),
            )
            assertTrue(f.references.all { it.resolved })
            assertEquals(1, f.replacementReads)
            assertEquals(
                AppResult.Success(CollaborationHistoryReconciliation(0, 0, null)),
                f.reconcile(),
            )
            assertEquals(1, f.writes)
            assertEquals(1, f.replacementReads)
            assertEquals(
                AppResult.Success(null),
                f.eventReader(CollaborationLocalScope.Selected("selection"), f.key, event.eventId),
            )
            assertEquals(0, f.local.setup.calls)
        }

    @Test
    fun redactedReplacementStillRequiresExactSenderEventAndTimestamp() = runTest {
        for (kind in 0..3) {
            val f = HistoryReferenceReconciliationFixture()
            val event = f.references.last().event
            val valid = fact(event).copy(redacted = true)
            f.replacementFacts[event.eventId] =
                when (kind) {
                    0 -> valid
                    1 -> valid.copy(eventId = "\$other")
                    2 -> valid.copy(sender = "@other:test")
                    else -> valid.copy(timestamp = event.timestamp - 1)
                }
            if (kind == 0) assertIs<AppResult.Success<*>>(f.reconcile())
            else {
                assertIs<AppResult.Failed>(f.reconcile())
                assertEquals(0, f.writes)
                assertTrue(f.references.none { it.resolved })
            }
        }
    }

    @Test
    fun ownerReplacementOrDenialDuringFactReadCannotResolveEarlierOriginalMatches() = runTest {
        for (denied in listOf(false, true)) {
            val f = HistoryReferenceReconciliationFixture()
            val event = f.references.last().event
            f.replacementFacts[event.eventId] = fact(event)
            f.afterReplacement = {
                if (denied)
                    f.local.gate.value = CollaborationListAdmission("denied", f.local.denied)
                else f.local.setup.session = null
            }
            assertIs<AppResult.Failed>(f.reconcile())
            assertEquals(0, f.writes)
        }
    }

    @Test
    fun cancellationDrainsReplacementReadWithoutWritesOrRetry() = runTest {
        val f = HistoryReferenceReconciliationFixture()
        val entered = CompletableDeferred<Unit>()
        var active = false
        f.afterReplacement = {
            active = true
            try {
                entered.complete(Unit)
                awaitCancellation()
            } finally {
                active = false
            }
        }
        val job = launch { f.reconcile() }
        entered.await()
        job.cancelAndJoin()
        assertFalse(active)
        assertEquals(1, f.replacementReads)
        assertEquals(0, f.writes)
    }

    @Test
    fun failedReplacementReadLeavesTheEntirePagePendingForCallerRecovery() = runTest {
        val f = HistoryReferenceReconciliationFixture()
        val failure = Failure(FailureKind.Storage, "Unavailable", code = "fixture.storage")
        f.replacementFailure = failure
        assertEquals(AppResult.Failed(failure), f.reconcile())
        assertEquals(0, f.writes)
        assertTrue(f.references.none { it.resolved })
        assertEquals(1, f.replacementReads)
    }

    private fun fact(event: CollaborationProviderHistoryEvent) =
        CollaborationReplacementEventFact(event.eventId, event.sender, event.timestamp, false)
}
