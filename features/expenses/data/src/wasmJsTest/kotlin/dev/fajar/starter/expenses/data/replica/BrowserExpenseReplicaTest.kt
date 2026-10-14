@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.expenses.data.replica

import dev.fajar.starter.database.createAppDatabase
import kotlin.test.Test
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest

class BrowserExpenseReplicaTest {
    @Test
    fun localMonthsPagesAndReauthentication() = runTest {
        withDatabase(::checkExpenseLocalMonthsPagesAndReauthentication)
    }

    @Test
    fun equalVersionGrowthAndShrink() = runTest {
        withDatabase(::checkExpenseEqualVersionGrowthAndShrink)
    }

    @Test fun authenticatedChunks() = runTest { withDatabase(::checkExpenseAuthenticatedChunks) }

    @Test
    fun historyKeepsReviewLeaseAndCannotSkipItsFrontier() = runTest {
        withDatabase(::checkExpenseHistoryKeepsReviewLeaseAndCannotSkipItsFrontier)
    }

    @Test
    fun stagingDeletionAndLostKeys() = runTest {
        withDatabase(::checkExpenseStagingDeletionAndLostKeys)
    }

    @Test
    fun atomicMappingFailureAndPaymentDeletion() = runTest {
        withDatabase(::checkExpenseAtomicMappingFailureAndPaymentDeletion)
    }

    @Test
    fun cancelledOrObsoleteEncryptionCannotPublish() = runTest {
        withDatabase(::checkExpenseCancelledOrObsoleteEncryptionCannotPublish)
    }

    @Test
    fun readDenialSurvivesRepositoryAndSessionReplacement() = runTest {
        withDatabase(::checkExpenseReadDenialSurvivesRepositoryAndSessionReplacement)
    }

    @Test
    fun readDenialRemainsVisibleWhenStorageFails() = runTest {
        withDatabase(::checkExpenseReadDenialRemainsVisibleWhenStorageFails)
    }

    @Test
    fun readDenialCannotBeClearedByAnOlderPendingAdmission() = runTest {
        withDatabase(::checkExpenseReadDenialCannotBeClearedByAnOlderPendingAdmission)
    }
}

private suspend fun withDatabase(block: suspend (ExpenseReplicaFixture) -> Unit) {
    val name = "expense-replica-${Uuid.random()}"
    val fixture = ExpenseReplicaFixture { createAppDatabase(name) }
    try {
        fixture.activate()
        block(fixture)
    } finally {
        fixture.close()
    }
}
