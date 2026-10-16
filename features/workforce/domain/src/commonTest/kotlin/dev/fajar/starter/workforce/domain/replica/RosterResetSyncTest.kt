@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.workforce.domain.replica

import dev.fajar.starter.common.result.*
import dev.fajar.starter.sync.domain.entities.*
import dev.fajar.starter.workforce.domain.entities.*
import dev.fajar.starter.workforce.domain.policies.calendarFailure
import dev.fajar.starter.workforce.domain.replica.entities.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*

class RosterResetSyncTest {
    @Test
    fun aWithdrawnUpsertDoesNotBlockTheFollowingDeletionOrTheUnderlyingCalendar() = runTest {
        val f = WorkforceSyncFixture()
        assertIs<AppResult.Success<*>>(f.sync(f.base.workspace.id))
        val reference = workforceReference(f.roster)
        f.changesCall = { cursor ->
            if (cursor == "head")
                f.changes(
                    listOf(SyncChange(reference.copy(version = 1), SyncOperation.UPSERT)),
                    "old-upsert",
                    hasMore = true,
                )
            else
                f.changes(
                    listOf(SyncChange(reference.copy(version = 2), SyncOperation.DELETE)),
                    "reset",
                )
        }
        val reads = f.canonicalReads.size
        f.canonicalCall = { _, _ -> calendarFailure("roster_day_not_found") }
        assertIs<AppResult.Success<*>>(f.sync(f.base.workspace.id))
        assertEquals("reset", f.replicas.current?.cursor)
        assertEquals(reads + 1, f.canonicalReads.size)
        assertEquals(
            2L,
            f.replicas.pending.single { it.reference.id == f.roster.id }.reference.version,
        )
        assertNull(f.replicas.pending.single { it.reference.id == f.roster.id }.record)
        val snapshot =
            assertIs<AppResult.Success<WorkCalendarSnapshot?>>(
                    f.base.observe(f.base.workspace.id).first()
                )
                .value!!
        assertEquals(
            CalendarOrigin.PATTERN,
            (snapshot.calendar!!.days.first() as WorkDay.Work).origin,
        )
        assertEquals(CalendarOrigin.HOLIDAY, (snapshot.calendar.days[1] as WorkDay.Off).origin)
    }

    @Test
    fun resetDuringBootstrapHydrationStillPublishesTheRemainingOwnedSources() = runTest {
        val f = WorkforceSyncFixture()
        val regular = f.canonicalCall
        f.canonicalCall = { collection, id ->
            if (collection == SyncCollection.ROSTER_DAYS) calendarFailure("roster_day_not_found")
            else regular(collection, id)
        }
        assertIs<AppResult.Success<*>>(f.sync(f.base.workspace.id))
        val published =
            assertIs<AppResult.Success<WorkforceReplicaFixture.Published>>(
                    f.replicas.published.value
                )
                .value
        assertTrue(published.checkpoint!!.published)
        assertEquals(3, published.entries.size)
        assertFalse(published.entries.any { it.reference.collection == SyncCollection.ROSTER_DAYS })
        val snapshot =
            assertIs<AppResult.Success<WorkCalendarSnapshot?>>(
                    f.base.observe(f.base.workspace.id).first()
                )
                .value!!
        assertEquals(
            CalendarOrigin.PATTERN,
            (snapshot.calendar!!.days.first() as WorkDay.Work).origin,
        )
    }

    @Test
    fun aMissingOlderReferenceCannotRemoveANewerAcquiredRosterVersion() = runTest {
        val f = WorkforceSyncFixture()
        val newer = f.roster.copy(version = 3)
        f.records[SyncCollection.ROSTER_DAYS to newer.id] = newer
        assertIs<AppResult.Success<*>>(f.sync(f.base.workspace.id))
        val reference = workforceReference(f.roster)
        f.changesCall = {
            f.changes(
                listOf(SyncChange(reference.copy(version = 1), SyncOperation.UPSERT)),
                "older",
            )
        }
        f.canonicalCall = { _, _ -> calendarFailure("roster_day_not_found") }
        assertIs<AppResult.Success<*>>(f.sync(f.base.workspace.id))
        assertEquals(newer, f.replicas.pending.single { it.reference.id == newer.id }.record)
        f.changesCall = {
            f.changes(
                listOf(SyncChange(reference.copy(version = 4), SyncOperation.DELETE)),
                "reset",
            )
        }
        assertIs<AppResult.Success<*>>(f.sync(f.base.workspace.id))
        assertNull(f.replicas.pending.single { it.reference.id == newer.id }.record)
        assertEquals(
            4L,
            f.replicas.pending.single { it.reference.id == newer.id }.reference.version,
        )
    }

    @Test
    fun unrelatedFailuresDoNotBecomeRosterDeletions() = runTest {
        for ((collection, failure) in
            listOf(
                SyncCollection.ROSTER_DAYS to calendarFailure("not_found"),
                SyncCollection.ROSTER_DAYS to calendarFailure("offline", FailureKind.Network),
                SyncCollection.WORK_SCHEDULES to calendarFailure("roster_day_not_found"),
            )) {
            val f = WorkforceSyncFixture()
            assertIs<AppResult.Success<*>>(f.sync(f.base.workspace.id))
            val before = f.replicas.published.value
            val checkpoint = f.replicas.current
            val record = if (collection == SyncCollection.ROSTER_DAYS) f.roster else f.schedule
            f.changesCall = {
                f.changes(
                    listOf(SyncChange(workforceReference(record), SyncOperation.UPSERT)),
                    "failed",
                )
            }
            f.canonicalCall = { _, _ -> failure }
            assertSame(failure, f.sync(f.base.workspace.id))
            assertEquals(before, f.replicas.published.value)
            assertEquals(checkpoint, f.replicas.current)
        }
    }

    @Test
    fun cancellationBeforeAWithdrawnResponsePreservesFactsAndReleasesAcquisition() = runTest {
        val f = WorkforceSyncFixture()
        assertIs<AppResult.Success<*>>(f.sync(f.base.workspace.id))
        val before = f.replicas.published.value
        val checkpoint = f.replicas.current
        f.changesCall = {
            f.changes(
                listOf(SyncChange(workforceReference(f.roster), SyncOperation.UPSERT)),
                "reset",
            )
        }
        val release = CompletableDeferred<Unit>()
        f.canonicalCall = { _, _ ->
            release.await()
            calendarFailure("roster_day_not_found")
        }
        val pending = async { f.sync(f.base.workspace.id) }
        runCurrent()
        pending.cancelAndJoin()
        release.complete(Unit)
        assertEquals(before, f.replicas.published.value)
        assertEquals(checkpoint, f.replicas.current)
        assertEquals(0, f.activeReads)
        assertFalse(f.execution.isLocked)
        assertIs<AppResult.Success<*>>(f.sync(f.base.workspace.id))
        assertNull(f.replicas.pending.single { it.reference.id == f.roster.id }.record)
    }
}
