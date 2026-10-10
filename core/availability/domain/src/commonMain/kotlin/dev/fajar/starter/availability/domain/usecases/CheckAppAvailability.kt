package dev.fajar.starter.availability.domain.usecases

import dev.fajar.starter.availability.domain.entities.*
import dev.fajar.starter.availability.domain.policy.evaluateAvailability
import dev.fajar.starter.availability.domain.repositories.AvailabilityRepository
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import kotlin.time.Clock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * HTTP adapters invoke this policy before first-party API I/O. Configuration recovery stays
 * available.
 */
class CheckAppAvailability(
    private val repository: AvailabilityRepository,
    private val build: AppBuild,
    private val clock: Clock = Clock.System,
) {
    suspend operator fun invoke(): AppResult<Unit> {
        val policy =
            when (val result = repository.current()) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        currentCoroutineContext().ensureActive()
        return when (
            evaluateAvailability(policy, build.number, clock.now().toEpochMilliseconds())
        ) {
            is AppAvailability.UpdateRequired ->
                AppResult.Failed(
                    Failure(FailureKind.Unavailable, "Update the application to continue.")
                )
            is AppAvailability.Maintenance ->
                AppResult.Failed(
                    Failure(FailureKind.Unavailable, "The application is temporarily unavailable.")
                )
            else -> AppResult.Success(Unit)
        }
    }
}
