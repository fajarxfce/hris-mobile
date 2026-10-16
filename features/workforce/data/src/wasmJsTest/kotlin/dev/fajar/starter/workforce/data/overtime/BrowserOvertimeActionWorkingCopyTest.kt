@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.workforce.data.overtime

import dev.fajar.starter.database.createAppDatabase
import kotlin.test.Test
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest

class BrowserOvertimeActionWorkingCopyTest {
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
    val fixture =
        OvertimeReplicaFixture(createAppDatabase("overtime-action-working-" + Uuid.random()))
    try {
        fixture.activate()
        block(fixture)
    } finally {
        fixture.close()
    }
}
