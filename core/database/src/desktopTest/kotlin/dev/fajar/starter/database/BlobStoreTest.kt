package dev.fajar.starter.database

import java.nio.file.Files
import kotlin.test.*
import kotlinx.coroutines.test.runTest

class BlobStoreTest {
    @Test
    fun checkpointsPreserveContentAndRejectObsoleteOwners() = runTest {
        withBlobDatabase(::verifyBlobCheckpoints)
    }

    @Test
    fun progressAndBlocksCommitTogether() = runTest { withBlobDatabase(::verifyBlobAtomicity) }

    @Test
    fun accountChangesHideRetainedFilesAndRejectLateWrites() = runTest {
        withBlobDatabase(::verifyBlobOwnership)
    }

    @Test
    fun fileBlockAndEnvelopeBoundsRejectPartialWrites() = runTest {
        withBlobDatabase(::verifyBlobBounds)
    }

    @Test
    fun capacityIsSharedAcrossPartitionsAndPagesAreBounded() = runTest {
        withBlobDatabase(::verifyBlobQuotaAndPages)
    }

    @Test
    fun declaredBytesReserveGlobalCapacityBeforeImport() = runTest {
        withBlobDatabase(::verifyBlobGlobalReservation)
    }

    @Test
    fun abandonedAccountsCannotGrowTheHeaderCountWithoutBound() = runTest {
        withBlobDatabase(::verifyBlobGlobalItemLimit)
    }

    @Test
    fun cancelledWritesLeaveCompleteTransactions() = runTest {
        withBlobDatabase(::verifyBlobCancellation)
    }

    @Test
    fun independentConnectionsCannotOverbookTheSameCapacity() = runTest {
        val directory = Files.createTempDirectory("blob-concurrent").toFile()
        val first = createAppDatabase(directory)
        val second = createAppDatabase(directory)
        try {
            verifyBlobCapacityAcrossConnections(first, second)
        } finally {
            first.close()
            second.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun restartRetainsCompletedContentForTheSameAccount() = runTest {
        val directory = Files.createTempDirectory("blob-restart").toFile()
        var database = createAppDatabase(directory)
        val scope = BlobScope("session", "account")
        val original = BlobRecord("company/files", "document", 3, "sealed-metadata")
        val block = BlobChunkRecord(0, 3, "sealed-content")
        try {
            database.accounts.activate(scope.sessionId, scope.accountKey)
            assertEquals(BlobInsertResult.Stored, database.blobs.insert(scope, original, 100, 10))
            assertTrue(database.blobs.append(scope, original.partition, original.id, 0, block))
            assertTrue(
                database.blobs.complete(scope, original.partition, original.id, 1, "sealed-proof")
            )
            assertTrue(
                database.blobs.updateState(
                    scope,
                    original.partition,
                    original.id,
                    2,
                    "sealed-delivery",
                )
            )
            database.close()
            database = createAppDatabase(directory)
            val resumed = scope.copy(sessionId = "replacement-login")
            database.accounts.activate(resumed.sessionId, resumed.accountKey)
            assertEquals(
                original.copy(
                    storedBytes = 3,
                    completion = "sealed-proof",
                    revision = 3,
                    state = "sealed-delivery",
                ),
                database.blobs.get(resumed, original.partition, original.id),
            )
            assertEquals(block, database.blobs.chunk(resumed, original.partition, original.id, 0))
            assertNull(database.blobs.chunk(scope, original.partition, original.id, 0))
        } finally {
            database.close()
            directory.deleteRecursively()
        }
    }
}

private suspend fun withBlobDatabase(block: suspend (AppDatabase) -> Unit) {
    val directory = Files.createTempDirectory("blob-contract").toFile()
    val database = createAppDatabase(directory)
    try {
        block(database)
    } finally {
        database.close()
        directory.deleteRecursively()
    }
}
