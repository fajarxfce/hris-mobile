package dev.fajar.starter.leave.data

import dev.fajar.starter.database.createAppDatabase
import java.nio.file.Files
import kotlin.test.Test
import kotlinx.coroutines.test.runTest

class LeaveRequestReplicaTest {
    @Test
    fun readDenialSurvivesFactCleanupAndSessionReplacement() = runTest {
        withDatabase(::checkLeaveDenialSurvivesStorageCleanupAndLogin)
    }

    @Test
    fun failedAdmissionWritesKeepTheProcessGate() = runTest {
        withDatabase(::checkLeaveDenialRemainsKnownAfterFailedStorage)
    }

    @Test
    fun olderRecoveryCannotClearANewerDenial() = runTest {
        withDatabase(::checkLateLeaveAdmissionCannotClearANewerDenial)
    }

    @Test
    fun cancellationBeforePublicationKeepsTheDenialVisible() = runTest {
        withDatabase(::checkCancelledLeaveDenialRetainsTheProcessGate)
    }

    @Test
    fun admissionAuthenticatesItsOwnerAndNeverReplacesALostKey() = runTest {
        withDatabase(::checkLeaveAdmissionAuthenticatesItsCompanyAndRetainsALostKey)
    }

    @Test
    fun cancelledLateEncryptionCannotPublishARequestOrHistoryPage() = runTest {
        withDatabase(::checkLateRequestEncryptionCannotPublish)
    }

    @Test
    fun individualAcquisitionDoesNotDependOnAnOverview() = runTest {
        withDatabase(::checkIndependentRequestsBeforeAndAfterFeed)
    }

    @Test
    fun denseRequestsCommitTogetherWithinTheDatabaseBounds() = runTest {
        withDatabase(::checkDenseAtomicRequestBatch)
    }

    @Test
    fun immutableHistoryProgressDoesNotRenewAnActionLease() = runTest {
        withDatabase(::checkHistoryProgressPreservesCurrentFactsAndActionLease)
    }

    @Test
    fun oldDayChunksAndDeletedParentsCannotBecomeVisible() = runTest {
        withDatabase(::checkOldChunksAndDeletedParentsCannotResurrectRequests)
    }

    @Test
    fun dataSurvivesLoginWithoutAllowingLateWritersOrOtherAccounts() = runTest {
        withDatabase(::checkRetainedOwnerAndAcquisitionCompareAndSet)
    }
}

private suspend fun withDatabase(block: suspend (LeaveRequestReplicaFixture) -> Unit) {
    val folder = Files.createTempDirectory("leave-request-replica").toFile()
    val fixture = LeaveRequestReplicaFixture(createAppDatabase(folder))
    try {
        fixture.activate()
        block(fixture)
    } finally {
        fixture.close()
        folder.deleteRecursively()
    }
}
