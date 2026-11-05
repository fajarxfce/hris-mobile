@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.database

import androidx.room.Room
import kotlin.test.*
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory

class AppleReplicaStagingTest {
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

private fun openStagingStore(path: String): AppDatabase =
    RoomAppDatabase(Room.databaseBuilder<StarterDatabase>("$path/staging.db"))

private suspend fun withStagingPath(block: suspend (String) -> Unit) {
    val directory = NSTemporaryDirectory().trimEnd('/') + "/replica-staging-${Uuid.random()}"
    val files = NSFileManager.defaultManager
    check(files.createDirectoryAtPath(directory, true, null, null))
    try {
        block(directory)
    } finally {
        check(files.removeItemAtPath(directory, null))
    }
}
