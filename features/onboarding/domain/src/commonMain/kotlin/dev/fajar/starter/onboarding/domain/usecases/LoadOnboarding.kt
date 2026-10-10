package dev.fajar.starter.onboarding.domain.usecases

import dev.fajar.starter.onboarding.domain.repositories.OnboardingRepository

class LoadOnboarding(private val repository: OnboardingRepository) {
    suspend operator fun invoke() = repository.isComplete()
}
