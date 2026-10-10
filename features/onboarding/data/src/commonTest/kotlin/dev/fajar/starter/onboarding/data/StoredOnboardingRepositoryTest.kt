package dev.fajar.starter.onboarding.data

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.common.result.FailureKind
import dev.fajar.starter.datastore.UserPreferencesStore
import dev.fajar.starter.datastore.proto.UserPreferences
import dev.fajar.starter.onboarding.data.repositories.StoredOnboardingRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

class StoredOnboardingRepositoryTest {
    @Test
    fun failedWriteDoesNotReportOnboardingCompleted() = runTest {
        val storage =
            object : UserPreferencesStore {
                override val data = flowOf(UserPreferences())

                override suspend fun update(transform: (UserPreferences) -> UserPreferences) {
                    throw IllegalStateException("storage unavailable")
                }

                override fun close() = Unit
            }
        val repository = StoredOnboardingRepository(storage)
        assertEquals(
            FailureKind.Storage,
            assertIs<AppResult.Failed>(repository.complete()).failure.kind,
        )
        assertEquals(false, assertIs<AppResult.Success<Boolean>>(repository.isComplete()).value)
    }
}
