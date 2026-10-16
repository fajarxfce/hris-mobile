@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.leave.data

import dev.fajar.starter.database.createAppDatabase
import kotlin.test.Test
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest

class BrowserLeaveWorkingCopyTest {
    @Test
    fun concurrentAutosaveAndReadDenialUseTheSameContentKey() = runTest {
        withDatabase(::checkConcurrentLeaveWritersShareOneContentKey)
    }

    @Test
    fun ownedInputSurvivesFactCleanupAndLogin() = runTest {
        withDatabase(::checkLeaveWorkingCopySurvivesFactCleanupAndLogin)
    }

    @Test
    fun unknownLocalOutcomeRecoversWithoutOverwritingAnotherEdit() = runTest {
        withDatabase(::checkLeaveWorkingAcknowledgementAndCompetingEdits)
    }

    @Test
    fun copiedEnvelopeAndLostKeyCannotRevealOrReplaceInput() = runTest {
        withDatabase(::checkLeaveWorkingCipherBindsCompanyAndEmployee)
    }

    @Test
    fun cancelledEncryptionCannotPublishAndClearsByteBuffers() = runTest {
        withDatabase(::checkCancelledWorkingEncryptionCannotPublish)
    }
}

private suspend fun withDatabase(block: suspend (LeaveRequestReplicaFixture) -> Unit) {
    val fixture = LeaveRequestReplicaFixture(createAppDatabase("leave-working-" + Uuid.random()))
    try {
        fixture.activate()
        block(fixture)
    } finally {
        fixture.close()
    }
}
