package dev.fajar.starter.leave.data

import dev.fajar.starter.database.createAppDatabase
import java.nio.file.Files
import kotlin.test.Test
import kotlinx.coroutines.test.runTest

class LeaveWorkingCopyTest {
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
    val folder = Files.createTempDirectory("leave-working-copy").toFile()
    val fixture = LeaveRequestReplicaFixture(createAppDatabase(folder))
    try {
        fixture.activate()
        block(fixture)
    } finally {
        fixture.close()
        folder.deleteRecursively()
    }
}
