@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.inbox.data

import dev.fajar.starter.database.createAppDatabase
import kotlin.test.Test
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest

class BrowserInboxReadAdmissionTest {
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
    val fixture = InboxReadAdmissionFixture(createAppDatabase("inbox-admission-${Uuid.random()}"))
    try {
        fixture.activate()
        block(fixture)
    } finally {
        fixture.close()
    }
}
