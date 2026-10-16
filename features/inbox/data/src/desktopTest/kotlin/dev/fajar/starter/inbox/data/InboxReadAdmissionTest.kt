package dev.fajar.starter.inbox.data

import dev.fajar.starter.database.createAppDatabase
import java.nio.file.Files
import kotlin.test.Test
import kotlinx.coroutines.test.runTest

class InboxReadAdmissionTest {
    @Test
    fun aConfirmedDenialSurvivesCleanupAndReauthentication() = runTest {
        withDatabase(::checkInboxDenialSurvivesStorageCleanupAndLogin)
    }

    @Test
    fun storageFailureCannotReopenPrivateInbox() = runTest {
        withDatabase(::checkInboxDenialRemainsKnownAfterFailedStorage)
    }

    @Test
    fun anOldPendingAdmissionCannotRemoveANewerDenial() = runTest {
        withDatabase(::checkLateInboxAdmissionCannotClearANewerDenial)
    }

    @Test
    fun cancelledPersistenceKeepsTheProcessAdmissionGate() = runTest {
        withDatabase(::checkCancelledInboxDenialRetainsTheProcessGate)
    }

    @Test
    fun admissionCiphertextAuthenticatesItsOwnerAndNeverReplacesALostKey() = runTest {
        withDatabase(::checkInboxAdmissionAuthenticatesItsCompanyAndRetainsALostKey)
    }
}

private suspend fun withDatabase(block: suspend (InboxReadAdmissionFixture) -> Unit) {
    val folder = Files.createTempDirectory("inbox-admission").toFile()
    val fixture = InboxReadAdmissionFixture(createAppDatabase(folder))
    try {
        fixture.activate()
        block(fixture)
    } finally {
        fixture.close()
        folder.deleteRecursively()
    }
}
