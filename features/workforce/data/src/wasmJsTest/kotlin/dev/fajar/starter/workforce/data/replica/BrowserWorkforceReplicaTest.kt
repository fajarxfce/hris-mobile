@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.workforce.data.replica

import dev.fajar.starter.database.createAppDatabase
import kotlin.test.Test
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest

class BrowserWorkforceReplicaTest {
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
    val fixture = WorkforceReplicaFixture(createAppDatabase("workforce-replica-${Uuid.random()}"))
    try {
        fixture.activate()
        block(fixture)
    } finally {
        fixture.close()
    }
}
