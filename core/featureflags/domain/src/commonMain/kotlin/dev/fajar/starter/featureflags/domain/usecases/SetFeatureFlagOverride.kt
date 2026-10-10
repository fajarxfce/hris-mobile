package dev.fajar.starter.featureflags.domain.usecases

import dev.fajar.starter.common.config.AppEnvironment
import dev.fajar.starter.common.result.*
import dev.fajar.starter.featureflags.domain.entities.BooleanFlag
import dev.fajar.starter.featureflags.domain.repositories.FeatureFlagRepository

class SetFeatureFlagOverride(
    private val repository: FeatureFlagRepository,
    private val environment: AppEnvironment,
) {
    /** null removes the override and resumes remote/default evaluation. */
    suspend operator fun invoke(flag: BooleanFlag, value: Boolean?): AppResult<Unit> {
        if (environment == AppEnvironment.Prod)
            return AppResult.Failed(
                Failure(
                    FailureKind.Unavailable,
                    "Feature flag overrides are disabled in production.",
                )
            )
        return repository.setOverride(flag.key, value)
    }
}
