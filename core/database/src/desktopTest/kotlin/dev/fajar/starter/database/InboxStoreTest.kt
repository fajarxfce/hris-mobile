package dev.fajar.starter.database

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class InboxStoreTest {
    @Test
    fun inboxPersistsOrdersUpsertsAndMarksRead() = runTest {
        val directory = Files.createTempDirectory("fluent-inbox").toFile()
        var database = createAppDatabase(directory)
        var store = database.inbox
        try {
            assertTrue(store.observe().first().isEmpty())
            store.upsert(InboxRecord("a", "First", "Body", "inbox", 1))
            store.upsert(InboxRecord("b", "Second", "Body", "inbox", 2))
            store.upsert(InboxRecord("a", "Updated", "Body", "inbox", 1))
            assertEquals(listOf("b", "a"), store.observe().first().map { it.id })
            store.markRead("a")
            database.close()
            database = createAppDatabase(directory)
            store = database.inbox
            assertTrue(store.observe().first().single { it.id == "a" }.read)
            assertEquals("Updated", store.observe().first().single { it.id == "a" }.title)
            store.clear()
            assertTrue(store.observe().first().isEmpty())
        } finally {
            database.close()
            directory.deleteRecursively()
        }
    }
}
