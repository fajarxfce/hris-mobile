package dev.fajar.starter.payroll.data.replica

import dev.fajar.starter.database.createAppDatabase
import java.nio.file.Files
import kotlin.test.Test
import kotlinx.coroutines.test.runTest

class PayrollReplicaTest {
    @Test
    fun historySurvivesReplacementRestartAndReauthentication() = runTest {
        withDatabase(::checkPayrollHistorySurvivesReplicaReplacementRestartAndSameAccountLogin)
    }

    @Test
    fun historyIsImmutableAndIndependentOfCurrentData() = runTest {
        withDatabase(::checkPayrollHistoryIsImmutableAndDoesNotChangeCurrentDataOrCursor)
    }

    @Test
    fun historyAuthenticatesEveryRecordAndItsCheckpoint() = runTest {
        withDatabase(::checkPayrollHistoryAuthenticatesRecordsAndCheckpoint)
    }

    @Test
    fun historyRequiresItsOriginalContentKey() = runTest {
        withDatabase(::checkPayrollHistoryAlonePreventsRegeneratingAMissingKey)
    }

    @Test
    fun cancelledOrReplacedHistoryOwnersCannotPublish() = runTest {
        withDatabase(::checkPayrollHistoryCancellationAndOwnerReplacementCannotPublish)
    }

    @Test
    fun obsoleteHistoryCommitsAndPendingCleanupAreOrdered() = runTest {
        withDatabase(::checkPayrollHistoryRejectsObsoleteCommitsAndSerializesCleanup)
    }

    @Test
    fun legacyBalancesRemainReadableUntilACompleteFormatUpgrade() = runTest {
        withDatabase(::checkPayrollLegacyBalanceUpgrade)
    }

    @Test
    fun revisedDetailsAndSummariesRetainIndependentPaymentFacts() = runTest {
        withDatabase(::checkPayrollRevisedDetailsAndSummary)
    }

    @Test
    fun mismatchedRevisionsCannotAdvanceTheStoredCursor() = runTest {
        withDatabase(::checkPayrollRevisionMismatchCannotAdvanceCheckpoint)
    }

    @Test
    fun readDenialSurvivesRestartAndReauthentication() = runTest {
        withDatabase(::checkPayrollReadDenialSurvivesRepositoryAndSessionReplacement)
    }

    @Test
    fun failedPersistenceCannotUnblockRetainedFacts() = runTest {
        withDatabase(::checkPayrollReadDenialRemainsVisibleWhenStorageFails)
    }

    @Test
    fun lateAdmissionCannotClearANewerDenial() = runTest {
        withDatabase(::checkPayrollReadDenialCannotBeClearedByAnOlderPendingAdmission)
    }

    @Test
    fun yearsDetailsAndExactAmountsSurviveDatabaseReopening() = runTest {
        withDatabase(::checkPayrollIndexedYearsDetailsAndRestart)
    }

    @Test
    fun paymentVersionsAndParentDeletionRemainIndependent() = runTest {
        withDatabase(::checkPayrollIndependentVersionsAndDeletion)
    }

    @Test
    fun stagingKeepsOwnedFactsAcrossLoginAndAccountChanges() = runTest {
        withDatabase(::checkPayrollStagingAndAccountOwnership)
    }

    @Test
    fun ciphertextAuthenticatesItsIndexesAndPreservesLostKeyFailure() = runTest {
        withDatabase(::checkPayrollAuthenticatedIndexesAndMissingKey)
    }

    @Test
    fun cancelledOrObsoleteEncryptionDoesNotPublish() = runTest {
        withDatabase(::checkPayrollCancelledOrObsoleteEncryptionCannotPublish)
    }

    @Test
    fun mappingFailureCannotPartiallyCommitDataOrCursor() = runTest {
        withDatabase(::checkPayrollMappingFailureDoesNotCommitAnyPartOfTheBatch)
    }

    @Test
    fun emptyAndStagingReadsDoNotCreateKeysOrClaimCompletion() = runTest {
        withDatabase(::checkPayrollEmptyAndStagingReadsDoNotRequireAKeyOrClaimCompletion)
    }

    @Test
    fun aRetainedDenialAlsoRequiresItsOriginalContentKey() = runTest {
        withDatabase(::checkPayrollRetainedDenialAlsoPreventsKeyRegeneration)
    }
}

private suspend fun withDatabase(block: suspend (PayrollReplicaFixture) -> Unit) {
    val directory = Files.createTempDirectory("payroll-replica").toFile()
    val fixture = PayrollReplicaFixture { createAppDatabase(directory) }
    try {
        fixture.activate()
        block(fixture)
    } finally {
        fixture.close()
        directory.deleteRecursively()
    }
}
