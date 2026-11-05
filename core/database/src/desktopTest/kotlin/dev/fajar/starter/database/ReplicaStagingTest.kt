package dev.fajar.starter.database

import java.nio.file.Files
import kotlin.test.*
import kotlinx.coroutines.test.runTest

class ReplicaStagingTest {
    @Test
    fun workingReadsIncludeTombstonesAndHonorPublicationAndOwner() = runTest {
        withStagingStore { verifyWorkingReplicaDocuments(it) }
    }

    @Test
    fun discardRetainsVisibleSnapshotAndAllowsImmediateWrites() = runTest {
        withStagingStore { verifyReplicaStagingDiscard(it) }
    }

    @Test
    fun competingContinuationAndPublicationHaveOneWinner() = runTest {
        withStagingStore { verifyReplicaStagingRaces(it) }
    }

    @Test
    fun boundedAndCancelledDiscardCannotPartiallyRemoveData() = runTest {
        withStagingStore { verifyReplicaStagingBoundsAndCancellation(it) }
    }

    @Test
    fun restartAndReauthenticationRetainDiscardOwnership() = runTest {
        withStagingPath { path ->
            val original = openStagingStore(path)
            try {
                prepareReplicaStagingRestart(original)
            } finally {
                original.close()
            }
            val reopened = openStagingStore(path)
            try {
                verifyReplicaStagingAfterRestart(reopened)
            } finally {
                reopened.close()
            }
        }
    }
}

private suspend fun withStagingStore(block: suspend (AppDatabase) -> Unit) {
    withStagingPath { path ->
        val database = openStagingStore(path)
        try {
            block(database)
        } finally {
            database.close()
        }
    }
}

private fun openStagingStore(path: java.io.File) = createAppDatabase(path)

private suspend fun withStagingPath(block: suspend (java.io.File) -> Unit) {
    val directory = Files.createTempDirectory("replica-staging").toFile()
    try {
        block(directory)
    } finally {
        check(directory.deleteRecursively())
    }
}
