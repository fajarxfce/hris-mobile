package dev.fajar.starter.workforce.data.overtime

import dev.fajar.starter.database.createAppDatabase
import java.nio.file.Files
import kotlin.test.Test
import kotlinx.coroutines.test.runTest

class OvertimePlanWorkingCopyTest {
    @Test
    fun concurrentAutosaveAndReadDenialUseTheSameContentKey() = runTest {
        withDatabase(::checkConcurrentOvertimePlanWritersShareOneContentKey)
    }

    @Test
    fun ownedInputSurvivesFactCleanupAndLogin() = runTest {
        withDatabase(::checkOvertimePlanWorkingCopySurvivesFactCleanupAndLogin)
    }

    @Test
    fun unknownLocalOutcomeRecoversWithoutOverwritingAnotherEdit() = runTest {
        withDatabase(::checkOvertimePlanWorkingAcknowledgementAndCompetingEdits)
    }

    @Test
    fun copiedEnvelopeAndLostKeyCannotRevealOrReplaceInput() = runTest {
        withDatabase(::checkOvertimePlanWorkingCipherBindsCompanyAndEmployee)
    }

    @Test
    fun cancelledEncryptionCannotPublishAndClearsByteBuffers() = runTest {
        withDatabase(::checkCancelledOvertimePlanWorkingEncryptionCannotPublish)
    }
}

private suspend fun withDatabase(block: suspend (OvertimeReplicaFixture) -> Unit) {
    val folder = Files.createTempDirectory("overtime-plan-working-copy").toFile()
    val fixture = OvertimeReplicaFixture(createAppDatabase(folder))
    try {
        fixture.activate()
        block(fixture)
    } finally {
        fixture.close()
        folder.deleteRecursively()
    }
}
