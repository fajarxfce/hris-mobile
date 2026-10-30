package dev.fajar.starter.documents.data.replica

import dev.fajar.starter.database.createAppDatabase
import java.nio.file.Files
import kotlin.test.Test
import kotlinx.coroutines.test.runTest

class DocumentCatalogReplicaTest {
    @Test
    fun businessMetadataUpgradeRetainsOldEncryptedFactsAcrossRestart() = runTest {
        withDatabase(::checkDocumentBusinessMetadataUpgradeAcrossRestart)
    }

    @Test
    fun pendingDenialSurvivesNewLogin() = runTest {
        withDatabase(::checkCatalogPendingDenialSurvivesReauthentication)
    }

    @Test
    fun readDenialSurvivesRestart() = runTest {
        withDatabase(::checkCatalogReadDenialSurvivesRepositoryAndSessionReplacement)
    }

    @Test
    fun failedAdmissionStorageRemainsClosed() = runTest {
        withDatabase(::checkCatalogReadDenialRemainsVisibleWhenStorageFails)
    }

    @Test
    fun lateAdmissionCannotReopenLaterDenial() = runTest {
        withDatabase(::checkCatalogReadDenialCannotBeClearedByAnOlderPendingAdmission)
    }

    @Test
    fun localPagesAndOwnerSurviveRestart() = runTest {
        withDatabase(::checkCatalogPagesRestartAndOwnership)
    }

    @Test
    fun versionsWithdrawalAndReplacementAreAtomic() = runTest {
        withDatabase(::checkCatalogVersionsWithdrawalAndReplacement)
    }

    @Test
    fun plaintextAndIndexesAreNotLeaked() = runTest {
        withDatabase(::checkCatalogEncryptionIndexesAndLostKey)
    }

    @Test
    fun cancellationCannotCommit() = runTest {
        withDatabase(::checkCatalogCancellationDuringEncryption)
    }

    @Test
    fun onlySelectedPageIsDecrypted() = runTest {
        withDatabase(::checkCatalogReadsOnlyTheSelectedIndexedPage)
    }

    @Test
    fun emptySnapshotDoesNotCreateAKey() = runTest {
        withDatabase(::checkEmptyCatalogDoesNotCreateAKey)
    }
}

private suspend fun withDatabase(block: suspend (DocumentCatalogFixture) -> Unit) {
    val directory = Files.createTempDirectory("document-catalog-replica").toFile()
    val fixture = DocumentCatalogFixture { createAppDatabase(directory) }
    try {
        fixture.activate()
        block(fixture)
    } finally {
        fixture.close()
        directory.deleteRecursively()
    }
}
