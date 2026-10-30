@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.documents.data.metadata

import dev.fajar.starter.database.createAppDatabase
import kotlin.test.Test
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest

class BrowserDocumentMetadataStorageTest {
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
    val name = "document-metadata-${Uuid.random()}"
    val fixture = DocumentMetadataStorageFixture { createAppDatabase(name) }
    try {
        fixture.initialize()
        block(fixture)
    } finally {
        fixture.close()
    }
}
