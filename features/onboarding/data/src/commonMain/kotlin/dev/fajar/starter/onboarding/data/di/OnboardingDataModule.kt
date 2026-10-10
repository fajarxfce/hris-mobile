package dev.fajar.starter.onboarding.data.di

import dev.fajar.starter.onboarding.domain.repositories.OnboardingRepository
import dev.fajar.starter.onboarding.domain.usecases.CompleteOnboarding
import dev.fajar.starter.onboarding.domain.usecases.LoadOnboarding
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Module

@Module
@ComponentScan("dev.fajar.starter.onboarding.data")
class OnboardingDataModule {
    @Factory fun load(repository: OnboardingRepository) = LoadOnboarding(repository)

    @Factory fun complete(repository: OnboardingRepository) = CompleteOnboarding(repository)
}
