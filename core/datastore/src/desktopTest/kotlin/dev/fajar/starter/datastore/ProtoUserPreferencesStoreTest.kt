package dev.fajar.starter.datastore

import androidx.datastore.core.CorruptionException
import dev.fajar.starter.datastore.proto.ThemeMode
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class ProtoUserPreferencesStoreTest {
    @Test
    fun updatesAreAtomicAndObservedAsObjects() = runTest {
        val directory = Files.createTempDirectory("fluent-preferences").toFile()
        val store = createUserPreferences(directory)
        try {
            assertEquals(ThemeMode.SYSTEM, store.data.first().theme_mode)
            listOf(
                    async { store.update { it.copy(onboarding_completed = true) } },
                    async { store.update { it.copy(theme_mode = ThemeMode.DARK) } },
                )
                .awaitAll()
            val updated =
                store.data.first { it.onboarding_completed && it.theme_mode == ThemeMode.DARK }
            assertTrue(updated.onboarding_completed)
            assertTrue(directory.resolve("user_preferences.pb").length() > 0)
        } finally {
            store.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun corruptFilesFailInsteadOfSilentlyResettingPreferences() = runTest {
        val directory = Files.createTempDirectory("fluent-preferences").toFile()
        val bytes = byteArrayOf(10, 5, 1)
        directory.resolve("user_preferences.pb").writeBytes(bytes)
        val store = createUserPreferences(directory)
        try {
            assertFailsWith<CorruptionException> { store.data.first() }
            assertTrue(bytes.contentEquals(directory.resolve("user_preferences.pb").readBytes()))
        } finally {
            store.close()
            directory.deleteRecursively()
        }
    }
}
