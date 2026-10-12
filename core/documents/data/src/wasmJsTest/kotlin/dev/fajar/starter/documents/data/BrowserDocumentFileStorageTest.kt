@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.fajar.starter.documents.data

import dev.fajar.starter.database.AppDatabase
import dev.fajar.starter.database.createAppDatabase
import kotlin.js.Promise
import kotlin.random.Random
import kotlin.test.Test
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest

class BrowserDocumentFileStorageTest {
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
    val namespace = "document-files-" + Random.nextInt()
    val database = createAppDatabase(namespace)
    try {
        block(database)
    } finally {
        database.close()
        deleteDatabase(namespace + ".database").await<JsAny?>()
    }
}

@JsFun(
    """(name) => new Promise((resolve, reject) => {
    const request = indexedDB.deleteDatabase(name);
    request.onsuccess = () => resolve(null); request.onerror = () => reject(request.error);
    request.onblocked = () => reject(new Error('Document database still has an owner.'));
})"""
)
private external fun deleteDatabase(name: String): Promise<JsAny?>
