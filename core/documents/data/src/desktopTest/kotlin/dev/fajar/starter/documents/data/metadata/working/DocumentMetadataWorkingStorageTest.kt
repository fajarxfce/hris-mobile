package dev.fajar.starter.documents.data.metadata.working

import dev.fajar.starter.database.createAppDatabase
import dev.fajar.starter.documents.data.metadata.DocumentMetadataStorageFixture
import java.nio.file.Files
import kotlin.test.Test
import kotlinx.coroutines.test.runTest

class DocumentMetadataWorkingStorageTest {
    @Test
    fun restartAndReviewedHeader() = runTest {
        withDatabase(::checkMetadataWorkingRestartAndReviewedHeader)
    }

    @Test
    fun exactWritesAndExplicitDiscard() = runTest {
        withDatabase(::checkMetadataWorkingExactWritesAndExplicitDiscard)
    }

    @Test
    fun boundedIndexedPages() = runTest { withDatabase(::checkMetadataWorkingBoundedIndexedPages) }

    @Test
    fun keyLossAndAccountIsolation() = runTest {
        withDatabase(::checkMetadataWorkingKeyLossAndAccountIsolation)
    }

    @Test
    fun tamperingAndEnvelopeBounds() = runTest {
        withDatabase(::checkMetadataWorkingTamperingAndEnvelopeBounds)
    }

    @Test
    fun cancellationAndLateAccount() = runTest {
        withDatabase(::checkMetadataWorkingCancellationAndLateAccount)
    }
}

private suspend fun withDatabase(block: suspend (DocumentMetadataStorageFixture) -> Unit) {
    val directory = Files.createTempDirectory("metadata-working").toFile()
    val fixture = DocumentMetadataStorageFixture { createAppDatabase(directory) }
    try {
        fixture.initialize()
        block(fixture)
    } finally {
        fixture.close()
        directory.deleteRecursively()
    }
}
