package dev.fajar.starter.workforce.data.replica

import dev.fajar.starter.database.createAppDatabase
import java.nio.file.Files
import kotlin.test.Test
import kotlinx.coroutines.test.runTest

class WorkforceReplicaTest {
    @Test
    fun readDenialSurvivesRestartAndReauthentication() = runTest {
        withDatabase(::checkWorkforceReadDenialSurvivesRepositoryAndSessionReplacement)
    }

    @Test
    fun failedPersistenceCannotUnblockRetainedFacts() = runTest {
        withDatabase(::checkWorkforceReadDenialRemainsVisibleWhenStorageFails)
    }

    @Test
    fun lateAdmissionCannotClearANewerDenial() = runTest {
        withDatabase(::checkWorkforceReadDenialCannotBeClearedByAnOlderPendingAdmission)
    }

    @Test
    fun historicalQueriesAreIndexedAndRetainTheEffectiveBaseline() = runTest {
        withDatabase(::checkWorkforceBoundedHistoricalQueries)
    }

    @Test
    fun stableIdsSupportExplicitOffInactiveHolidaysAndTombstones() = runTest {
        withDatabase(::checkWorkforceStableIdsUpdatesAndTombstones)
    }

    @Test
    fun stagedReplacementAndReauthenticationPreserveOwnedFacts() = runTest {
        withDatabase(::checkWorkforceReplacementAndAccountOwnership)
    }

    @Test
    fun encryptedFactsAuthenticateIndexesAndNeverRegenerateALostKey() = runTest {
        withDatabase(::checkWorkforceAuthenticatedRecordsAndMissingKey)
    }

    @Test
    fun cancelledEncryptionReleasesBuffersWithoutPublication() = runTest {
        withDatabase(::checkWorkforceCancelledEncryptionCannotPublish)
    }

    @Test
    fun emptyAndStagingReplicasDoNotPretendToBeComplete() = runTest {
        withDatabase(::checkWorkforceFactsWithoutAnyReplicaDoNotRequireAContentKey)
    }
}

private suspend fun withDatabase(block: suspend (WorkforceReplicaFixture) -> Unit) {
    val folder = Files.createTempDirectory("workforce-replica").toFile()
    val fixture = WorkforceReplicaFixture(createAppDatabase(folder))
    try {
        fixture.activate()
        block(fixture)
    } finally {
        fixture.close()
        folder.deleteRecursively()
    }
}
