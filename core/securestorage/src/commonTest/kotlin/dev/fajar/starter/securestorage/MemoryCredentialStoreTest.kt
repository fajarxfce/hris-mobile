package dev.fajar.starter.securestorage

import kotlin.test.*
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
}
