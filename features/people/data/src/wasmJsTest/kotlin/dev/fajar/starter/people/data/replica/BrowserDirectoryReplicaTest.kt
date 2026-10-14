@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.people.data.replica

import dev.fajar.starter.database.createAppDatabase
import kotlin.test.Test
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest

class BrowserDirectoryReplicaTest {
    @Test
    fun pendingDenialSurvivesNewLogin() = runTest {
        withDatabase(::checkDirectoryPendingDenialSurvivesReauthentication)
    }

    @Test
    fun readDenialSurvivesRestart() = runTest {
        withDatabase(::checkDirectoryReadDenialSurvivesRepositoryAndSessionReplacement)
    }

    @Test
    fun failedAdmissionStorageRemainsClosed() = runTest {
        withDatabase(::checkDirectoryReadDenialRemainsVisibleWhenStorageFails)
    }

    @Test
    fun lateAdmissionCannotReopenLaterDenial() = runTest {
        withDatabase(::checkDirectoryReadDenialCannotBeClearedByAnOlderPendingAdmission)
    }

    @Test
    fun allPagesAndOwnerSurviveRestart() = runTest {
        withDatabase(::checkDirectoryPaginationRestartAndOwnership)
    }

    @Test
    fun versionsDeletesAndReplacementAreAtomic() = runTest {
        withDatabase(::checkDirectoryVersionsTombstonesAndAtomicReplacement)
    }

    @Test
    fun plaintextAndIndexesAreNotLeaked() = runTest {
        withDatabase(::checkDirectoryEncryptionAndLostKey)
    }

    @Test
    fun cancellationCannotCommit() = runTest {
        withDatabase(::checkDirectoryCancellationDuringEncryption)
    }

    @Test
    fun scanNeverCombinesDifferentRevisions() = runTest {
        withDatabase(::checkDirectoryScanRejectsMixedRevisions)
    }

    @Test
    fun emptySnapshotDoesNotCreateAKey() = runTest {
        withDatabase(::checkEmptyDirectoryDoesNotCreateAKey)
    }
}

private suspend fun withDatabase(block: suspend (DirectoryReplicaFixture) -> Unit) {
    val name = "directory-replica-${Uuid.random()}"
    val fixture = DirectoryReplicaFixture { createAppDatabase(name) }
    try {
        fixture.activate()
        block(fixture)
    } finally {
        fixture.close()
    }
}
