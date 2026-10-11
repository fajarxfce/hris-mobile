package dev.fajar.starter.securestorage

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class MemoryCredentialStoreTest {
    @Test
    fun ephemeralCredentialsNeverSurviveANewOwnerAndCanBeCleared() = runTest {
        val store = MemoryCredentialStore()
        assertFalse(store.persistent)
        store.write("opaque")
        assertEquals("opaque", store.read())
        assertNull(MemoryCredentialStore().read())
        store.write(null)
        assertNull(store.read())
    }

    @Test
    fun competingCreationRetainsOneValueAndCancellationCannotReplaceIt() = runTest {
        val store = MemoryCredentialStore()
        val values = listOf("first", "second").map { async { store.writeIfAbsent(it) } }.awaitAll()
        assertEquals(1, values.distinct().size)
        assertEquals(values.first(), store.read())
        val cancelled = Job().apply { cancel() }
        assertFailsWith<CancellationException> {
            withContext(cancelled) { store.writeIfAbsent("late") }
        }
        assertEquals(values.first(), store.writeIfAbsent("replacement"))
    }
}
