package dev.fajar.starter.expenses.data.working

import dev.fajar.starter.database.createAppDatabase
import dev.fajar.starter.expenses.data.replica.ExpenseReplicaFixture
import java.nio.file.Files
import kotlin.test.Test
import kotlinx.coroutines.test.runTest

class ExpenseWorkingCopyTest {
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
    val directory = Files.createTempDirectory("expense-working-copy").toFile()
    val fixture = ExpenseReplicaFixture { createAppDatabase(directory) }
    try {
        fixture.activate()
        block(fixture)
    } finally {
        fixture.close()
        directory.deleteRecursively()
    }
}
