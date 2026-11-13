@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class CollaborationSpaceInboxSeedWorkerTest {
    @Test
    fun startupSeedsOnceAndOwnDemandOrOrdinaryProgressCannotRepeatIt() = runTest {
        val f = SpaceInboxRepairWorkerFixture()
        f.references.clear()
        f.records.value = AppResult.Success(listOf(f.queued))
        f.onSeed = {
            f.request()
            AppResult.Success(true)
        }
        val job = backgroundScope.launch { f.worker()(f.access).collect() }
        runCurrent()
        assertEquals(listOf(f.queued.intent), f.seeded)
        assertEquals(f.progress?.latestDemand, f.progress?.completedDemand)
        f.records.value = AppResult.Success(listOf(f.sent))
        runCurrent()
        f.request()
        runCurrent()
        advanceTimeBy(86_400_000)
        runCurrent()
        assertEquals(listOf(f.queued.intent), f.seeded)
        assertEquals(1, f.commandStarts)
        assertEquals(1, f.requestStarts)
        job.cancelAndJoin()
        assertEquals(1, f.commandStops)
        assertEquals(1, f.requestStops)
    }

    @Test
    fun aFailedSeedStaysParkedWhenAnEarlierSeedProducesARequest() = runTest {
        val f = SpaceInboxRepairWorkerFixture()
        f.references.clear()
        val second = f.queued.copy(intent = f.queued.intent.copy(id = f.id(11)))
        val third = f.queued.copy(intent = f.queued.intent.copy(id = f.id(12)))
        f.records.value = AppResult.Success(listOf(f.queued, second, third))
        f.onSeed = { original ->
            if (original.id == f.queued.intent.id) {
                f.request()
                AppResult.Success(true)
            } else AppResult.Failed(f.storageFailure)
        }
        val outcomes = mutableListOf<AppResult<Unit>>()
        val job = backgroundScope.launch { f.worker()(f.access).collect { outcomes += it } }
        runCurrent()
        assertEquals(listOf(f.queued.intent, second.intent), f.seeded)
        assertEquals(AppResult.Failed(f.storageFailure), outcomes.last())
        f.request()
        runCurrent()
        assertEquals(2, f.seeded.size)
        assertEquals(AppResult.Failed(f.storageFailure), outcomes.last())
        f.onSeed = { AppResult.Success(true) }
        f.records.value = AppResult.Success(listOf(f.sent, second, third))
        runCurrent()
        assertEquals(
            listOf(f.queued.intent, second.intent, f.queued.intent, second.intent, third.intent),
            f.seeded,
        )
        assertEquals(AppResult.Success(Unit), outcomes.last())
        job.cancelAndJoin()
    }

    @Test
    fun laterSeedDenialSurvivesOwnRepairUntilAnAdmittedSeedRecovers() = runTest {
        val f = SpaceInboxRepairWorkerFixture()
        f.references.clear()
        f.records.value = AppResult.Success(listOf(f.queued))
        f.onSeed = { AppResult.Failed(f.storageFailure) }
        val outcomes = mutableListOf<AppResult<Unit>>()
        val job = backgroundScope.launch { f.worker()(f.access).collect { outcomes += it } }
        runCurrent()
        assertEquals(AppResult.Failed(f.storageFailure), outcomes.last())
        val denied = Failure(FailureKind.AccessDenied, "Access denied")
        f.onSeed = { AppResult.Failed(denied) }
        f.records.value = AppResult.Success(listOf(f.sent))
        runCurrent()
        assertEquals(AppResult.Failed(denied), outcomes.last())
        assertEquals(2, f.seeded.size)
        f.request()
        runCurrent()
        assertEquals(AppResult.Failed(denied), outcomes.last())
        assertEquals(2, f.seeded.size)
        f.onSeed = { AppResult.Success(true) }
        val next = f.queued.copy(intent = f.queued.intent.copy(id = f.id(11)))
        f.records.value = AppResult.Success(listOf(f.sent, next))
        runCurrent()
        assertEquals(AppResult.Success(Unit), outcomes.last())
        assertEquals(4, f.seeded.size)
        job.cancelAndJoin()
    }

    @Test
    fun anUncertainCommittedSeedIsNotRetriedByItsOwnRequest() = runTest {
        val f = SpaceInboxRepairWorkerFixture()
        f.references.clear()
        f.records.value = AppResult.Success(listOf(f.queued))
        f.onSeed = {
            f.request()
            AppResult.Failed(f.storageFailure)
        }
        val outcomes = mutableListOf<AppResult<Unit>>()
        val job = backgroundScope.launch { f.worker()(f.access).collect { outcomes += it } }
        runCurrent()
        assertEquals(1, f.seeded.size)
        assertEquals(AppResult.Failed(f.storageFailure), outcomes.last())
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, f.seeded.size)
        f.onSeed = { AppResult.Success(true) }
        f.records.value = AppResult.Success(listOf(f.sent))
        runCurrent()
        assertEquals(2, f.seeded.size)
        assertEquals(AppResult.Success(Unit), outcomes.last())
        job.cancelAndJoin()
    }

    @Test
    fun aSeedDeadlineCancelsTheAttemptAndWaitsForAChangedOriginalBeforeRetrying() = runTest {
        val f = SpaceInboxRepairWorkerFixture()
        f.references.clear()
        f.records.value = AppResult.Success(listOf(f.queued))
        var cancelled = false
        f.onSeed = {
            try {
                awaitCancellation()
            } finally {
                cancelled = true
            }
        }
        val outcomes = mutableListOf<AppResult<Unit>>()
        val job = backgroundScope.launch { f.worker()(f.access).collect { outcomes += it } }
        runCurrent()
        advanceTimeBy(30_000)
        runCurrent()
        assertTrue(cancelled)
        assertEquals(1, f.seeded.size)
        assertIs<AppResult.Failed>(outcomes.last())
        f.request()
        runCurrent()
        assertEquals(1, f.seeded.size)
        assertIs<AppResult.Failed>(outcomes.last())
        f.onSeed = { AppResult.Success(true) }
        f.records.value = AppResult.Success(listOf(f.sent))
        runCurrent()
        assertEquals(2, f.seeded.size)
        assertEquals(AppResult.Success(Unit), outcomes.last())
        job.cancelAndJoin()
    }

    @Test
    fun disposingTheOwnerCancelsSeedingAndJoinsBothObservers() = runTest {
        val f = SpaceInboxRepairWorkerFixture()
        f.records.value = AppResult.Success(listOf(f.queued))
        var cancelled = false
        f.onSeed = {
            try {
                awaitCancellation()
            } finally {
                cancelled = true
            }
        }
        val job = backgroundScope.launch { f.worker()(f.access).collect() }
        runCurrent()
        job.cancelAndJoin()
        assertTrue(cancelled)
        assertEquals(1, f.seeded.size)
        assertEquals(1, f.commandStops)
        assertEquals(1, f.requestStops)
        assertTrue(f.pages.isEmpty())
    }

    @Test
    fun anExplicitNewOwnerCanRetryTheSameRetainedInventory() = runTest {
        val f = SpaceInboxRepairWorkerFixture()
        f.references.clear()
        f.records.value = AppResult.Success(listOf(f.queued))
        f.onSeed = { AppResult.Failed(f.storageFailure) }
        val first = backgroundScope.launch { f.worker()(f.access).collect() }
        runCurrent()
        assertEquals(1, f.seeded.size)
        first.cancelAndJoin()
        f.onSeed = { AppResult.Success(true) }
        val second = backgroundScope.launch { f.worker()(f.access).collect() }
        runCurrent()
        assertEquals(2, f.seeded.size)
        assertEquals(2, f.commandStarts)
        assertEquals(2, f.requestStarts)
        second.cancelAndJoin()
        assertEquals(2, f.commandStops)
        assertEquals(2, f.requestStops)
    }

    @Test
    fun missingSourcesAreFiniteAndNewOriginalsAreSelectedWithoutReprobingKnownOnes() = runTest {
        val f = SpaceInboxRepairWorkerFixture()
        f.references.clear()
        f.records.value = AppResult.Success(listOf(f.queued))
        f.onSeed = { AppResult.Success(false) }
        val job = backgroundScope.launch { f.worker()(f.access).collect() }
        runCurrent()
        val next = f.queued.copy(intent = f.queued.intent.copy(id = f.id(11)))
        f.records.value = AppResult.Success(listOf(f.sent, next))
        runCurrent()
        assertEquals(listOf(f.queued.intent, next.intent), f.seeded)
        f.records.value = AppResult.Success(listOf(next))
        runCurrent()
        assertEquals(listOf(f.queued.intent, next.intent, f.queued.intent), f.seeded)
        f.request()
        runCurrent()
        assertEquals(3, f.seeded.size)
        job.cancelAndJoin()
    }

    @Test
    fun cancelledStartupLeavesUnattemptedOriginalsForTheNextExplicitOwner() = runTest {
        val f = SpaceInboxRepairWorkerFixture()
        f.references.clear()
        f.records.value = AppResult.Success(listOf(f.queued))
        var blocked = true
        f.afterChange = { if (blocked) throw CancellationException("Cancelled startup request") }
        val first = backgroundScope.launch { f.worker()(f.access).collect() }
        runCurrent()
        assertTrue(first.isCancelled)
        assertTrue(f.seeded.isEmpty())
        blocked = false
        val second = backgroundScope.launch { f.worker()(f.access).collect() }
        runCurrent()
        assertEquals(listOf(f.queued.intent), f.seeded)
        second.cancelAndJoin()
    }
}
