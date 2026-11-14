@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class CollaborationSpaceRecipientTransferWorkerTest {
    @Test
    fun absentAndExplicitEmptySourcesNeverScanOrWakeOnTimers() = runTest {
        for (explicit in listOf(false, true)) {
            val f = SpaceRecipientTransferWorkerFixture()
            if (explicit)
                f.sourceFacts.value =
                    AppResult.Success(CollaborationSpaceRecipientOutboxVersion(f.id(9000), null))
            val job = backgroundScope.launch { f.worker()(f.access).collect() }
            runCurrent()
            f.catalogChanged()
            f.destinationChanged()
            runCurrent()
            advanceTimeBy(86_400_000)
            runCurrent()
            assertTrue(f.pages.isEmpty())
            assertTrue(f.calls.isEmpty())
            job.cancelAndJoin()
            assertEquals(1, f.sourceStops)
            assertEquals(1, f.catalogStops)
            assertEquals(1, f.destinationStops)
        }
    }

    @Test
    fun successfulOwnWritesSettleIntoOneEmptyPassThenStayIdle() = runTest {
        val f = SpaceRecipientTransferWorkerFixture()
        val first = f.add(1)
        val second = f.add(2)
        val job = backgroundScope.launch { f.worker()(f.access).collect() }
        runCurrent()
        assertEquals(listOf(first.id, second.id), f.calls)
        assertEquals<List<String?>>(listOf(null, null), f.pages)
        assertTrue(f.rows.isEmpty())
        f.catalogChanged()
        f.destinationChanged()
        runCurrent()
        advanceTimeBy(86_400_000)
        runCurrent()
        assertEquals(2, f.pages.size)
        assertEquals(2, f.calls.size)
        job.cancelAndJoin()
    }

    @Test
    fun aBlockedFirstRecipientCannotStarveReadyMessagesAcrossPages() = runTest {
        val f = SpaceRecipientTransferWorkerFixture()
        val originals = (0..21).map(f::add)
        val blocked = originals.first()
        f.onTransfer = {
            if (it.id == blocked.id)
                AppResult.Success(CollaborationSpaceRecipientTransfer.WaitingForConversation(null))
            else f.complete(it)
        }
        val job = backgroundScope.launch { f.worker()(f.access).collect() }
        runCurrent()
        assertEquals(originals.map { it.id }, f.calls)
        assertEquals(listOf(blocked.id), f.rows.keys.toList())
        val pages = f.pages.toList()
        f.destinationChanged()
        runCurrent()
        assertEquals(pages, f.pages)
        assertEquals(1, f.calls.count { it == blocked.id })
        f.catalogChanged()
        runCurrent()
        assertEquals(2, f.calls.count { it == blocked.id })
        val count = f.calls.size
        advanceTimeBy(86_400_000)
        runCurrent()
        assertEquals(count, f.calls.size)
        job.cancelAndJoin()
    }

    @Test
    fun aWaitingIndexNeedsANewIndexVersionAndDoesNotReactToOtherOriginals() = runTest {
        val f = SpaceRecipientTransferWorkerFixture()
        f.add(1)
        f.onTransfer = {
            AppResult.Success(CollaborationSpaceRecipientTransfer.WaitingForInbox(f.id(90)))
        }
        val job = backgroundScope.launch { f.worker()(f.access).collect() }
        runCurrent()
        f.catalogChanged()
        runCurrent()
        assertEquals(1, f.pages.size)
        f.sourceChanged()
        runCurrent()
        assertEquals(1, f.calls.size)
        f.onTransfer = { f.complete(it) }
        f.destinationChanged()
        runCurrent()
        assertEquals(2, f.calls.size)
        assertTrue(f.rows.isEmpty())
        job.cancelAndJoin()
    }

    @Test
    fun aFailureIsAttemptedOncePerChangedFrameWithoutAClockLoop() = runTest {
        val f = SpaceRecipientTransferWorkerFixture()
        f.add(1)
        f.onTransfer = { AppResult.Failed(f.failure) }
        val values = mutableListOf<AppResult<Unit>>()
        val job = backgroundScope.launch { f.worker()(f.access).toList(values) }
        runCurrent()
        assertEquals(AppResult.Failed(f.failure), values.last())
        advanceTimeBy(86_400_000)
        runCurrent()
        assertEquals(1, f.calls.size)
        f.destinationChanged()
        runCurrent()
        assertEquals(2, f.calls.size)
        advanceTimeBy(86_400_000)
        runCurrent()
        assertEquals(2, f.calls.size)
        job.cancelAndJoin()
    }

    @Test
    fun inputChangesDoNotCancelAnOwnedTransferButOwnerCancellationDoes() = runTest {
        val f = SpaceRecipientTransferWorkerFixture()
        val original = f.add(1)
        val entered = CompletableDeferred<Unit>()
        var stopped = 0
        f.onTransfer = {
            entered.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                stopped++
            }
        }
        val job = backgroundScope.launch { f.worker()(f.access).collect() }
        entered.await()
        f.sourceChanged()
        f.catalogChanged()
        f.destinationChanged()
        runCurrent()
        assertEquals(0, stopped)
        assertEquals(listOf(original.id), f.calls)
        job.cancelAndJoin()
        assertEquals(1, stopped)
        assertEquals(1, f.sourceStops)
        assertEquals(1, f.catalogStops)
        assertEquals(1, f.destinationStops)
        assertEquals(original, f.rows[original.id])
    }

    @Test
    fun newEarlierOriginalsArrivingDuringAPassAreServedByTheNextPass() = runTest {
        val f = SpaceRecipientTransferWorkerFixture()
        val first = f.add(20)
        val second = f.add(30)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.onTransfer = {
            if (it.id == first.id) {
                entered.complete(Unit)
                release.await()
            }
            f.complete(it)
        }
        val job = backgroundScope.launch { f.worker()(f.access).collect() }
        entered.await()
        val later = f.add(10)
        runCurrent()
        assertEquals(listOf(first.id), f.calls)
        release.complete(Unit)
        runCurrent()
        assertEquals(listOf(first.id, second.id, later.id), f.calls)
        assertTrue(f.rows.isEmpty())
        assertTrue(f.pages.size <= 4)
        job.cancelAndJoin()
    }

    @Test
    fun failedSourceObservationWaitsForAChangedAdmittedFact() = runTest {
        val f = SpaceRecipientTransferWorkerFixture()
        f.add(1)
        val retained = f.sourceFacts.value
        f.sourceFacts.value = AppResult.Failed(f.failure)
        val values = mutableListOf<AppResult<Unit>>()
        val job = backgroundScope.launch { f.worker()(f.access).toList(values) }
        runCurrent()
        assertEquals(AppResult.Failed(f.failure), values.last())
        assertTrue(f.pages.isEmpty())
        advanceTimeBy(86_400_000)
        runCurrent()
        assertTrue(f.pages.isEmpty())
        f.sourceFacts.value = retained
        runCurrent()
        assertEquals(1, f.calls.size)
        job.cancelAndJoin()
    }

    @Test
    fun invalidFrameStopsBeforeReadingTheOriginals() = runTest {
        val f = SpaceRecipientTransferWorkerFixture()
        f.add(1)
        f.sourceFacts.value =
            AppResult.Success(CollaborationSpaceRecipientOutboxVersion(f.id(9000), -1))
        val values = mutableListOf<AppResult<Unit>>()
        val job = backgroundScope.launch { f.worker()(f.access).toList(values) }
        runCurrent()
        assertEquals(FailureKind.Unexpected, assertIs<AppResult.Failed>(values.last()).failure.kind)
        assertTrue(f.pages.isEmpty())
        assertTrue(f.calls.isEmpty())
        job.cancelAndJoin()
    }

    @Test
    fun malformedProgressStopsInsteadOfRereadingTheSamePage() = runTest {
        val f = SpaceRecipientTransferWorkerFixture()
        val row = f.add(1)
        f.onPage = { _, _ ->
            AppResult.Success(CollaborationSpaceRecipientSubmissionPage(listOf(row), row.id))
        }
        val values = mutableListOf<AppResult<Unit>>()
        val job = backgroundScope.launch { f.worker()(f.access).toList(values) }
        runCurrent()
        assertEquals(FailureKind.Unexpected, assertIs<AppResult.Failed>(values.last()).failure.kind)
        assertEquals(1, f.pages.size)
        assertTrue(f.calls.isEmpty())
        advanceTimeBy(86_400_000)
        runCurrent()
        assertEquals(1, f.pages.size)
        job.cancelAndJoin()
    }

    @Test
    fun anImpossibleOverQuotaSourceStopsAfterSixteenPages() = runTest {
        val f = SpaceRecipientTransferWorkerFixture()
        (0..128).forEach(f::add)
        f.onTransfer = {
            AppResult.Success(CollaborationSpaceRecipientTransfer.WaitingForConversation(null))
        }
        val values = mutableListOf<AppResult<Unit>>()
        val job = backgroundScope.launch { f.worker()(f.access).toList(values) }
        runCurrent()
        assertEquals(16, f.pages.size)
        assertEquals(128, f.calls.size)
        assertEquals(FailureKind.Unexpected, assertIs<AppResult.Failed>(values.last()).failure.kind)
        advanceTimeBy(86_400_000)
        runCurrent()
        assertEquals(16, f.pages.size)
        job.cancelAndJoin()
    }

    @Test
    fun aMissingInputDoesNotSpinOrStartTransfer() = runTest {
        val f = SpaceRecipientTransferWorkerFixture()
        f.add(1)
        val waiting = CompletableDeferred<Unit>()
        f.beforeSource = { waiting.await() }
        val job = backgroundScope.launch { f.worker()(f.access).collect() }
        runCurrent()
        advanceTimeBy(86_400_000)
        runCurrent()
        assertTrue(f.pages.isEmpty())
        waiting.complete(Unit)
        runCurrent()
        assertEquals(1, f.calls.size)
        job.cancelAndJoin()
    }
}
