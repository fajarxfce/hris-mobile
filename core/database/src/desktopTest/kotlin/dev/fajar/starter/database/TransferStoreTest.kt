package dev.fajar.starter.database

import java.nio.file.Files
import kotlin.test.*
import kotlinx.coroutines.test.runTest

class TransferStoreTest {
    @Test
    fun transactionsAndReopen() = runTest {
        val directory = Files.createTempDirectory("transfer-contract").toFile()
        var database = createAppDatabase(directory)
        try {
            verifyTransferTransactions(database)
            database.accounts.activate("resume")
            val row = TransferRecord("resume", "Download", "example", "text/plain", 3, 1)
            assertTrue(database.transfers.create("resume", row, 100, 10))
            assertTrue(
                database.transfers.replace(
                    "resume",
                    0,
                    row.copy(offset = 3, storedBytes = 3),
                    TransferChunkRecord(0, byteArrayOf(1, 2, 3)),
                )
            )
            database.close()
            database = createAppDatabase(directory)
            assertEquals(3, database.transfers.get("resume", "resume")!!.offset)
            assertContentEquals(
                byteArrayOf(1, 2, 3),
                database.transfers.chunk("resume", "resume", 0)!!.bytes,
            )
        } finally {
            database.close()
            directory.deleteRecursively()
        }
    }
}
