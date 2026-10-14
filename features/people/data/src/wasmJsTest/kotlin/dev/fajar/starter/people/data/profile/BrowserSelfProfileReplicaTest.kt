@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.people.data.profile

import dev.fajar.starter.database.createAppDatabase
import kotlin.test.Test
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest

class BrowserSelfProfileReplicaTest {
    @Test
    fun pendingDenialSurvivesNewLogin() = runTest {
        withDatabase(::checkSelfProfilePendingDenialSurvivesReauthentication)
    }

    @Test
    fun readDenialSurvivesRestart() = runTest {
        withDatabase(::checkSelfProfileReadDenialSurvivesRepositoryAndSessionReplacement)
    }

    @Test
    fun failedAdmissionStorageRemainsClosed() = runTest {
        withDatabase(::checkSelfProfileReadDenialRemainsVisibleWhenStorageFails)
    }

    @Test
    fun lateAdmissionCannotReopenLaterDenial() = runTest {
        withDatabase(::checkSelfProfileReadDenialCannotBeClearedByAnOlderPendingAdmission)
    }

    @Test
    fun allPagesAndOwnerSurviveRestart() = runTest {
        withDatabase(::checkSelfProfilePaginationRestartAndOwnership)
    }

    @Test
    fun versionsDeletesAndReplacementAreAtomic() = runTest {
        withDatabase(::checkSelfProfileVersionsTombstonesAndAtomicReplacement)
    }

    @Test
    fun plaintextAndIndexesAreNotLeaked() = runTest {
        withDatabase(::checkSelfProfileEncryptionAndLostKey)
    }

    @Test
    fun cancellationCannotCommit() = runTest {
        withDatabase(::checkSelfProfileCancellationDuringEncryption)
    }

    @Test
    fun scanNeverCombinesDifferentRevisions() = runTest {
        withDatabase(::checkSelfProfileScanRejectsMixedRevisions)
    }

    @Test
    fun emptySnapshotDoesNotCreateAKey() = runTest {
        withDatabase(::checkEmptySelfProfileDoesNotCreateAKey)
    }
}

private suspend fun withDatabase(block: suspend (SelfProfileReplicaFixture) -> Unit) {
    val name = "self-profile-replica-${Uuid.random()}"
    val fixture = SelfProfileReplicaFixture { createAppDatabase(name) }
    try {
        fixture.activate()
        block(fixture)
    } finally {
        fixture.close()
    }
}
