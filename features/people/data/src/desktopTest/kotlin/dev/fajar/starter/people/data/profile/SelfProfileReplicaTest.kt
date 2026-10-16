package dev.fajar.starter.people.data.profile

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.database.createAppDatabase
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertIs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class SelfProfileReplicaTest {
    @Test
    fun anOversizedOwnedProjectionStopsInsteadOfReadingUnboundedRecords() = runTest {
        withDatabase { fixture ->
            fixture.publish((1..201).map { selfProfileFixtureRecord(it) })
            assertIs<AppResult.Failed>(fixture.repository.observe(fixture.access).first())
            kotlin.test.assertEquals(
                AppResult.Success(fixture.checkpoint()),
                fixture.repository.observePublishedCheckpoint(fixture.access).first(),
            )
        }
    }

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
    val selfProfile = Files.createTempDirectory("self-profile-replica").toFile()
    val fixture = SelfProfileReplicaFixture { createAppDatabase(selfProfile) }
    try {
        fixture.activate()
        block(fixture)
    } finally {
        fixture.close()
        selfProfile.deleteRecursively()
    }
}
