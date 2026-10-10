package dev.fajar.starter.availability.domain.usecases

import dev.fajar.starter.availability.domain.repositories.AvailabilityRepository
import dev.fajar.starter.common.result.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class DismissRecommendedUpdate(private val repository: AvailabilityRepository) {
    suspend operator fun invoke(build: Long): AppResult<Unit> {
        val policy =
            when (val result = repository.current()) {
                is AppResult.Failed -> return result
                is AppResult.Success -> result.value
            }
        currentCoroutineContext().ensureActive()
        if (build <= 0 || build != policy.recommendedBuild)
            return AppResult.Failed(
                Failure(FailureKind.Validation, "The update policy changed. Try again.")
            )
        return repository.dismiss(build)
    }
}
