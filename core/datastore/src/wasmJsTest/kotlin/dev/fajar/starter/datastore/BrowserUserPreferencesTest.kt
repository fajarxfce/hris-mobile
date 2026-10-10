@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.datastore

import androidx.datastore.core.CorruptionException
import dev.fajar.starter.datastore.proto.UserPreferences
import kotlin.test.*
import kotlinx.browser.localStorage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class BrowserUserPreferencesTest {
    private val namespace = "fluent-starter.browser-test"
    private val key = "$namespace.user_preferences.pb"

    @AfterTest
    fun cleanup() {
        localStorage.removeItem(key)
    }

    @Test
    fun updatesAreObservedAndPersistAcrossOwners() = runTest {
        val store = createUserPreferences(namespace)
        val values = mutableListOf<UserPreferences>()
        val observation =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                store.data.toList(values)
            }
        assertFalse(values.last().onboarding_completed)
        store.update {
            it.copy(
                onboarding_completed = true,
                feature_flag_values = mapOf("save" to "true"),
                feature_flag_overrides = mapOf("save" to false),
                feature_flags_fetched_at = 123,
            )
        }
        runCurrent()
        assertTrue(values.last().onboarding_completed)
        store.close()
        runCurrent()
        assertTrue(observation.isCompleted)
        val reopened = createUserPreferences(namespace)
        try {
            val persisted = reopened.data.first()
            assertTrue(persisted.onboarding_completed)
            assertEquals(mapOf("save" to "true"), persisted.feature_flag_values)
            assertEquals(mapOf("save" to false), persisted.feature_flag_overrides)
            assertEquals(123, persisted.feature_flags_fetched_at)
        } finally {
            reopened.close()
        }
    }

    @Test
    fun corruptionIsPreservedAndReported() = runTest {
        localStorage.setItem(key, "invalid-protobuf")
        val store = createUserPreferences(namespace)
        try {
            assertFailsWith<CorruptionException> { store.data.first() }
            assertEquals("invalid-protobuf", localStorage.getItem(key))
        } finally {
            store.close()
        }
    }

    @Test
    fun failedOrCancelledTransformsNeverPublishTheNewValue() = runTest {
        val store = createUserPreferences(namespace)
        try {
            store.data.first()
            val before = localStorage.getItem(key)
            assertFailsWith<IllegalStateException> { store.update { error("transform") } }
            assertEquals(before, localStorage.getItem(key))
            val cancelled = launch {
                store.update {
                    run {
                        cancel()
                        it.copy(onboarding_completed = true)
                    }
                }
            }
            cancelled.join()
            assertEquals(before, localStorage.getItem(key))
        } finally {
            store.close()
        }
    }
}
