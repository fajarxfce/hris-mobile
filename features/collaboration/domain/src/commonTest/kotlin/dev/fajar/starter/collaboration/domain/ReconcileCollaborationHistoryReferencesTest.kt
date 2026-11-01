package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class ReconcileCollaborationHistoryReferencesTest {
    @Test
    fun expiredCredentialsResolveOriginalAndTombstoneOfflineWhileUnknownReferencesRemain() =
        runTest {
            val f = HistoryReferenceReconciliationFixture()
            f.local.setup.now += 365.days
            val before = f.progress
            assertEquals(
                AppResult.Success(CollaborationHistoryReconciliation(2, 1, null)),
                f.reconcile(),
            )
            assertEquals(before.copy(revision = before.revision + 1), f.progress)
            assertEquals(listOf(true, true, false), f.references.map { it.resolved })
            assertEquals(
                AppResult.Success(CollaborationHistoryReconciliation(0, 1, null)),
                f.reconcile(),
            )
            assertEquals(1, f.writes)
            assertEquals(0, f.local.setup.calls)
            assertEquals(0, f.activeReads)
        }

    @Test
    fun unresolvedEntriesDoNotPreventBoundedContinuationAfterResolvedRowsLoseTheirIndex() =
        runTest {
            val f = HistoryReferenceReconciliationFixture()
            f.facts.remove(f.references[0].event.eventId)
            assertEquals(
                AppResult.Success(CollaborationHistoryReconciliation(1, 1, "after-1")),
                f.reconcile(limit = 2),
            )
            assertEquals(
                AppResult.Success(CollaborationHistoryReconciliation(0, 1, null)),
                f.reconcile("after-1", 2),
            )
            assertEquals(3, f.factReads)
            assertEquals(1, f.writes)
        }

    @Test
    fun absentPartitionAndExhaustedEmptyHistoryNeedNoFactsOrWrites() = runTest {
        val f = HistoryReferenceReconciliationFixture()
        for (progress in listOf(null, f.progress.copy(next = null, references = 0))) {
            f.pageOverride = CollaborationHistoryReferences(progress, emptyList(), null)
            assertEquals(
                AppResult.Success(CollaborationHistoryReconciliation(0, 0, null)),
                f.reconcile(),
            )
        }
        assertEquals(0, f.factReads)
        assertEquals(0, f.writes)
    }

    @Test
    fun malformedReferenceSnapshotsCannotReadFactsOrMarkAnyEntry() = runTest {
        val f = HistoryReferenceReconciliationFixture()
        val valid = CollaborationHistoryReferences(f.progress, f.references, null)
        val first = f.references.first()
        val invalid =
            listOf(
                valid.copy(progress = null),
                valid.copy(progress = f.progress.copy(references = 2)),
                valid.copy(progress = f.progress.copy(generation = "invalid")),
                valid.copy(events = f.references.reversed()),
                valid.copy(events = listOf(first, first.copy(sequence = 1))),
                valid.copy(events = listOf(first.copy(resolved = true))),
                valid.copy(events = listOf(first.copy(sequence = -1))),
                valid.copy(events = listOf(first.copy(event = first.event.copy(timestamp = -1)))),
                valid.copy(events = List(21) { first }),
                valid.copy(after = "unexpected-short-page-cursor"),
            )
        for (page in invalid) {
            f.pageOverride = page
            assertIs<AppResult.Failed>(f.reconcile())
        }
        assertEquals(0, f.factReads)
        assertEquals(0, f.writes)
    }

    @Test
    fun mismatchedFactsRejectTheWholePageIncludingPreviouslyMatchedReferences() = runTest {
        val f = HistoryReferenceReconciliationFixture()
        val id = f.references[1].event.eventId
        val fact = f.facts.getValue(id)
        for (invalid in
            listOf(
                fact.copy(eventId = "\$other"),
                fact.copy(sender = "@other:test"),
                fact.copy(timestamp = 99),
                fact.copy(redacted = false),
            )) {
            f.facts[id] = invalid
            assertIs<AppResult.Failed>(f.reconcile())
        }
        assertTrue(f.references.none { it.resolved })
        assertEquals(0, f.writes)
    }

    @Test
    fun knownDenialFailedGateAndInvalidArgumentsDoNotOpenReferences() = runTest {
        val f = HistoryReferenceReconciliationFixture()
        assertIs<AppResult.Failed>(f.reconcile(limit = 21))
        assertIs<AppResult.Failed>(f.reconcile(after = "bad cursor"))
        assertIs<AppResult.Failed>(f.useCase("selection", f.key.copy(roomId = "invalid")))
        f.local.gate.value = CollaborationListAdmission("denied", f.local.denied)
        assertEquals(AppResult.Failed(f.local.denied), f.reconcile())
        f.local.gateFailure = f.local.storage
        assertEquals(AppResult.Failed(f.local.storage), f.reconcile())
        assertEquals(0, f.reads)
    }

    @Test
    fun lateDenialWinsOverStorageAndSessionFailureWithoutDiscardingReferences() = runTest {
        val f = HistoryReferenceReconciliationFixture()
        f.readFailure = f.local.storage
        f.afterRead = {
            f.local.gate.value = CollaborationListAdmission("denied", f.local.denied)
            f.local.setup.sessionFailure = f.local.storage
        }
        assertEquals(AppResult.Failed(f.local.denied), f.reconcile())
        assertEquals(3, f.references.size)
        assertEquals(0, f.factReads)
        assertEquals(0, f.writes)
    }

    @Test
    fun accountReplacementDuringFactGateCannotResolveOldReferences() = runTest {
        val f = HistoryReferenceReconciliationFixture()
        f.local.beforeAdmission = { if (f.factReads > 0) f.local.setup.session = null }
        assertIs<AppResult.Failed>(f.reconcile())
        assertEquals(1, f.factReads)
        assertEquals(0, f.writes)
    }

    @Test
    fun failedFactAndLostCasNeverPartiallyResolveOrRetry() = runTest {
        val f = HistoryReferenceReconciliationFixture()
        f.factFailure = f.local.storage
        assertEquals(AppResult.Failed(f.local.storage), f.reconcile())
        assertEquals(0, f.writes)
        f.factFailure = null
        f.rejectWrite = true
        assertIs<AppResult.Failed>(f.reconcile())
        assertEquals(1, f.writes)
        assertTrue(f.references.none { it.resolved })
    }

    @Test
    fun lostResponseOrLateDenialRetainsCommittedResolutionAndRetryDoesNotRepeatIt() = runTest {
        for (deny in listOf(false, true)) {
            val f = HistoryReferenceReconciliationFixture()
            f.afterWrite = {
                if (deny) f.local.gate.value = CollaborationListAdmission("denied", f.local.denied)
            }
            f.writeFailure = f.local.storage
            assertEquals(
                AppResult.Failed(if (deny) f.local.denied else f.local.storage),
                f.reconcile(),
            )
            f.local.gate.value = CollaborationListAdmission("admitted", null)
            assertEquals(
                AppResult.Success(CollaborationHistoryReconciliation(0, 1, null)),
                f.reconcile(),
            )
            assertEquals(1, f.writes)
        }
    }

    @Test
    fun cancellationBeforeAndAfterCommitReleasesTheOwnerWithoutInventingSuccess() = runTest {
        for (committed in listOf(false, true)) {
            val f = HistoryReferenceReconciliationFixture()
            val entered = CompletableDeferred<Unit>()
            val pause: suspend () -> Unit = {
                entered.complete(Unit)
                awaitCancellation()
            }
            if (committed) f.afterWrite = pause else f.afterFact = pause
            val pending = launch {
                f.reconcile()
                error("Cancelled operation cannot complete")
            }
            entered.await()
            pending.cancelAndJoin()
            assertEquals(if (committed) 2 else 0, f.references.count { it.resolved })
            f.afterWrite = {}
            f.afterFact = {}
            assertEquals(
                AppResult.Success(
                    CollaborationHistoryReconciliation(if (committed) 0 else 2, 1, null)
                ),
                f.reconcile(),
            )
        }
    }

    @Test
    fun referenceSnapshotIsDetachedBeforeLateGateMutation() = runTest {
        val f = HistoryReferenceReconciliationFixture()
        val source = f.references.toMutableList()
        f.pageOverride = CollaborationHistoryReferences(f.progress, source, null)
        f.local.beforeAdmission = { if (f.reads > 0) source.clear() }
        assertEquals(
            AppResult.Success(CollaborationHistoryReconciliation(2, 1, null)),
            f.reconcile(),
        )
        assertEquals(3, f.factReads)
        assertFalse(
            CollaborationHistoryReconciliation(2, 1, "private-position")
                .toString()
                .contains("private-position")
        )
    }
}
