@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.availability.domain

import dev.fajar.starter.availability.domain.entities.AppPolicy
import dev.fajar.starter.availability.domain.repositories.AvailabilityRepository
import dev.fajar.starter.availability.domain.usecases.*
import dev.fajar.starter.common.config.*
import dev.fajar.starter.common.result.AppResult
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*

class AvailabilityCancellationTest {
    @Test
    fun cancellationDuringStorageReadPreventsDismissalOrPublication() = runTest {
        val pending = CompletableDeferred<Unit>()
        var writes = 0
        val repository =
            object : AvailabilityRepository {
                override fun observe() = flowOf(AppResult.Success(AppPolicy()))

                override suspend fun current(): AppResult<AppPolicy> {
                    withContext(NonCancellable) { pending.await() }
                    return AppResult.Success(AppPolicy(recommendedBuild = 2))
                }

                override suspend fun dismiss(recommendedBuild: Long): AppResult<Unit> {
                    writes++
                    return AppResult.Success(Unit)
                }
            }
        var published = false
        val check = launch {
            CheckAppAvailability(repository, AppBuild(AppPlatform.Android, 1, "1.0", null))()
            published = true
        }
        val dismiss = launch { DismissRecommendedUpdate(repository)(2) }
        runCurrent()
        check.cancel()
        dismiss.cancel()
        pending.complete(Unit)
        joinAll(check, dismiss)
        assertEquals(0, writes)
        assertFalse(published)
    }
}
