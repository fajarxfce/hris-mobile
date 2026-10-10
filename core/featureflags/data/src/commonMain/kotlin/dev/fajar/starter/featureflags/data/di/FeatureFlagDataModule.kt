package dev.fajar.starter.featureflags.data.di

import dev.fajar.starter.common.config.AppEnvironment
import dev.fajar.starter.featureflags.domain.repositories.FeatureFlagRepository
import dev.fajar.starter.featureflags.domain.usecases.*
import dev.fajar.starter.sync.domain.SyncTask
import org.koin.core.annotation.*

@Module
@ComponentScan("dev.fajar.starter.featureflags.data")
class FeatureFlagDataModule {
    @Factory
    fun observe(repository: FeatureFlagRepository, environment: AppEnvironment) =
        ObserveFeatureFlag(repository, environment)

    @Factory
    fun override(repository: FeatureFlagRepository, environment: AppEnvironment) =
        SetFeatureFlagOverride(repository, environment)

    @Single(binds = [SyncTask::class, RefreshFeatureFlags::class])
    fun refresh(repository: FeatureFlagRepository, environment: AppEnvironment) =
        RefreshFeatureFlags(repository, environment)
}
