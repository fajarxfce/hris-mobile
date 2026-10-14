@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.expenses.domain

import dev.fajar.starter.common.result.*
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.policies.expenseFailure
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class ExpenseObservationTest {
    @Test
    fun staleReadsKeepTheirCollectorUntilDisposal() = runTest {
        for (detail in listOf(false, true)) {
            val origin = testScheduler.currentTime
            val f = ExpenseFixture {
                Instant.parse("2026-10-13T00:00:00Z") +
                    (testScheduler.currentTime - origin).milliseconds
            }
            f.load(detail)
            val seen = mutableListOf<AppResult<*>>()
            val source =
                if (detail) f.observeDetail(f.workspace.id, f.summary.id)
                else f.observe(f.workspace.id)
            val job =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    source.collect { seen += it }
                }
            assertIs<AppResult.Success<*>>(seen.last())
            assertEquals(1, f.observers)
            advanceTimeBy(3 * 24 * 60 * 60_000L)
            runCurrent()
            assertIs<AppResult.Success<*>>(seen.last())
            assertEquals(1, seen.size)
            job.cancelAndJoin()
            assertEquals(0, f.observers)
        }
    }

    @Test
    fun sessionAndPermissionChangesStopTheOldSubscription() = runTest {
        for (logout in listOf(false, true)) {
            val f = ExpenseFixture()
            f.load()
            val seen = mutableListOf<AppResult<*>>()
            val job =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    f.observe(f.workspace.id).collect { seen += it }
                }
            if (logout) f.sessionState.value = AppResult.Success(null)
            else
                f.workspaceState.value =
                    AppResult.Success(f.workspace.copy(permissions = emptySet()))
            runCurrent()
            assertIs<AppResult.Failed>(seen.last())
            assertEquals(0, f.observers)
            job.cancelAndJoin()
        }
    }

    @Test
    fun unrelatedDetailIsHiddenWhileAnOwnedStaleClaimRemainsReadable() = runTest {
        val f = ExpenseFixture()
        f.load(true)
        val snapshot =
            (f.observeDetail(f.workspace.id, f.claim.id).first() as AppResult.Success).value!!
        f.seedDetails(snapshot.copy(refreshAfter = f.clock.now() - 1.seconds))
        assertEquals(
            AppResult.Success(null),
            f.observeDetail(f.workspace.id, "55555555-5555-5555-5555-555555555555").first(),
        )
        assertEquals(
            AppResult.Success(snapshot.copy(refreshAfter = f.clock.now() - 1.seconds)),
            f.observeDetail(f.workspace.id, f.summary.id).first(),
        )
    }

    @Test
    fun aRecordFailureCanRecoverOnAValidStorageEmission() = runTest {
        val f = ExpenseFixture()
        f.load()
        val valid = f.replicas.published.value
        val expected = f.observe(f.workspace.id).first()
        f.replicas.published.value = expenseFailure("fixture.storage", FailureKind.Storage)
        val seen = mutableListOf<AppResult<*>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe(f.workspace.id).collect { seen += it }
            }
        assertIs<AppResult.Failed>(seen.last())
        f.replicas.published.value = valid
        runCurrent()
        assertEquals(expected, seen.last())
        job.cancelAndJoin()
        assertEquals(0, f.observers)
    }
}
