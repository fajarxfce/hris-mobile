package dev.fajar.starter.database

import java.io.File
import java.nio.file.Files
import kotlin.test.*
import kotlinx.coroutines.test.runTest

class DeviceRecordStoreTest {
    @Test
    fun retentionDeduplicatesAndExactCleanupPreservesReplacement() = runTest {
        withDatabase { verifyDeviceRecordRetention(it.deviceRecords) }
    }

    @Test
    fun concurrentInsertionRespectsGlobalCapacity() = runTest {
        withDatabase { verifyDeviceRecordCapacity(it.deviceRecords) }
    }

    @Test
    fun orderedPagesRemainBoundedAndSeparateNamespaces() = runTest {
        withDatabase { verifyDeviceRecordPages(it.deviceRecords) }
    }

    @Test
    fun deviceRecordsSurviveAccountReplacement() = runTest {
        withDatabase { verifyDeviceRecordOwnership(it) }
    }

    @Test
    fun cancelledOperationsCannotReadOrMutateRecords() = runTest {
        withDatabase { verifyDeviceRecordCancellation(it.deviceRecords) }
    }

    @Test
    fun boundsAndRedactedDiagnosticsProtectTheRawBoundary() = runTest {
        withDatabase { verifyDeviceRecordBounds(it.deviceRecords) }
    }

    @Test
    fun concurrentOwnersRetainOneOriginalAndStaleRemovalCannotEraseReplacement() = runTest {
        withLocation { location ->
            val first = open(location)
            val second = open(location)
            try {
                verifyDeviceRecordConcurrentIdentity(first.deviceRecords, second.deviceRecords)
            } finally {
                first.close()
                second.close()
            }
        }
    }

    @Test
    fun deviceRecordsSurviveDatabaseReopenAndExactRemoval() = runTest {
        withLocation { location ->
            var database = open(location)
            val record = DeviceRecord("input", "original", "first", "sealed-original")
            try {
                assertEquals(record, database.deviceRecords.retain(record, 1))
                database.close()
                database = open(location)
                assertEquals(record, database.deviceRecords.get(record.namespace, record.id))
                assertEquals(
                    record,
                    database.deviceRecords.retain(record.copy(payload = "new-ciphertext"), 1),
                )
                assertTrue(database.deviceRecords.remove(record))
                database.close()
                database = open(location)
                assertNull(database.deviceRecords.get(record.namespace, record.id))
                assertTrue(database.deviceRecords.page(record.namespace).isEmpty())
            } finally {
                database.close()
            }
        }
    }

    private suspend fun withDatabase(block: suspend (AppDatabase) -> Unit) {
        withLocation { location ->
            val database = open(location)
            try {
                block(database)
            } finally {
                database.close()
            }
        }
    }
}

private fun open(location: String) = createAppDatabase(File(location))

private suspend fun withLocation(block: suspend (String) -> Unit) {
    val directory = Files.createTempDirectory("device-records").toFile()
    try {
        block(directory.absolutePath)
    } finally {
        directory.deleteRecursively()
    }
}
