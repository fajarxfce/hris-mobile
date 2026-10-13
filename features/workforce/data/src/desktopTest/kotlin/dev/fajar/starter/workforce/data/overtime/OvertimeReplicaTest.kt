package dev.fajar.starter.workforce.data.overtime

import dev.fajar.starter.database.createAppDatabase
import java.nio.file.Files
import kotlin.test.Test
import kotlinx.coroutines.test.runTest

class OvertimeReplicaTest {
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
    val folder = Files.createTempDirectory("overtime-replica").toFile()
    val fixture = OvertimeReplicaFixture(createAppDatabase(folder))
    try {
        fixture.activate()
        block(fixture)
    } finally {
        fixture.close()
        folder.deleteRecursively()
    }
}
