package dev.fajar.starter.people.data.replica

import dev.fajar.starter.database.createAppDatabase
import dev.fajar.starter.people.domain.replica.entities.DirectorySelection
import dev.fajar.starter.people.domain.replica.policies.selectDirectory
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.measureTimedValue
import kotlinx.coroutines.test.runTest

class DirectoryReplicaTest {
    @Test
    fun aLargeDirectorySearchIncludesTheFinalUnvisitedPage() = runTest {
        withDatabase { fixture ->
            fixture.publish((1..5000).map { directoryFixtureRecord(it) })
            val snapshot = measureTimedValue { fixture.snapshot() }
            val query = measureTimedValue {
                selectDirectory(snapshot.value, DirectorySelection(query = "Colleague 5000"))!!
            }
            assertEquals(5000, snapshot.value.records.size)
            assertEquals(directoryFixtureId(5000), query.value.page.items.single().id)
            println(
                "directory_rows=5000 decode_ms=${snapshot.duration.inWholeMilliseconds} query_ms=${query.duration.inWholeMilliseconds}"
            )
        }
    }

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
    val directory = Files.createTempDirectory("directory-replica").toFile()
    val fixture = DirectoryReplicaFixture { createAppDatabase(directory) }
    try {
        fixture.activate()
        block(fixture)
    } finally {
        fixture.close()
        directory.deleteRecursively()
    }
}
