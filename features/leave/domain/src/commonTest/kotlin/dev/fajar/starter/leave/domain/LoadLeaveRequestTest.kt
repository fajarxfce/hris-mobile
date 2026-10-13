@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.leave.domain

import dev.fajar.starter.common.result.*
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.leave.domain.usecases.LoadLeaveRequest
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest

class LoadLeaveRequestTest {
    @Test
    fun acquiredHistoryRemainsReadableOfflineWithoutWaitingForTheSyncLock() = runTest {
        var now = Instant.parse("2026-10-12T01:00:00Z")
        val f = LeaveRequestFixture { now }
        val selected = f.selection.copy(historyAfter = 1)
        assertIs<AppResult.Success<Unit>>(f.load(selected))
        val saved = f.records.value
        val reads = f.reads
        val lock = Mutex(locked = true)
        val load =
            LoadLeaveRequest(
                f.remote,
                f.cache,
                f.base.sessions,
                f.base.workspaces,
                f.base.clock,
                f.base.build,
                lock,
            )
        f.requestCall = { error("A locally acquired page cannot require HTTP") }
        now += 3.days
        try {
            assertIs<AppResult.Success<Unit>>(
                load(f.base.workspace.id, selected, LeaveRequestAcquisition.LOCAL_FIRST)
            )
            assertEquals(0, testScheduler.currentTime)
            assertEquals(reads, f.reads)
            assertEquals(saved, f.records.value)
        } finally {
            lock.unlock()
        }
    }

    @Test
    fun inconsistentLatestAndOlderResponsesCannotPublishAMixedRequest() = runTest {
        val f = LeaveRequestFixture()
        f.load()
        val saved = f.records.value
        f.requestCall = { selected ->
            AppResult.Success(f.details.copy(version = if (selected.historyAfter == null) 1 else 2))
        }
        assertEquals(
            "client.leave_changed",
            assertIs<AppResult.Failed>(f.load(f.selection.copy(historyAfter = 1))).failure.code,
        )
        assertEquals(saved, f.records.value)
        assertEquals(1, f.writes)
    }

    @Test
    fun deadlineIncludesWaitingForSharedAcquisitionOwnership() = runTest {
        val f = LeaveRequestFixture()
        val lock = Mutex(locked = true)
        val load =
            LoadLeaveRequest(
                f.remote,
                f.cache,
                f.base.sessions,
                f.base.workspaces,
                f.base.clock,
                f.base.build,
                lock,
            )
        try {
            assertEquals(
                "client.leave_timeout",
                assertIs<AppResult.Failed>(load(f.base.workspace.id, f.selection)).failure.code,
            )
            assertEquals(30_000, testScheduler.currentTime)
            assertEquals(0, f.reads)
            assertEquals(0, f.base.policyReads)
        } finally {
            lock.unlock()
        }
        assertIs<AppResult.Success<Unit>>(load(f.base.workspace.id, f.selection))
        assertEquals(1, f.writes)
    }
}
