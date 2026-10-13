@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.leave.domain

import dev.fajar.starter.common.result.*
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.leave.domain.policies.leaveFailure
import dev.fajar.starter.sync.domain.entities.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*

class SyncLeaveHistoryTest {
    @Test
    fun everyRequestHistoryResumesFromItsPersistedFrontier() = runTest {
        val f = LeaveSyncFixture()
        val requests =
            (1..8).map { index ->
                f.base.request
                    .copy(
                        id = "00000000-0000-0000-0000-${index.toString().padStart(12, '0')}",
                        version = 21,
                    )
                    .fixtureDetails()
                    .let { it.copy(history = history(it, null)) }
            }
        f.bootstrapCall = {
            AppResult.Success(
                SyncBootstrapPage(
                    f.collections,
                    requests.map {
                        SyncReference(SyncCollection.LEAVE_REQUESTS, it.id, it.version)
                    },
                    null,
                    "head",
                    f.base.clock.now(),
                )
            )
        }
        f.requestCall = { id ->
            AppResult.Success(LeaveReplicaRecord.Request(requests.single { it.id == id }))
        }
        val pages = mutableListOf<Pair<String, Long>>()
        f.historyCall = { id, after ->
            pages += id to after
            val current = requests.single { it.id == id }
            AppResult.Success(
                LeaveReplicaRecord.Request(current.copy(history = history(current, after)))
            )
        }
        assertFalse(assertIs<AppResult.Success<LeaveSyncOutcome>>(f.run()).value.caughtUp)
        assertEquals(8, pages.size)
        assertTrue(f.replica.historyWork.values.all { it.after == 2L })
        assertTrue(assertIs<AppResult.Success<LeaveSyncOutcome>>(f.run()).value.caughtUp)
        assertEquals(16, pages.size)
        assertTrue(f.replica.historyWork.isEmpty())
        val access = requireNotNull(f.replica.current).access
        for (request in requests) for (after in listOf(12L, 2L)) {
            val stored =
                assertIs<AppResult.Success<LeaveRequestSnapshot?>>(
                        f.replica
                            .observeRequest(access, LeaveRequestSelection(request.id, after))
                            .first()
                    )
                    .value!!
            assertEquals(request.copy(history = history(request, after)), stored.request)
        }
        assertTrue(assertIs<AppResult.Success<LeaveSyncOutcome>>(f.run()).value.caughtUp)
        assertEquals(16, pages.size)
    }

    @Test
    fun cachedHistoryAdvancesTheFrontierWithoutHttp() = runTest {
        val f = historyFixture()
        assertFalse(assertIs<AppResult.Success<LeaveSyncOutcome>>(f.run()).value.caughtUp)
        val reads = f.recordReads
        val work = f.replica.historyWork.values.single()
        f.replica.historyWork[work.requestId] = work.copy(after = 12)
        f.historyCall = { _, _ -> error("The acquired page must be read locally") }
        assertFalse(assertIs<AppResult.Success<LeaveSyncOutcome>>(f.run()).value.caughtUp)
        assertEquals(reads, f.recordReads)
        assertEquals(2L, f.replica.historyWork.values.single().after)
    }

    @Test
    fun invalidCursorCannotAdvanceProgressOrStartAnInternalRetryLoop() = runTest {
        val f = historyFixture()
        f.historyCall = { _, after ->
            val request = f.base.request.copy(version = 21).fixtureDetails()
            AppResult.Success(
                LeaveReplicaRecord.Request(
                    request.copy(
                        history = history(request, after).copy(nextCursor = after.toString())
                    )
                )
            )
        }
        val failed = assertIs<AppResult.Failed>(f.run())
        assertEquals("client.invalid_response", failed.failure.code)
        assertEquals(12L, f.replica.historyWork.values.single().after)
        assertTrue(f.replica.historyPages.isEmpty())
        assertEquals(3, f.recordReads)
    }

    @Test
    fun serverVersionAheadOfTheFeedKeepsTheFrontierAndWaitsForPublication() = runTest {
        val f = historyFixture()
        f.historyCall = { _, after ->
            val request = f.base.request.copy(version = 22).fixtureDetails()
            AppResult.Success(
                LeaveReplicaRecord.Request(request.copy(history = history(request, after)))
            )
        }
        val before = testScheduler.currentTime
        assertFalse(assertIs<AppResult.Success<LeaveSyncOutcome>>(f.run()).value.caughtUp)
        assertEquals(5_000, testScheduler.currentTime - before)
        assertEquals(12L, f.replica.historyWork.values.single().after)
        assertTrue(f.replica.historyPages.isEmpty())
    }

    @Test
    fun lateHistoryDenialCannotClearAReplacementWorkspace() = runTest {
        val f = historyFixture()
        val release = CompletableDeferred<Unit>()
        f.historyCall = { _, _ ->
            withContext(NonCancellable) { release.await() }
            leaveFailure("access_denied", FailureKind.AccessDenied)
        }
        val pending = async { f.run() }
        runCurrent()
        val saved = f.replica.published.value
        f.base.workspaceState.value = AppResult.Success(f.base.workspace.copy(id = "replacement"))
        release.complete(Unit)
        assertEquals(
            "client.workspace_changed",
            assertIs<AppResult.Failed>(pending.await()).failure.code,
        )
        assertEquals(saved, f.replica.published.value)
        assertEquals(0, f.replica.clears)
        assertTrue(f.replica.historyPages.isEmpty())
    }

    @Test
    fun cancellationRejectsANonCooperativeHistoryResultWithoutAdvancingItsFrontier() = runTest {
        val f = historyFixture()
        val release = CompletableDeferred<Unit>()
        f.historyCall = { _, after ->
            withContext(NonCancellable) { release.await() }
            val request = f.base.request.copy(version = 21).fixtureDetails()
            AppResult.Success(
                LeaveReplicaRecord.Request(request.copy(history = history(request, after)))
            )
        }
        val pending = async { f.run() }
        runCurrent()
        val saved = f.replica.published.value
        pending.cancel()
        release.complete(Unit)
        pending.join()
        assertTrue(pending.isCancelled)
        assertEquals(saved, f.replica.published.value)
        assertEquals(12L, f.replica.historyWork.values.single().after)
        assertTrue(f.replica.historyPages.isEmpty())
    }
}

private fun historyFixture(): LeaveSyncFixture {
    val f = LeaveSyncFixture()
    val request = f.base.request.copy(version = 21).fixtureDetails()
    f.requestCall = {
        AppResult.Success(
            LeaveReplicaRecord.Request(request.copy(history = history(request, null)))
        )
    }
    f.historyCall = { _, after ->
        AppResult.Success(
            LeaveReplicaRecord.Request(request.copy(history = history(request, after)))
        )
    }
    return f
}

private fun history(request: LeaveRequestDetails, after: Long?): LeavePage<LeaveChange> {
    val first = after?.minus(1) ?: request.version
    val versions = (first downTo maxOf(0, first - 9)).toList()
    return LeavePage(
        versions.map {
            LeaveChange(
                it,
                LeaveChangeKind.DECIDED,
                request.status,
                request.submittedAt,
                "Reviewed",
            )
        },
        versions.lastOrNull()?.takeIf { versions.size == 10 && it > 0 }?.toString(),
    )
}
