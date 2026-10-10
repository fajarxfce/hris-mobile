package dev.fajar.starter.availability.domain.usecases

import dev.fajar.starter.availability.domain.entities.*
import dev.fajar.starter.availability.domain.policy.evaluateAvailability
import dev.fajar.starter.availability.domain.repositories.AvailabilityRepository
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import kotlin.time.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*

class ObserveAvailability(
    private val repository: AvailabilityRepository,
    private val build: AppBuild,
    private val clock: Clock = Clock.System,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(): Flow<AppResult<AppAvailability>> =
        repository
            .observe()
            .flatMapLatest { result ->
                flow<AppResult<AppAvailability>> {
                    when (result) {
                        is AppResult.Failed -> emit(result)
                        is AppResult.Success -> {
                            val now = clock.now().toEpochMilliseconds()
                            val availability = evaluateAvailability(result.value, build.number, now)
                            emit(AppResult.Success(availability))
                            if (availability is AppAvailability.Maintenance) {
                                delay(availability.untilEpochMillis - now)
                                emit(
                                    AppResult.Success(
                                        evaluateAvailability(
                                            result.value,
                                            build.number,
                                            availability.untilEpochMillis,
                                        )
                                    )
                                )
                            }
                        }
                    }
                }
            }
            .distinctUntilChanged()
}
