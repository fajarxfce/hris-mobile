package dev.fajar.starter.availability.data.di

import dev.fajar.starter.availability.domain.repositories.AvailabilityRepository
import dev.fajar.starter.availability.domain.usecases.*
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.featureflags.domain.repositories.FeatureFlagRepository
import org.koin.core.annotation.*

@Module
@ComponentScan("dev.fajar.starter.availability.data")
class AvailabilityDataModule {
    @Factory
    fun observe(repository: AvailabilityRepository, build: AppBuild) =
        ObserveAvailability(repository, build)

    @Factory
    fun check(repository: AvailabilityRepository, build: AppBuild) =
        CheckAppAvailability(repository, build)

    @Factory fun dismiss(repository: AvailabilityRepository) = DismissRecommendedUpdate(repository)

    @Single fun refresh(flags: FeatureFlagRepository) = RefreshAvailability(flags)
}
