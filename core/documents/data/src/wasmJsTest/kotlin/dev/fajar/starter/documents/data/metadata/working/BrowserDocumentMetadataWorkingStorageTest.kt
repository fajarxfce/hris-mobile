@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.documents.data.metadata.working

import dev.fajar.starter.database.createAppDatabase
import dev.fajar.starter.documents.data.metadata.DocumentMetadataStorageFixture
import kotlin.test.Test
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest

class BrowserDocumentMetadataWorkingStorageTest {
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
    val name = "metadata-working-${Uuid.random()}"
    val fixture = DocumentMetadataStorageFixture { createAppDatabase(name) }
    try {
        fixture.initialize()
        block(fixture)
    } finally {
        fixture.close()
    }
}
