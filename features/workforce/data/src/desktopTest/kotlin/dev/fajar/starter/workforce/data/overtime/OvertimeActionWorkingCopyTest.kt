package dev.fajar.starter.workforce.data.overtime

import dev.fajar.starter.database.createAppDatabase
import java.nio.file.Files
import kotlin.test.Test
import kotlinx.coroutines.test.runTest

class OvertimeActionWorkingCopyTest {
    @Test
    fun concurrentAutosaveAndReadDenialUseTheSameContentKey() = runTest {
        withDatabase(::checkConcurrentOvertimeActionWritersShareOneContentKey)
    }

    @Test
    fun ownedInputSurvivesFactCleanupAndLogin() = runTest {
        withDatabase(::checkOvertimeActionWorkingCopySurvivesFactCleanupAndLogin)
    }

    @Test
    fun unknownLocalOutcomeRecoversWithoutOverwritingAnotherEdit() = runTest {
        withDatabase(::checkOvertimeActionWorkingAcknowledgementAndCompetingEdits)
    }

    @Test
    fun copiedEnvelopeAndLostKeyCannotRevealOrReplaceInput() = runTest {
        withDatabase(::checkOvertimeActionWorkingCipherBindsScopeAndAction)
    }

    @Test
    fun cancelledEncryptionCannotPublishAndClearsByteBuffers() = runTest {
        withDatabase(::checkCancelledOvertimeActionWorkingEncryptionCannotPublish)
    }
}

private suspend fun withDatabase(block: suspend (OvertimeReplicaFixture) -> Unit) {
    val folder = Files.createTempDirectory("overtime-action-working-copy").toFile()
    val fixture = OvertimeReplicaFixture(createAppDatabase(folder))
    try {
        fixture.activate()
        block(fixture)
    } finally {
        fixture.close()
        folder.deleteRecursively()
    }
}
