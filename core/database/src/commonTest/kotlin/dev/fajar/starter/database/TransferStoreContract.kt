package dev.fajar.starter.database

import kotlin.test.*
import kotlinx.coroutines.flow.first

suspend fun verifyTransferTransactions(database: AppDatabase) {
    database.accounts.activate("transfers-a")
    val store = database.transfers
    val initial = TransferRecord("file", "Upload", "report.txt", "text/plain", 8, 1)
    assertTrue(store.create("transfers-a", initial, 8, 1))
    assertFalse(store.create("transfers-a", initial.copy(id = "overflow"), 8, 1))
    assertTrue(
        store.replace(
            "transfers-a",
            0,
            initial.copy(storedBytes = 4),
            TransferChunkRecord(0, byteArrayOf(1, 2, 3, 4)),
        )
    )
    assertFalse(
        store.replace(
            "transfers-a",
            0,
            initial.copy(storedBytes = 8),
            TransferChunkRecord(4, byteArrayOf(5, 6, 7, 8)),
        )
    )
    assertContentEquals(byteArrayOf(1, 2, 3, 4), store.chunk("transfers-a", "file", 3)!!.bytes)
    assertEquals(0, store.chunk("transfers-a", "file", 5)!!.offset)
    assertFails {
        store.replace(
            "transfers-a",
            1,
            initial.copy(storedBytes = 8),
            TransferChunkRecord(7, byteArrayOf(1, 2)),
        )
    }
    assertEquals(4, store.get("transfers-a", "file")!!.storedBytes)
    assertFails { store.replace("transfers-a", 1, initial.copy(size = 1000)) }
    assertEquals(8, store.get("transfers-a", "file")!!.size)
    assertTrue(
        store.replace(
            "transfers-a",
            1,
            initial.copy(storedBytes = 8),
            TransferChunkRecord(4, byteArrayOf(5, 6, 7, 8)),
        )
    )
    assertFalse(store.remove("transfers-a", "file", 1))
    database.accounts.activate("transfers-b")
    assertTrue(store.observe("transfers-b").first().isEmpty())
    assertNull(store.chunk("transfers-a", "file", 0))
    assertNull(store.chunk("transfers-b", "file", 0))
    assertFalse(store.replace("transfers-a", 2, initial))
    assertFalse(store.create("transfers-a", initial, 8, 1))
    assertTrue(store.create("transfers-b", initial, 8, 1))
    assertTrue(
        store.replace(
            "transfers-b",
            0,
            initial.copy(storedBytes = 4),
            TransferChunkRecord(0, byteArrayOf(1, 2, 3, 4)),
        )
    )
    assertTrue(store.remove("transfers-b", "file", 1))
    assertNull(store.chunk("transfers-b", "file", 0))
}
