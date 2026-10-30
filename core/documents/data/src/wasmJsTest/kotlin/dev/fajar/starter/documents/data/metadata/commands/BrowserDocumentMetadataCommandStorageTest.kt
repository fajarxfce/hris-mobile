@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.documents.data.metadata.commands

import dev.fajar.starter.database.createAppDatabase
import kotlin.test.Test
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest

class BrowserDocumentMetadataCommandStorageTest {
    @Test
    fun restartAndOriginalReceipt() = runTest {
        withDatabase(::checkMetadataCommandRestartAndOriginalReceipt)
    }

    @Test
    fun exactWritesAndResourceSlot() = runTest {
        withDatabase(::checkMetadataCommandExactWritesAndResourceSlot)
    }

    @Test
    fun accountIsolationAndSharedCapacity() = runTest {
        withDatabase(::checkMetadataCommandAccountIsolationAndSharedCapacity)
    }

    @Test
    fun encryptionKeyLossAndUnicode() = runTest {
        withDatabase(::checkMetadataCommandEncryptionKeyLossAndUnicode)
    }

    @Test
    fun tamperedCiphertextAndBounds() = runTest {
        withDatabase(::checkMetadataCommandTamperedCiphertextAndBounds)
    }

    @Test
    fun cancellationAndLateAccount() = runTest {
        withDatabase(::checkMetadataCommandCancellationAndLateAccount)
    }
}

private suspend fun withDatabase(block: suspend (MetadataCommandStorageFixture) -> Unit) {
    val name = "metadata-command-${Uuid.random()}"
    val fixture = MetadataCommandStorageFixture { createAppDatabase(name) }
    try {
        fixture.activate()
        block(fixture)
    } finally {
        fixture.close()
    }
}
