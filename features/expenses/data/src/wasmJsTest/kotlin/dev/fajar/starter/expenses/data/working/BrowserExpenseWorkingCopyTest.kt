@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.expenses.data.working

import dev.fajar.starter.database.createAppDatabase
import dev.fajar.starter.expenses.data.replica.ExpenseReplicaFixture
import kotlin.test.Test
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest

class BrowserExpenseWorkingCopyTest {
    @Test
    fun reopensWithoutChangingClaim() = runTest {
        withDatabase(::checkWorkingCopyReopensWithoutChangingClaim)
    }

    @Test
    fun competingWritesAndLostAcknowledgement() = runTest {
        withDatabase(::checkWorkingCopyConcurrentWritesAndLostAcknowledgement)
    }

    @Test
    fun authenticatesChunksAndBoundsInput() = runTest {
        withDatabase(::checkWorkingCopyRejectsMixedChunksAndInvalidInput)
    }

    @Test
    fun cancelsAndRejectsAnObsoleteOwner() = runTest {
        withDatabase(::checkWorkingCopyCancellationAndScopeReplacement)
    }

    @Test
    fun sharesFirstKeyAndRejectsKeyLoss() = runTest {
        withDatabase(::checkWorkingCopySharesItsFirstKeyAndRejectsKeyLoss)
    }
}

private suspend fun withDatabase(block: suspend (ExpenseReplicaFixture) -> Unit) {
    val name = "expense-working-copy-${Uuid.random()}"
    val fixture = ExpenseReplicaFixture { createAppDatabase(name) }
    try {
        fixture.activate()
        block(fixture)
    } finally {
        fixture.close()
    }
}
