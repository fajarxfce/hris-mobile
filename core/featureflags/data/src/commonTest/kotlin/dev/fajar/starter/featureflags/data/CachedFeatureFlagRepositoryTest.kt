@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.featureflags.data

import dev.fajar.starter.common.result.*
import dev.fajar.starter.datastore.UserPreferencesStore
import dev.fajar.starter.datastore.proto.*
import dev.fajar.starter.featureflags.data.datasources.RemoteFeatureFlagSource
import dev.fajar.starter.featureflags.data.errors.*
import dev.fajar.starter.featureflags.data.repositories.CachedFeatureFlagRepository
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class CachedFeatureFlagRepositoryTest {
    @Test
    fun refreshIsAtomicAndPreservesOverridesAndOtherPreferences() = runTest {
        val local = MemoryPreferences()
        local.value.value =
            UserPreferences(
                onboarding_completed = true,
                theme_mode = ThemeMode.DARK,
                feature_flag_overrides = mapOf("save" to false),
            )
        val repository = CachedFeatureFlagRepository(Remote { mapOf("save" to "true") }, local)
        assertEquals(AppResult.Success(Unit), repository.refresh(123))
        val stored = local.value.value
        assertEquals(mapOf("save" to "true"), stored.feature_flag_values)
        assertEquals(mapOf("save" to false), stored.feature_flag_overrides)
        assertTrue(stored.onboarding_completed)
        assertEquals(ThemeMode.DARK, stored.theme_mode)
        assertEquals(123, stored.feature_flags_fetched_at)
        assertEquals(
            stored.feature_flag_values,
            (repository.observe().first() as AppResult.Success).value.remoteValues,
        )
    }

    @Test
    fun failedFetchPreservesTheWholeLastSnapshot() = runTest {
        val local = MemoryPreferences()
        val before =
            UserPreferences(
                feature_flag_values = mapOf("save" to "false"),
                feature_flags_fetched_at = 22,
            )
        local.value.value = before
        val repository = CachedFeatureFlagRepository(Remote { error("connection") }, local)
        assertEquals(
            FailureKind.Service,
            (repository.refresh(100) as AppResult.Failed).failure.kind,
        )
        assertEquals(before, local.value.value)
    }

    @Test
    fun aSuccessfulEmptyTemplateRemovesDeletedRemoteParameters() = runTest {
        val local = MemoryPreferences()
        local.value.value = UserPreferences(feature_flag_values = mapOf("removed" to "true"))
        val repository = CachedFeatureFlagRepository(Remote { emptyMap() }, local)
        repository.refresh(100)
        assertTrue(local.value.value.feature_flag_values.isEmpty())
        assertEquals(100, local.value.value.feature_flags_fetched_at)
    }

    @Test
    fun aLateSdkResultAfterCancellationCannotReplaceTheCache() = runTest {
        val local = MemoryPreferences()
        val response = CompletableDeferred<Map<String, String>>()
        val repository =
            CachedFeatureFlagRepository(
                Remote { withContext(NonCancellable) { response.await() } },
                local,
            )
        val request = launch { repository.refresh(100) }
        runCurrent()
        request.cancel()
        response.complete(mapOf("save" to "false"))
        request.join()
        assertTrue(request.isCancelled)
        assertEquals(UserPreferences(), local.value.value)
    }

    @Test
    fun overrideEditsDuringFetchAreNotLostAndNullRestoresRemoteEvaluation() = runTest {
        val local = MemoryPreferences()
        val response = CompletableDeferred<Map<String, String>>()
        val repository = CachedFeatureFlagRepository(Remote { response.await() }, local)
        val request = launch { repository.refresh(100) }
        runCurrent()
        repository.setOverride("save", false)
        response.complete(mapOf("save" to "true"))
        request.join()
        assertEquals(mapOf("save" to false), local.value.value.feature_flag_overrides)
        repository.setOverride("save", null)
        assertTrue(local.value.value.feature_flag_overrides.isEmpty())
        assertEquals(mapOf("save" to "true"), local.value.value.feature_flag_values)
    }

    @Test
    fun storageFailuresKeepTheirClassification() = runTest {
        val broken =
            object : UserPreferencesStore {
                override val data: Flow<UserPreferences> = flow { error("read") }

                override suspend fun update(transform: (UserPreferences) -> UserPreferences) {
                    error("write")
                }

                override fun close() = Unit
            }
        val repository = CachedFeatureFlagRepository(Remote { emptyMap() }, broken)
        assertEquals(FailureKind.Storage, (repository.snapshot() as AppResult.Failed).failure.kind)
        assertEquals(
            FailureKind.Storage,
            (repository.refresh(100) as AppResult.Failed).failure.kind,
        )
        assertEquals(
            FailureKind.Storage,
            (repository.observe().first() as AppResult.Failed).failure.kind,
        )
    }

    @Test
    fun safeBoundaryPreservesDiagnosticsAndNeverConsumesCancellation() = runTest {
        val error = RemoteConfigUnavailableException()
        var captured: Exception? = null
        val result = safeRemoteConfigCall(onException = { captured = it }) { throw error }
        assertSame(error, captured)
        assertEquals(FailureKind.Unavailable, (result as AppResult.Failed).failure.kind)
        assertFailsWith<CancellationException> {
            safeRemoteConfigCall { throw CancellationException() }
        }
    }
}

private class Remote(private val response: suspend () -> Map<String, String>) :
    RemoteFeatureFlagSource {
    override suspend fun fetch() = response()
}

private class MemoryPreferences : UserPreferencesStore {
    val value = MutableStateFlow(UserPreferences())
    override val data = value

    override suspend fun update(transform: (UserPreferences) -> UserPreferences) {
        value.value = transform(value.value)
    }

    override fun close() = Unit
}
