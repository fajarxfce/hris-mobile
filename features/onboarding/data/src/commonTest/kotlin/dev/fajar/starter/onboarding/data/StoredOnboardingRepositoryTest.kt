package dev.fajar.starter.onboarding.data

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.common.result.FailureKind
import dev.fajar.starter.onboarding.data.repositories.StoredOnboardingRepository
import dev.fajar.starter.storage.PreferenceStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest

class StoredOnboardingRepositoryTest {
    @Test
    fun rejectedWriteDoesNotReportOnboardingCompleted() = runTest {
        val storage =
            object : PreferenceStore {
                override suspend fun readBoolean(key: String): Boolean? = null

                override suspend fun writeBoolean(key: String, value: Boolean) = false
            }
        val repository = StoredOnboardingRepository(storage)
        assertEquals(
            FailureKind.Storage,
            assertIs<AppResult.Failed>(repository.complete()).failure.kind,
        )
        assertEquals(false, assertIs<AppResult.Success<Boolean>>(repository.isComplete()).value)
    }
}
