@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.workforce.data.overtime

import dev.fajar.starter.database.createAppDatabase
import kotlin.test.Test
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest

class BrowserOvertimeReplicaTest {
    @Test
    fun dateFiltersUseBoundedIndexedPagesAndRetainEveryDetail() = runTest {
        withDatabase(::checkOvertimeRangeQueriesAndIndependentDetails)
    }

    @Test
    fun individualReadsCanPublishBeforeTheFeedWithoutCompletingIt() = runTest {
        withDatabase(::checkOvertimeIndividualReadsBeforeAndAfterFeed)
    }

    @Test
    fun historicalActivityDoesNotRenewAnActionReview() = runTest {
        withDatabase(::checkOvertimeHistoryProgressAndOriginalReviewLease)
    }

    @Test
    fun deletedParentsAndReplacedStatusesCannotResurrect() = runTest {
        withDatabase(::checkOvertimeDeletedParentsAndStatusIndexes)
    }

    @Test
    fun stagingKeepsPublishedFactsUntilItsSuccessorCompletes() = runTest {
        withDatabase(::checkOvertimeStagingProtectsPublishedFacts)
    }

    @Test
    fun aNewLoginRetainsFactsButRejectsOldWritersAndActionReviews() = runTest {
        withDatabase(::checkOvertimeRetainedOwnershipAndLateWriters)
    }

    @Test
    fun ciphertextAndIndexesAuthenticateTheirScope() = runTest {
        withDatabase(::checkOvertimeCiphertextAndIndexAuthentication)
    }

    @Test
    fun lateEncryptionCannotPublishAfterCancellationOrAccountReplacement() = runTest {
        withDatabase(::checkOvertimeCancelledEncryptionCannotPublish)
    }

    @Test
    fun thePrecedingEncryptedRequestMigratesWithoutLosingAcquiredHistory() = runTest {
        withDatabase(::checkOvertimeLegacyReadOnlyMigration)
    }
}

private suspend fun withDatabase(block: suspend (OvertimeReplicaFixture) -> Unit) {
    val fixture = OvertimeReplicaFixture(createAppDatabase("overtime-replica-${Uuid.random()}"))
    try {
        fixture.activate()
        block(fixture)
    } finally {
        fixture.close()
    }
}
