package dev.fajar.starter.people.domain.replica

import dev.fajar.starter.common.result.*
import dev.fajar.starter.people.domain.replica.entities.*
import dev.fajar.starter.people.domain.replica.policies.*
import dev.fajar.starter.sync.domain.entities.*
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class DirectorySyncTest {
    @Test
    fun liveCredentialExpiryDoesNotWithdrawOwnedDirectoryRecords() = runTest {
        val f = DirectoryFixture()
        f.sync("workspace")
        f.policyCall = { directoryFailure("session_expired", FailureKind.Unauthorized) }
        assertIs<AppResult.Failed>(f.sync("workspace"))
        assertNull((f.store.accessState.value as AppResult.Success).value.denial)
        assertNotNull(
            assertIs<AppResult.Success<DirectoryView?>>(
                    f.observe("workspace", flowOf(DirectorySelection())).first()
                )
                .value
        )
    }

    @Test
    fun completeCollectionSupportsUnvisitedPagesAndLocalSearchWithoutHttp() = runTest {
        val f = DirectoryFixture()
        assertTrue(
            assertIs<AppResult.Success<DirectorySyncOutcome>>(f.sync("workspace")).value.caughtUp
        )
        assertEquals(45, f.reads.size)
        val selected = MutableStateFlow(DirectorySelection(query = "Colleague 45"))
        val value =
            assertIs<AppResult.Success<DirectoryView?>>(f.observe("workspace", selected).first())
                .value!!
        assertEquals(listOf(directoryId(45)), value.page.items.map { it.id })
        assertNull(value.page.nextCursor)
        assertEquals(1, f.bootstrapReads)
        assertEquals(1, f.changeReads)
        assertEquals(0, f.base.profileReads)
        assertEquals(0, f.base.directoryReads)
        val snapshot = (f.store.snapshot.value as AppResult.Success).value
        assertEquals(20, selectDirectory(snapshot, DirectorySelection())!!.page.items.size)
        assertEquals(
            20,
            selectDirectory(snapshot, DirectorySelection(after = "E00020"))!!.page.items.size,
        )
        assertEquals(
            5,
            selectDirectory(snapshot, DirectorySelection(after = "E00040"))!!.page.items.size,
        )
        assertEquals(
            "Engineering",
            selectDirectory(snapshot, DirectorySelection(departmentQuery = "engine"))!!
                .departments
                .items
                .single()
                .name,
        )
    }

    @Test
    fun ordinaryAgeAndCredentialExpiryNeverHideOwnedLocalFacts() = runTest {
        val f = DirectoryFixture()
        f.sync("workspace")
        f.now += 73.hours
        val expired =
            f.base.session.copy(
                binding =
                    f.base.session.binding.copy(
                        expiresAtEpochMillis = (f.now - 72.hours).toEpochMilliseconds()
                    )
            )
        f.base.sessions.state.value = AppResult.Success(expired)
        val view =
            assertIs<AppResult.Success<DirectoryView?>>(
                    f.observe("workspace", flowOf(DirectorySelection(query = "45"))).first()
                )
                .value!!
        assertEquals("Colleague 45", view.page.items.single().name)
        assertEquals(1, f.bootstrapReads)
        assertEquals(1, f.changeReads)
    }

    @Test
    fun routeEntrySkipsFreshAcquisitionWhileExplicitRefreshStillSynchronizes() = runTest {
        val f = DirectoryFixture()
        f.sync("workspace")
        f.sync("workspace", force = false)
        assertEquals(1, f.changeReads)
        f.sync("workspace")
        assertEquals(2, f.changeReads)
    }

    @Test
    fun publicationPendingNeverExposesAnIncompleteReplacement() = runTest {
        val f = DirectoryFixture()
        f.changesCall = {
            AppResult.Success(SyncChangePage(emptyList(), "head", false, true, 5, f.now))
        }
        assertFalse(
            assertIs<AppResult.Success<DirectorySyncOutcome>>(f.sync("workspace")).value.caughtUp
        )
        assertNull((f.store.snapshot.value as AppResult.Success).value.checkpoint)
        assertTrue(f.changeReads <= 10)
        f.changesCall = {
            AppResult.Success(SyncChangePage(emptyList(), "head", false, false, 5, f.now))
        }
        assertTrue(
            assertIs<AppResult.Success<DirectorySyncOutcome>>(f.sync("workspace")).value.caughtUp
        )
        assertNotNull((f.store.snapshot.value as AppResult.Success).value.checkpoint)
    }

    @Test
    fun deletedOrWithdrawnIndividualsDoNotRevokeTheDirectory() = runTest {
        val f = DirectoryFixture()
        f.sync("workspace")
        f.rows.remove(directoryId(1))
        f.changesCall = {
            AppResult.Success(
                SyncChangePage(
                    listOf(
                        SyncChange(
                            SyncReference(SyncCollection.DIRECTORY_EMPLOYEES, directoryId(1), 1),
                            SyncOperation.UPSERT,
                        ),
                        SyncChange(
                            SyncReference(SyncCollection.DIRECTORY_EMPLOYEES, directoryId(2), 1),
                            SyncOperation.DELETE,
                        ),
                    ),
                    "head-2",
                    false,
                    false,
                    5,
                    f.now,
                )
            )
        }
        f.sync("workspace")
        assertEquals(43, (f.store.snapshot.value as AppResult.Success).value.records.size)
        assertNull((f.store.accessState.value as AppResult.Success).value.denial)
        assertEquals(1, f.reads.count { it == directoryId(2) })
    }

    @Test
    fun duplicateReferencesPreferTheirLatestVersionAndDeletion() = runTest {
        val f = DirectoryFixture()
        f.sync("workspace")
        val reads = f.reads.size
        f.changesCall = {
            AppResult.Success(
                SyncChangePage(
                    listOf(
                        SyncChange(
                            SyncReference(SyncCollection.DIRECTORY_EMPLOYEES, directoryId(1), 2),
                            SyncOperation.UPSERT,
                        ),
                        SyncChange(
                            SyncReference(SyncCollection.DIRECTORY_EMPLOYEES, directoryId(1), 2),
                            SyncOperation.DELETE,
                        ),
                        SyncChange(
                            SyncReference(SyncCollection.DIRECTORY_EMPLOYEES, directoryId(1), 1),
                            SyncOperation.UPSERT,
                        ),
                    ),
                    "head-2",
                    false,
                    false,
                    5,
                    f.now,
                )
            )
        }
        f.sync("workspace")
        assertEquals(reads, f.reads.size)
        assertFalse(
            (f.store.snapshot.value as AppResult.Success).value.records.any {
                it.employee.id == directoryId(1)
            }
        )
    }

    @Test
    fun cancellationDuringHydrationCannotAdvanceTheCursor() = runTest {
        val f = DirectoryFixture()
        val entered = CompletableDeferred<Unit>()
        var active = 0
        f.recordCall = {
            entered.complete(Unit)
            active++
            try {
                awaitCancellation()
            } finally {
                active--
            }
        }
        val job = launch { f.sync("workspace") }
        entered.await()
        assertTrue(active in 1..4)
        job.cancelAndJoin()
        assertEquals(0, active)
        assertEquals(0, f.store.checkpoint!!.revision)
        assertNull((f.store.snapshot.value as AppResult.Success).value.checkpoint)
    }

    @Test
    fun scopeReplacementRejectsLateResults() = runTest {
        val f = DirectoryFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.recordCall = { id ->
            entered.complete(Unit)
            release.await()
            AppResult.Success(f.rows.getValue(id))
        }
        val job = async { f.sync("workspace") }
        entered.await()
        f.base.workspaces.state.value = AppResult.Success(f.workspace.copy(id = "replacement"))
        release.complete(Unit)
        assertIs<AppResult.Failed>(job.await())
        assertNull((f.store.snapshot.value as AppResult.Success).value.checkpoint)
    }

    @Test
    fun oneFailedRecordCancelsItsPendingSiblingsAndDoesNotPublish() = runTest {
        val f = DirectoryFixture()
        val blocked = CompletableDeferred<Unit>()
        var active = 0
        f.recordCall = { id ->
            if (id == directoryId(1)) {
                blocked.await()
                directoryFailure("unavailable", FailureKind.Network)
            } else {
                active++
                blocked.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    active--
                }
            }
        }
        assertIs<AppResult.Failed>(f.sync("workspace"))
        assertEquals(0, active)
        assertEquals(0, f.store.checkpoint!!.revision)
    }

    @Test
    fun failedCleanupAndOfflineRetriesCannotReopenAConfirmedDenial() = runTest {
        val f = DirectoryFixture()
        f.sync("workspace")
        f.store.failedClear = true
        f.policyCall = { directoryFailure("mfa_required", FailureKind.AccessDenied) }
        assertIs<AppResult.Failed>(f.sync("workspace"))
        f.policyCall = { directoryFailure("unavailable", FailureKind.Network) }
        assertIs<AppResult.Failed>(f.sync("workspace"))
        val denied =
            assertIs<AppResult.Failed>(f.observe("workspace", flowOf(DirectorySelection())).first())
        assertEquals("mfa_required", denied.failure.code)
        f.policyCall = {
            AppResult.Success(
                f.workspace.policy!!.policy.copy(serverTime = f.now, validUntil = f.now + 1.hours)
            )
        }
        assertTrue(
            assertIs<AppResult.Success<DirectorySyncOutcome>>(f.sync("workspace")).value.caughtUp
        )
        assertNotNull(
            assertIs<AppResult.Success<DirectoryView?>>(
                    f.observe("workspace", flowOf(DirectorySelection())).first()
                )
                .value
        )
    }

    @Test
    fun expiredCursorKeepsPublishedFactsDuringAnUnsuccessfulReplacement() = runTest {
        val f = DirectoryFixture()
        f.sync("workspace")
        val old = f.store.snapshot.value
        f.changesCall = { directoryFailure("sync_cursor_expired", FailureKind.Conflict) }
        f.bootstrapCall = { directoryFailure("unavailable", FailureKind.Network) }
        assertIs<AppResult.Failed>(f.sync("workspace"))
        assertEquals(old, f.store.snapshot.value)
        assertNull((f.store.accessState.value as AppResult.Success).value.denial)
    }

    @Test
    fun malformedSelectionAndCollectionNeverAcquireOrPublish() = runTest {
        val f = DirectoryFixture()
        assertIs<AppResult.Failed>(
            validateDirectorySelection(DirectorySelection(departmentId = "not-an-id"))
        )
        f.bootstrapCall = {
            AppResult.Success(
                SyncBootstrapPage(setOf(SyncCollection.PAYSLIPS), emptyList(), null, "head", f.now)
            )
        }
        assertIs<AppResult.Failed>(f.sync("workspace"))
        assertTrue(f.reads.isEmpty())
        assertNull((f.store.snapshot.value as AppResult.Success).value.checkpoint)
    }
}
