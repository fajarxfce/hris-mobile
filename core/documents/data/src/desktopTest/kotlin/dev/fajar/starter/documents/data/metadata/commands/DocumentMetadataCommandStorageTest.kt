package dev.fajar.starter.documents.data.metadata.commands

import dev.fajar.starter.database.createAppDatabase
import java.nio.file.Files
import kotlin.test.Test
import kotlinx.coroutines.test.runTest

class DocumentMetadataCommandStorageTest {
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
    val directory = Files.createTempDirectory("metadata-command").toFile()
    val fixture = MetadataCommandStorageFixture { createAppDatabase(directory) }
    try {
        fixture.activate()
        block(fixture)
    } finally {
        fixture.close()
        directory.deleteRecursively()
    }
}
