package dev.fajar.starter.onboarding.domain.repositories

import dev.fajar.starter.common.result.AppResult

interface OnboardingRepository {
    suspend fun isComplete(): AppResult<Boolean>

    suspend fun complete(): AppResult<Unit>
}
