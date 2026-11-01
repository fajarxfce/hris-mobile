package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class ReadCollaborationHistoryReferencesTest {
    @Test
    fun expiredCredentialsReadBoundedPendingMetadataWithoutNetworkOrAcknowledgement() = runTest {
        val f = HistoryReferenceReconciliationFixture()
        f.local.setup.now += 365.days
        val first =
            assertIs<AppResult.Success<CollaborationHistoryReferences>>(f.read(limit = 2)).value
        assertEquals(f.progress, first.progress)
        assertEquals(f.references.take(2), first.events)
        assertEquals("after-1", first.after)
        val last =
            assertIs<AppResult.Success<CollaborationHistoryReferences>>(f.read(first.after, 2))
                .value
        assertEquals(f.references.takeLast(1), last.events)
        assertNull(last.after)
        assertEquals(0, f.factReads)
        assertEquals(0, f.writes)
        assertEquals(0, f.local.setup.calls)
        assertEquals(0, f.activeReads)
    }

    @Test
    fun absentAndExhaustedPartitionsPreserveDifferentProgress() = runTest {
        val f = HistoryReferenceReconciliationFixture()
        for (progress in listOf(null, f.progress.copy(next = null, references = 0))) {
            val page = CollaborationHistoryReferences(progress, emptyList(), null)
            f.pageOverride = page
            assertEquals(AppResult.Success(page), f.read())
        }
    }

    @Test
    fun resolvedEntriesAreNotReturnedButProviderProgressIsRetained() = runTest {
        val f = HistoryReferenceReconciliationFixture()
        assertIs<AppResult.Success<*>>(f.reconcile())
        val page = assertIs<AppResult.Success<CollaborationHistoryReferences>>(f.read()).value
        assertEquals(f.references.takeLast(1), page.events)
        assertEquals(f.progress, page.progress)
        assertEquals(1, f.writes)
        assertEquals(0, f.activeReads)
    }

    @Test
    fun invalidInputsAndKnownDenialCannotOpenTheStore() = runTest {
        val f = HistoryReferenceReconciliationFixture()
        for (limit in listOf(0, 21)) assertIs<AppResult.Failed>(f.read(limit = limit))
        for (cursor in listOf("", "bad cursor", "a".repeat(201))) assertIs<AppResult.Failed>(
            f.read(after = cursor)
        )
        assertIs<AppResult.Failed>(f.reader("selection", f.key.copy(roomId = "invalid")))
        f.local.gate.value = CollaborationListAdmission("denied", f.local.denied)
        assertEquals(AppResult.Failed(f.local.denied), f.read())
        f.local.gateFailure = f.local.storage
        assertEquals(AppResult.Failed(f.local.storage), f.read())
        assertEquals(0, f.reads)
    }

    @Test
    fun malformedOrNonAdvancingPagesCannotEscapeTheBoundary() = runTest {
        val f = HistoryReferenceReconciliationFixture()
        val valid = CollaborationHistoryReferences(f.progress, f.references, null)
        val first = f.references.first()
        for (page in
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
                valid.copy(after = "short-page-cursor"),
            )) {
            f.pageOverride = page
            assertIs<AppResult.Failed>(f.read())
        }
        f.pageOverride = valid.copy(after = "after-0")
        assertIs<AppResult.Failed>(f.read(after = "after-0", limit = 3))
        assertEquals(0, f.factReads)
        assertEquals(0, f.writes)
    }

    @Test
    fun lateDenialWinsEvenWhenStorageAndSessionAlsoFail() = runTest {
        val f = HistoryReferenceReconciliationFixture()
        f.readFailure = f.local.storage
        f.afterRead = {
            f.local.gate.value = CollaborationListAdmission("denied", f.local.denied)
            f.local.setup.sessionFailure = f.local.storage
        }
        assertEquals(AppResult.Failed(f.local.denied), f.read())
        assertEquals(3, f.references.size)
        assertEquals(0, f.activeReads)
    }

    @Test
    fun accountLossDuringTheReadCannotExposeTheOldMetadata() = runTest {
        val f = HistoryReferenceReconciliationFixture()
        f.afterRead = { f.local.setup.session = null }
        assertIs<AppResult.Failed>(f.read())
        assertEquals(0, f.activeReads)
    }

    @Test
    fun storageFailureIsPreservedWithoutAnyWriteOrNetworkRetry() = runTest {
        val f = HistoryReferenceReconciliationFixture()
        f.readFailure = f.local.storage
        assertEquals(AppResult.Failed(f.local.storage), f.read())
        assertEquals(1, f.reads)
        assertEquals(0, f.writes)
        assertEquals(0, f.local.setup.calls)
    }

    @Test
    fun snapshotIsDetachedBeforeTheFinalGateSuspends() = runTest {
        val f = HistoryReferenceReconciliationFixture()
        val source = f.references.toMutableList()
        f.pageOverride = CollaborationHistoryReferences(f.progress, source, null)
        f.local.beforeAdmission = { if (f.reads > 0) source.clear() }
        val page = assertIs<AppResult.Success<CollaborationHistoryReferences>>(f.read()).value
        assertEquals(f.references, page.events)
        assertTrue(source.isEmpty())
    }

    @Test
    fun cancellationClosesTheSubscriptionWithoutReturningSuccess() = runTest {
        val f = HistoryReferenceReconciliationFixture()
        val entered = CompletableDeferred<Unit>()
        f.afterRead = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val pending = launch {
            f.read()
            error("Cancelled read cannot complete")
        }
        entered.await()
        pending.cancelAndJoin()
        assertEquals(0, f.activeReads)
        f.afterRead = {}
        assertIs<AppResult.Success<*>>(f.read())
        assertEquals(0, f.writes)
    }
}
