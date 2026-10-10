package dev.fajar.starter.featureflags.domain.usecases

import dev.fajar.starter.common.config.AppEnvironment
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.featureflags.domain.repositories.FeatureFlagRepository
import dev.fajar.starter.sync.domain.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One instance per container serializes all workers and applies the fetch interval. */
class RefreshFeatureFlags(
    private val repository: FeatureFlagRepository,
    private val environment: AppEnvironment,
    private val clock: Clock = Clock.System,
) : SyncTask {
    override val key = KEY
    private val execution = Mutex()

    override suspend fun invoke(): SyncResult =
        execution.withLock {
            val snapshot =
                when (val result = repository.snapshot()) {
                    is AppResult.Failed -> return@withLock syncFailure(result.failure)
                    is AppResult.Success -> result.value
                }
            val now = clock.now().toEpochMilliseconds()
            val interval = if (environment == AppEnvironment.Prod) 12.hours else 1.minutes
            val age = now - snapshot.fetchedAtEpochMillis
            if (
                snapshot.fetchedAtEpochMillis > 0 && age >= 0 && age < interval.inWholeMilliseconds
            ) {
                return@withLock SyncResult.Complete
            }
            when (val result = repository.refresh(now)) {
                is AppResult.Failed -> syncFailure(result.failure)
                is AppResult.Success -> SyncResult.Complete
            }
        }

    companion object {
        const val KEY = "feature-flags-refresh"
    }
}
