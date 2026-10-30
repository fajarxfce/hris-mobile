package dev.fajar.starter.documents.data.metadata

import dev.fajar.starter.database.createAppDatabase
import java.nio.file.Files
import kotlin.test.Test
import kotlinx.coroutines.test.runTest

class DocumentMetadataStorageTest {
    @Test
    fun tamperedOwnershipAndRowsCannotDecode() = runTest {
        withDatabase(::checkMetadataTamperedOwnershipAndRowsCannotDecode)
    }

    @Test
    fun metadataRestartPagingAndOwnership() = runTest {
        withDatabase(::checkMetadataRestartPagingAndOwnership)
    }

    @Test
    fun metadataExactWritesAndImmutableHistory() = runTest {
        withDatabase(::checkMetadataExactWritesAndImmutableHistory)
    }

    @Test
    fun metadataNoHistoryGapOrRegressingBoundary() = runTest {
        withDatabase(::checkMetadataNoHistoryGapOrRegressingBoundary)
    }

    @Test
    fun metadataEncryptionLostKeyAndSelectedPage() = runTest {
        withDatabase(::checkMetadataEncryptionLostKeyAndSelectedPage)
    }

    @Test
    fun metadataCancelledEncryptionCannotCommit() = runTest {
        withDatabase(::checkMetadataCancelledEncryptionCannotCommit)
    }

    @Test
    fun metadataLateAccountCannotCommit() = runTest {
        withDatabase(::checkMetadataLateAccountCannotCommit)
    }

    @Test
    fun metadataMaximumHistoryRemainsPaged() = runTest {
        withDatabase(::checkMetadataMaximumHistoryRemainsPaged)
    }

    @Test
    fun metadataAdmissionRestartAndResourceIsolation() = runTest {
        withDatabase(::checkMetadataAdmissionRestartAndResourceIsolation)
    }

    @Test
    fun metadataPendingAndFailedAdmissionWritesRemainClosed() = runTest {
        withDatabase(::checkMetadataPendingAndFailedAdmissionWritesRemainClosed)
    }

    @Test
    fun metadataOlderAdmissionCannotClearLaterDenial() = runTest {
        withDatabase(::checkMetadataOlderAdmissionCannotClearLaterDenial)
    }
}

private suspend fun withDatabase(block: suspend (DocumentMetadataStorageFixture) -> Unit) {
    val directory = Files.createTempDirectory("document-metadata").toFile()
    val fixture = DocumentMetadataStorageFixture { createAppDatabase(directory) }
    try {
        fixture.initialize()
        block(fixture)
    } finally {
        fixture.close()
        directory.deleteRecursively()
    }
}
