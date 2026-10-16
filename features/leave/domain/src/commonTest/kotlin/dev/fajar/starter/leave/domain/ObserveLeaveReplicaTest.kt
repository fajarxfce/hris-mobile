@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.leave.domain

import dev.fajar.starter.common.result.*
import dev.fajar.starter.leave.domain.entities.*
import dev.fajar.starter.leave.domain.policies.initialLeaveCheckpoint
import dev.fajar.starter.leave.domain.repositories.LeaveReplicaRepository
import dev.fajar.starter.leave.domain.usecases.ObserveLeave
import dev.fajar.starter.workspace.domain.entities.CompanyDataSet
import dev.fajar.starter.workspace.domain.entities.CompanyDataStatus
import dev.fajar.starter.workspace.domain.entities.CompanyDataSyncOutcome
import dev.fajar.starter.workspace.domain.usecases.ObserveCompanyData
import dev.fajar.starter.workspace.domain.usecases.SyncCompanyData
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class ObserveLeaveReplicaTest {
    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun publishedStatusRetainsPreviousCompletionWhileStagingAndClosesOnDenial() = runTest {
        val f = LeaveSyncFixture()
        val observation: ObserveCompanyData = f.base.observe
        val acquisition: SyncCompanyData = f.sync
        val workspaceId = f.base.workspace.id
        assertEquals(CompanyDataSet.Leave, observation.dataSet)
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
        val published = requireNotNull(f.replica.current)
        val expected = AppResult.Success(CompanyDataStatus(assertNotNull(published.refreshedAt)))
        assertEquals(expected, values.last())
        val replacement =
            initialLeaveCheckpoint(published.access, "status-replacement", published.refreshedAt)
        assertEquals(AppResult.Success(true), f.replica.begin(replacement, published.generation))
        runCurrent()
        assertEquals(expected, values.last())
        val denial =
            Failure(FailureKind.AccessDenied, "Verification required", code = "mfa_required")
        f.replica.restrict(published.access, denial)
        runCurrent()
        assertEquals(AppResult.Failed(denial), values.last())
        assertEquals(AppResult.Failed(denial), observation.observeStatus(workspaceId).first())
        assertEquals(0, f.replica.published.subscriptionCount.value)
        reader.cancelAndJoin()
        assertEquals(0, f.replica.published.subscriptionCount.value)
    }

    @Test
    fun filteringStoredRecordsDoesNotStartNetworkWorkAndKnownEmptyResultsRemainReadable() =
        runTest {
            val f = LeaveSyncFixture()
            f.run()
            val calls =
                f.bootstrapReads +
                    f.changeReads +
                    f.recordReads +
                    f.base.directoryReads +
                    f.base.policyReads
            for (selection in
                listOf(
                    LeaveSelection(),
                    LeaveSelection(year = 2025),
                    LeaveSelection(status = LeaveStatus.APPROVED),
                )) {
                val page =
                    assertIs<AppResult.Success<LeaveOverview?>>(
                            f.base.observe(f.base.workspace.id, selection).first()
                        )
                        .value!!
                if (selection.year == 2025) assertTrue(page.balances.items.isEmpty())
                if (selection.status == LeaveStatus.APPROVED)
                    assertTrue(page.requests.items.isEmpty())
            }
            assertEquals(
                calls,
                f.bootstrapReads +
                    f.changeReads +
                    f.recordReads +
                    f.base.directoryReads +
                    f.base.policyReads,
            )
            assertEquals(0, f.replica.directoryObservers)
            assertEquals(0, f.replica.pageObservers)
        }

    @Test
    fun metadataAndQueryResultsFromDifferentRevisionsAreNeverCombined() = runTest {
        val f = LeaveSyncFixture()
        f.run()
        val access = f.replica.current!!.access
        val selection = LeaveSelection(f.base.employee.id, 2026)
        val original =
            assertIs<AppResult.Success<LeaveReplicaPage>>(
                    f.replica.observe(access, selection).first()
                )
                .value
        val pages =
            MutableStateFlow<AppResult<LeaveReplicaPage>>(
                AppResult.Success(
                    original.copy(
                        checkpoint =
                            original.checkpoint!!.copy(revision = original.checkpoint.revision + 1)
                    )
                )
            )
        val repository =
            object : LeaveReplicaRepository by f.replica {
                override fun observe(access: LeaveAccess, selection: LeaveSelection) = pages
            }
        val observe =
            ObserveLeave(
                repository,
                f.base.cache,
                f.base.sessions,
                f.base.workspaces,
                f.base.clock,
                f.base.build,
            )
        val values = mutableListOf<AppResult<LeaveOverview?>>()
        val reader =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                observe(f.base.workspace.id).collect { values += it }
            }
        assertTrue(values.isEmpty())
        pages.value = AppResult.Success(original)
        runCurrent()
        assertEquals(
            f.base.request,
            assertIs<AppResult.Success<LeaveOverview?>>(values.single())
                .value!!
                .requests
                .items
                .single(),
        )
        reader.cancelAndJoin()
        assertEquals(0, f.replica.directoryObservers)
    }

    @Test
    fun elapsedFreshnessAndAReplacementSessionDoNotDiscardAnOwnedLocalRead() = runTest {
        var now = Instant.parse("2026-10-12T01:00:00Z")
        val f = LeaveSyncFixture(LeaveFixture { now })
        f.run()
        val expected =
            assertIs<AppResult.Success<LeaveOverview?>>(f.base.observe(f.base.workspace.id).first())
                .value!!
        now += 72.hours
        val session = f.base.session.copy(id = "new-login")
        f.base.sessionState.value = AppResult.Success(session)
        f.base.workspaceState.value =
            AppResult.Success(f.base.workspace.copy(sessionId = session.id))
        val read =
            assertIs<AppResult.Success<LeaveOverview?>>(f.base.observe(f.base.workspace.id).first())
                .value!!
        assertEquals(expected.requests, read.requests)
        assertEquals(expected.balances, read.balances)
        assertEquals(expected.fetchedAt, read.fetchedAt)
        assertEquals(expected.refreshAfter, read.refreshAfter)
        assertEquals(1, f.bootstrapReads)
    }

    @Test
    fun scopeLossDisposesTheLocalQueryAndDoesNotExposeRetainedRecordsUnderNewPermissions() =
        runTest {
            val f = LeaveSyncFixture()
            f.run()
            val values = mutableListOf<AppResult<LeaveOverview?>>()
            val reader =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    f.base.observe(f.base.workspace.id).collect { values += it }
                }
            assertEquals(1, f.replica.pageObservers)
            f.base.workspaceState.value =
                AppResult.Success(f.base.workspace.copy(permissions = setOf("leave.read")))
            runCurrent()
            assertEquals(
                "client.leave_scope_changed",
                assertIs<AppResult.Failed>(values.last()).failure.code,
            )
            assertEquals(0, f.replica.pageObservers)
            f.base.sessionState.value = AppResult.Success(null)
            runCurrent()
            assertEquals(0, f.replica.directoryObservers)
            reader.cancelAndJoin()
        }

    @Test
    fun invalidFiltersAreRejectedBeforeOpeningStorageObservers() = runTest {
        val f = LeaveFixture()
        for (selection in
            listOf(
                LeaveSelection(employeeId = "../other"),
                LeaveSelection(year = 2201),
                LeaveSelection(balanceAfter = "malformed page"),
                LeaveSelection(requestAfter = "x"),
            )) {
            assertIs<AppResult.Failed>(f.observe(f.workspace.id, selection).first())
        }
        assertEquals(0, f.observers)
        assertEquals(0, f.replica.directoryObservers)
        assertTrue(f.replica.selections.isEmpty())
    }
}
