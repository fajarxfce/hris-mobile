@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.featureflags.domain

import dev.fajar.starter.common.config.AppEnvironment
import dev.fajar.starter.common.result.*
import dev.fajar.starter.featureflags.domain.entities.*
import dev.fajar.starter.featureflags.domain.policy.evaluateFlag
import dev.fajar.starter.featureflags.domain.repositories.FeatureFlagRepository
import dev.fajar.starter.featureflags.domain.usecases.*
import dev.fajar.starter.sync.domain.SyncResult
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class FeatureFlagPolicyTest {
    private val flag = BooleanFlag("saved_activities", true)

    @Test
    fun missingAndMalformedValuesUseTheDeclaredDefault() {
        for (value in listOf(null, "", "yes", "1", "broken")) {
            val snapshot =
                FlagSnapshot(remoteValues = value?.let { mapOf(flag.key to it) } ?: emptyMap())
            assertEquals(
                FlagEvaluation(true, FlagSource.Default),
                evaluateFlag(flag, snapshot, AppEnvironment.Dev),
            )
        }
        assertEquals(
            FlagEvaluation(false, FlagSource.Remote),
            evaluateFlag(flag, FlagSnapshot(mapOf(flag.key to " FALSE ")), AppEnvironment.Dev),
        )
    }

    @Test
    fun falseOverridesWinOnlyOutsideProduction() {
        val snapshot = FlagSnapshot(mapOf(flag.key to "true"), mapOf(flag.key to false))
        for (environment in listOf(AppEnvironment.Dev, AppEnvironment.Staging)) {
            assertEquals(
                FlagEvaluation(false, FlagSource.Override),
                evaluateFlag(flag, snapshot, environment),
            )
        }
        assertEquals(
            FlagEvaluation(true, FlagSource.Remote),
            evaluateFlag(flag, snapshot, AppEnvironment.Prod),
        )
    }

    @Test
    fun overridePolicyRejectsProductionAndNullRemovesAnOverride() = runTest {
        val repository = MemoryFlags()
        val production = SetFeatureFlagOverride(repository, AppEnvironment.Prod)
        assertIs<AppResult.Failed>(production(flag, false))
        assertEquals(0, repository.overrideCalls)
        val development = SetFeatureFlagOverride(repository, AppEnvironment.Dev)
        assertIs<AppResult.Success<Unit>>(development(flag, false))
        assertEquals(false, repository.current.value.overrides[flag.key])
        development(flag, null)
        assertTrue(repository.current.value.overrides.isEmpty())
    }

    @Test
    fun observationIgnoresUnrelatedParametersAndRefreshTimestamps() = runTest {
        val repository = MemoryFlags()
        val evaluations = mutableListOf<AppResult<FlagEvaluation>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            ObserveFeatureFlag(repository, AppEnvironment.Dev)(flag).toList(evaluations)
        }
        repository.current.value = FlagSnapshot(mapOf("other" to "false"), fetchedAtEpochMillis = 4)
        runCurrent()
        assertEquals(1, evaluations.size)
        repository.current.value = FlagSnapshot(mapOf(flag.key to "false"))
        runCurrent()
        assertEquals(
            AppResult.Success(FlagEvaluation(false, FlagSource.Remote)),
            evaluations.last(),
        )
        assertEquals(2, evaluations.size)
    }

    @Test
    fun concurrentRefreshesFetchOnceAndCancelledWaitersDoNotFetch() = runTest {
        val repository = MemoryFlags()
        val pending = CompletableDeferred<Unit>()
        repository.beforeRefresh = { pending.await() }
        val refresh = RefreshFeatureFlags(repository, AppEnvironment.Dev, FixedClock(100_000))
        val first = async { refresh() }
        runCurrent()
        val cancelled = async { refresh() }
        val second = async { refresh() }
        runCurrent()
        cancelled.cancelAndJoin()
        pending.complete(Unit)
        assertEquals(SyncResult.Complete, first.await())
        assertEquals(SyncResult.Complete, second.await())
        assertEquals(1, repository.refreshCalls)
    }

    @Test
    fun productionUsesTwelveHoursAndClockRollbackDoesNotFreezeRefresh() = runTest {
        val repository = MemoryFlags()
        repository.current.value = FlagSnapshot(fetchedAtEpochMillis = 100_000)
        val clock = FixedClock(160_000)
        val refresh = RefreshFeatureFlags(repository, AppEnvironment.Prod, clock)
        assertEquals(SyncResult.Complete, refresh())
        assertEquals(0, repository.refreshCalls)
        clock.time = 100_000 + 12 * 60 * 60 * 1000L
        refresh()
        assertEquals(1, repository.refreshCalls)
        clock.time = 90_000
        refresh()
        assertEquals(2, repository.refreshCalls)
    }

    @Test
    fun failedRefreshDoesNotStartTheSuccessIntervalAndCancellationReleasesTheLock() = runTest {
        val repository = MemoryFlags()
        repository.beforeRefresh = { awaitCancellation() }
        val refresh = RefreshFeatureFlags(repository, AppEnvironment.Dev, FixedClock(100_000))
        val pending = launch { refresh() }
        runCurrent()
        pending.cancelAndJoin()
        repository.beforeRefresh = {}
        repository.refreshFailure = Failure(FailureKind.Service, "Unavailable")
        assertIs<SyncResult.Retry>(refresh())
        assertEquals(0, repository.current.value.fetchedAtEpochMillis)
        repository.refreshFailure = null
        assertEquals(SyncResult.Complete, refresh())
        assertEquals(3, repository.refreshCalls)
    }
}

private class FixedClock(var time: Long) : Clock {
    override fun now() = Instant.fromEpochMilliseconds(time)
}

private class MemoryFlags : FeatureFlagRepository {
    val current = MutableStateFlow(FlagSnapshot())
    var overrideCalls = 0
    var refreshCalls = 0
    var beforeRefresh: suspend () -> Unit = {}
    var refreshFailure: Failure? = null

    override fun observe() = current.map { AppResult.Success(it) }

    override suspend fun snapshot() = AppResult.Success(current.value)

    override suspend fun refresh(fetchedAtEpochMillis: Long): AppResult<Unit> {
        refreshCalls++
        beforeRefresh()
        refreshFailure?.let {
            return AppResult.Failed(it)
        }
        current.value = current.value.copy(fetchedAtEpochMillis = fetchedAtEpochMillis)
        return AppResult.Success(Unit)
    }

    override suspend fun setOverride(key: String, value: Boolean?): AppResult<Unit> {
        overrideCalls++
        current.value =
            current.value.copy(
                overrides =
                    if (value == null) current.value.overrides - key
                    else current.value.overrides + (key to value)
            )
        return AppResult.Success(Unit)
    }
}
