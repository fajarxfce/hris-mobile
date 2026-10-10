package dev.fajar.starter.featureflags.domain.usecases

import dev.fajar.starter.common.config.AppEnvironment
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.featureflags.domain.entities.*
import dev.fajar.starter.featureflags.domain.policy.evaluateFlag
import dev.fajar.starter.featureflags.domain.repositories.FeatureFlagRepository
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class ObserveFeatureFlag(
    private val repository: FeatureFlagRepository,
    private val environment: AppEnvironment,
) {
    operator fun invoke(flag: BooleanFlag) =
        repository
            .observe()
            .map { result ->
                when (result) {
                    is AppResult.Failed -> result
                    is AppResult.Success ->
                        AppResult.Success(evaluateFlag(flag, result.value, environment))
                }
            }
            .distinctUntilChanged()
}
