package dev.fajar.starter.documents.data

import dev.fajar.starter.database.AppDatabase
import dev.fajar.starter.database.createAppDatabase
import java.nio.file.Files
import kotlin.test.Test
import kotlinx.coroutines.test.runTest

class DocumentFileStorageTest {
    @Test
    fun uploadCheckpointsAreImmutableEncryptedAndRevisionBound() = runTest {
        withDatabase(::verifyDocumentUploadCheckpoint)
    }

    @Test
    fun uploadReadsAtMostOneAuthenticatedChunk() = runTest {
        withDatabase(::verifyBoundedDocumentUploadChunks)
    }

    @Test
    fun encryptedImportIsBoundedAndRecoverable() = runTest {
        withDatabase(::verifyEncryptedDocumentImport)
    }

    @Test
    fun filesRemainAccountAndCompanyBound() = runTest {
        withDatabase(::verifyDocumentFileOwnership)
    }

    @Test
    fun cancellationDistinguishesPartialAndCommittedFiles() = runTest {
        withDatabase(::verifyDocumentImportCancellation)
    }

    @Test
    fun staleCleanupCannotDeleteACompletedFile() = runTest {
        withDatabase(::verifyDocumentCleanupRace)
    }

    @Test
    fun invalidExtentCapacityAndKeyLossNeverPublishAFile() = runTest {
        withDatabase(::verifyDocumentImportBoundsAndKeyLoss)
    }
}

private suspend fun withDatabase(block: suspend (AppDatabase) -> Unit) {
    val directory = Files.createTempDirectory("document-files").toFile()
    val database = createAppDatabase(directory)
    try {
        block(database)
    } finally {
        database.close()
        directory.deleteRecursively()
    }
}
