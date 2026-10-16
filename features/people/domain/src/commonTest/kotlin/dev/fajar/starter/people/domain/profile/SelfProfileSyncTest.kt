package dev.fajar.starter.people.domain.profile

import dev.fajar.starter.common.result.*
import dev.fajar.starter.people.domain.profile.entities.*
import dev.fajar.starter.people.domain.profile.policies.*
import dev.fajar.starter.sync.domain.entities.*
import dev.fajar.starter.workspace.domain.entities.CompanyDataSet
import dev.fajar.starter.workspace.domain.entities.CompanyDataStatus
import dev.fajar.starter.workspace.domain.entities.CompanyDataSyncOutcome
import dev.fajar.starter.workspace.domain.usecases.ObserveCompanyData
import dev.fajar.starter.workspace.domain.usecases.SyncCompanyData
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class SelfProfileSyncTest {
    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun publishedStatusRetainsPreviousCompletionWhileStagingAndClosesOnDenial() = runTest {
        val f = SelfProfileFixture()
        val observation: ObserveCompanyData = f.observe
        val acquisition: SyncCompanyData = f.sync
        val workspaceId = "workspace"
        assertEquals(CompanyDataSet.Profile, observation.dataSet)
        assertEquals(observation.dataSet, acquisition.dataSet)
        val values = mutableListOf<AppResult<CompanyDataStatus>>()
        val reader =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                observation.observeStatus(workspaceId).collect { values += it }
            }
        runCurrent()
        assertEquals(AppResult.Success(CompanyDataStatus(null)), values.last())
        assertTrue(
            assertIs<AppResult.Success<CompanyDataSyncOutcome>>(acquisition.refresh(workspaceId))
                .value
                .caughtUp
        )
        runCurrent()
        val published = requireNotNull(f.store.checkpoint)
        val expected = AppResult.Success(CompanyDataStatus(assertNotNull(published.refreshedAt)))
        assertEquals(expected, values.last())
        val replacement =
            initialSelfProfileCheckpoint(
                published.access,
                "status-replacement",
                published.refreshedAt,
            )
        assertEquals(AppResult.Success(true), f.store.begin(replacement, published.generation))
        runCurrent()
        assertEquals(expected, values.last())
        val denial =
            Failure(FailureKind.AccessDenied, "Verification required", code = "mfa_required")
        f.store.restrict(published.access, denial)
        runCurrent()
        assertEquals(AppResult.Failed(denial), values.last())
        assertEquals(AppResult.Failed(denial), observation.observeStatus(workspaceId).first())
        assertEquals(0, f.store.snapshot.subscriptionCount.value)
        reader.cancelAndJoin()
        assertEquals(0, f.store.snapshot.subscriptionCount.value)
    }

    @Test
    fun liveCredentialExpiryDoesNotWithdrawOwnedSelfProfileRecords() = runTest {
        val f = SelfProfileFixture()
        f.sync("workspace")
        f.policyCall = { selfProfileFailure("session_expired", FailureKind.Unauthorized) }
        assertIs<AppResult.Failed>(f.sync("workspace"))
        assertNull((f.store.accessState.value as AppResult.Success).value.denial)
        assertNotNull(
            assertIs<AppResult.Success<SelfProfileView?>>(
                    f.observe("workspace", flowOf<String?>(null)).first()
                )
                .value
        )
    }

    @Test
    fun allOwnedEmploymentsCanBeSelectedLocallyWithoutAnotherRequest() = runTest {
        val f = SelfProfileFixture()
        assertTrue(
            assertIs<AppResult.Success<SelfProfileSyncOutcome>>(f.sync("workspace")).value.caughtUp
        )
        assertEquals(45, f.reads.size)
        val selected = MutableStateFlow<String?>(selfProfileId(45))
        val value =
            assertIs<AppResult.Success<SelfProfileView?>>(f.observe("workspace", selected).first())
                .value!!
        assertEquals(selfProfileId(45), value.profile!!.details.employee.id)
        assertEquals(45, value.employments.size)
        assertEquals(1, f.bootstrapReads)
        assertEquals(1, f.changeReads)
        val snapshot = (f.store.snapshot.value as AppResult.Success).value
        assertEquals(selfProfileId(20), selectSelfProfile(snapshot, selfProfileId(20))!!.selectedId)
        assertEquals(selfProfileId(1), selectSelfProfile(snapshot, "withdrawn")!!.selectedId)
        assertEquals(45, f.reads.size)
    }

    @Test
    fun ordinaryAgeAndCredentialExpiryNeverHideOwnedLocalFacts() = runTest {
        val f = SelfProfileFixture()
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
            assertIs<AppResult.Success<SelfProfileView?>>(
                    f.observe("workspace", flowOf<String?>(selfProfileId(45))).first()
                )
                .value!!
        assertEquals("E00045", view.profile!!.details.employee.employeeNumber)
        assertEquals(1, f.bootstrapReads)
        assertEquals(1, f.changeReads)
    }

    @Test
    fun routeEntrySkipsFreshAcquisitionWhileExplicitRefreshStillSynchronizes() = runTest {
        val f = SelfProfileFixture()
        f.sync("workspace")
        f.sync("workspace", force = false)
        assertEquals(1, f.changeReads)
        f.sync("workspace")
        assertEquals(2, f.changeReads)
    }

    @Test
    fun publicationPendingNeverExposesAnIncompleteReplacement() = runTest {
        val f = SelfProfileFixture()
        f.changesCall = {
            AppResult.Success(SyncChangePage(emptyList(), "head", false, true, 5, f.now))
        }
        assertFalse(
            assertIs<AppResult.Success<SelfProfileSyncOutcome>>(f.sync("workspace")).value.caughtUp
        )
        assertNull((f.store.snapshot.value as AppResult.Success).value.checkpoint)
        assertTrue(f.changeReads <= 10)
        f.changesCall = {
            AppResult.Success(SyncChangePage(emptyList(), "head", false, false, 5, f.now))
        }
        assertTrue(
            assertIs<AppResult.Success<SelfProfileSyncOutcome>>(f.sync("workspace")).value.caughtUp
        )
        assertNotNull((f.store.snapshot.value as AppResult.Success).value.checkpoint)
    }

    @Test
    fun deletedOrWithdrawnIndividualsDoNotRevokeTheSelfProfile() = runTest {
        val f = SelfProfileFixture()
        f.sync("workspace")
        f.rows.remove(selfProfileId(1))
        f.changesCall = {
            AppResult.Success(
                SyncChangePage(
                    listOf(
                        SyncChange(
                            SyncReference(SyncCollection.SELF_PROFILES, selfProfileId(1), 1),
                            SyncOperation.UPSERT,
                        ),
                        SyncChange(
                            SyncReference(SyncCollection.SELF_PROFILES, selfProfileId(2), 1),
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
        assertEquals(1, f.reads.count { it == selfProfileId(2) })
    }

    @Test
    fun duplicateReferencesPreferTheirLatestVersionAndDeletion() = runTest {
        val f = SelfProfileFixture()
        f.sync("workspace")
        val reads = f.reads.size
        f.changesCall = {
            AppResult.Success(
                SyncChangePage(
                    listOf(
                        SyncChange(
                            SyncReference(SyncCollection.SELF_PROFILES, selfProfileId(1), 2),
                            SyncOperation.UPSERT,
                        ),
                        SyncChange(
                            SyncReference(SyncCollection.SELF_PROFILES, selfProfileId(1), 2),
                            SyncOperation.DELETE,
                        ),
                        SyncChange(
                            SyncReference(SyncCollection.SELF_PROFILES, selfProfileId(1), 1),
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
                it.id == selfProfileId(1)
            }
        )
    }

    @Test
    fun cancellationDuringHydrationCannotAdvanceTheCursor() = runTest {
        val f = SelfProfileFixture()
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
        val f = SelfProfileFixture()
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
        val f = SelfProfileFixture()
        val blocked = CompletableDeferred<Unit>()
        var active = 0
        f.recordCall = { id ->
            if (id == selfProfileId(1)) {
                blocked.await()
                selfProfileFailure("unavailable", FailureKind.Network)
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
        val f = SelfProfileFixture()
        f.sync("workspace")
        f.store.failedClear = true
        f.policyCall = { selfProfileFailure("mfa_required", FailureKind.AccessDenied) }
        assertIs<AppResult.Failed>(f.sync("workspace"))
        f.policyCall = { selfProfileFailure("unavailable", FailureKind.Network) }
        assertIs<AppResult.Failed>(f.sync("workspace"))
        val denied =
            assertIs<AppResult.Failed>(f.observe("workspace", flowOf<String?>(null)).first())
        assertEquals("mfa_required", denied.failure.code)
        f.policyCall = {
            AppResult.Success(
                f.workspace.policy!!.policy.copy(serverTime = f.now, validUntil = f.now + 1.hours)
            )
        }
        assertTrue(
            assertIs<AppResult.Success<SelfProfileSyncOutcome>>(f.sync("workspace")).value.caughtUp
        )
        assertNotNull(
            assertIs<AppResult.Success<SelfProfileView?>>(
                    f.observe("workspace", flowOf<String?>(null)).first()
                )
                .value
        )
    }

    @Test
    fun expiredCursorKeepsPublishedFactsDuringAnUnsuccessfulReplacement() = runTest {
        val f = SelfProfileFixture()
        f.sync("workspace")
        val old = f.store.snapshot.value
        f.changesCall = { selfProfileFailure("sync_cursor_expired", FailureKind.Conflict) }
        f.bootstrapCall = { selfProfileFailure("unavailable", FailureKind.Network) }
        assertIs<AppResult.Failed>(f.sync("workspace"))
        assertEquals(old, f.store.snapshot.value)
        assertNull((f.store.accessState.value as AppResult.Success).value.denial)
    }

    @Test
    fun anUnrequestedCollectionNeverAcquiresOrPublishes() = runTest {
        val f = SelfProfileFixture()
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
