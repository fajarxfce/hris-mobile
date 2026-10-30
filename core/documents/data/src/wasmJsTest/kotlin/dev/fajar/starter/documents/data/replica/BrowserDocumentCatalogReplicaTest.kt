@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.documents.data.replica

import dev.fajar.starter.database.createAppDatabase
import kotlin.test.Test
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest

class BrowserDocumentCatalogReplicaTest {
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
    val name = "document-catalog-${Uuid.random()}"
    val fixture = DocumentCatalogFixture { createAppDatabase(name) }
    try {
        fixture.activate()
        block(fixture)
    } finally {
        fixture.close()
    }
}
