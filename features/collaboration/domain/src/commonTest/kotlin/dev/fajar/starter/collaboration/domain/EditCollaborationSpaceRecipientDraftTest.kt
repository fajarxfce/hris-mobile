@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class EditCollaborationSpaceRecipientDraftTest {
    @Test
    fun firstLocalDraftKeepsCapturedTextAndTimeWithoutCompanyOrTransport() = runTest {
        val f = SpaceRecipientDraftFixture()
        f.base.native.setup.workspace = null
        f.beforeRead = { f.base.native.setup.now += 100.days }
        assertEquals(AppResult.Success(f.original), f.edit()(f.access, null, f.original))
        assertEquals(listOf(f.original), f.writes)
        assertEquals(1, f.contactReads)
        assertEquals(1, f.contactStops)
        assertTrue(f.base.requests.isEmpty())
        assertEquals(0, f.base.begins)
        assertEquals(0, f.base.appends)
    }

    @Test
    fun missingOrMismatchedFirstRecipientNeverWrites() = runTest {
        for (missing in listOf(false, true)) {
            val f = SpaceRecipientDraftFixture()
            f.contact = if (missing) null else f.contact!!.copy(accountId = f.access.accountId)
            val result = assertIs<AppResult.Failed>(f.edit()(f.access, null, f.original))
            assertEquals(
                if (missing) FailureKind.Unavailable else FailureKind.Unexpected,
                result.failure.kind,
            )
            assertEquals(0, f.writeCalls)
        }
    }

    @Test
    fun ownedWorkingCopyRemainsEditableAfterContactDisappears() = runTest {
        val f = SpaceRecipientDraftFixture()
        f.editor = f.original
        f.contact = null
        val next =
            f.original.copy(revision = f.base.generation, text = "Continue my own working copy")
        assertEquals(AppResult.Success(next), f.edit()(f.access, f.original, next))
        assertEquals(0, f.contactReads)
        assertEquals(listOf(next), f.writes)
    }

    @Test
    fun exactReplayAfterSessionRenewalNeitherWritesNorRequiresTheContact() = runTest {
        val f = SpaceRecipientDraftFixture()
        f.editor = f.original
        f.contact = null
        val renewed = f.access.copy(sessionId = "new-local-login")
        f.base.identity.value =
            AppResult.Success(f.base.native.session.copy(id = renewed.sessionId))
        assertEquals(AppResult.Success(f.original), f.edit()(renewed, null, f.original))
        assertEquals(0, f.writeCalls)
        assertEquals(0, f.contactReads)
    }

    @Test
    fun editingTheSameRevisionOrAnotherPeerFailsBeforeStorage() = runTest {
        for (replacement in listOf("text", "peer")) {
            val f = SpaceRecipientDraftFixture()
            val next =
                if (replacement == "text") f.original.copy(text = "Changed")
                else f.original.copy(peerAccountId = f.access.accountId)
            assertEquals(
                FailureKind.Validation,
                assertIs<AppResult.Failed>(f.edit()(f.access, f.original, next)).failure.kind,
            )
            assertEquals(0, f.reads)
            assertEquals(0, f.writeCalls)
        }
    }

    @Test
    fun aCompetingNewerValueCannotBeOverwrittenByTheDelayedWrite() = runTest {
        val f = SpaceRecipientDraftFixture()
        val newer = f.original.copy(revision = f.base.generation, text = "Another editor")
        f.beforeWrite = { f.editor = newer }
        assertEquals(
            FailureKind.Conflict,
            assertIs<AppResult.Failed>(f.edit()(f.access, null, f.original)).failure.kind,
        )
        assertEquals(newer, f.editor)
        assertEquals(1, f.writeCalls)
        assertTrue(f.writes.isEmpty())
    }

    @Test
    fun lostReplyNeedsExactReadbackAndOtherwisePreservesTheStorageFailure() = runTest {
        for (changed in listOf(false, true)) {
            val f = SpaceRecipientDraftFixture()
            f.writeFailure = f.base.storageFailure
            if (changed)
                f.afterWrite = {
                    f.editor = f.original.copy(revision = f.base.generation, text = "Different")
                }
            val result = f.edit()(f.access, null, f.original)
            assertEquals(
                if (changed) AppResult.Failed(f.base.storageFailure)
                else AppResult.Success(f.original),
                result,
            )
            assertEquals(1, f.writeCalls)
        }
    }

    @Test
    fun failedReadbackDoesNotRetryOrReplaceTheCapturedOriginal() = runTest {
        val f = SpaceRecipientDraftFixture()
        f.afterWrite = { f.readFailure = f.base.storageFailure }
        assertEquals(AppResult.Failed(f.base.storageFailure), f.edit()(f.access, null, f.original))
        assertEquals(f.original, f.editor)
        assertEquals(1, f.writeCalls)
    }

    @Test
    fun denialDuringContactLookupStopsBeforeTheWrite() = runTest {
        val f = SpaceRecipientDraftFixture()
        f.beforeContact = { f.base.deny() }
        assertEquals(AppResult.Failed(f.base.denied), f.edit()(f.access, null, f.original))
        assertEquals(0, f.writeCalls)
        assertEquals(1, f.contactStops)
    }

    @Test
    fun accountReplacementDuringDraftReadStopsBeforeLookupOrWrite() = runTest {
        val f = SpaceRecipientDraftFixture()
        f.beforeRead = { f.base.identity.value = AppResult.Success(null) }
        assertEquals(
            FailureKind.Unauthorized,
            assertIs<AppResult.Failed>(f.edit()(f.access, null, f.original)).failure.kind,
        )
        assertEquals(0, f.contactReads)
        assertEquals(0, f.writeCalls)
    }

    @Test
    fun denialAfterCommitDoesNotExposeOrDeleteTheRetainedWorkingCopy() = runTest {
        val f = SpaceRecipientDraftFixture()
        f.afterWrite = { f.base.deny() }
        assertEquals(AppResult.Failed(f.base.denied), f.edit()(f.access, null, f.original))
        assertEquals(f.original, f.editor)
        assertEquals(1, f.writeCalls)
    }

    @Test
    fun cancellingASuspendedReadPerformsNoFollowUpAction() = runTest {
        val f = SpaceRecipientDraftFixture()
        val entered = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        f.beforeRead = {
            entered.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                stopped.complete(Unit)
            }
        }
        val action = async { f.edit()(f.access, null, f.original) }
        entered.await()
        action.cancelAndJoin()
        assertTrue(stopped.isCompleted)
        assertEquals(0, f.contactReads)
        assertEquals(0, f.writeCalls)
    }

    @Test
    fun malformedStoredPeerIsRejectedBeforeAnyReplacement() = runTest {
        val f = SpaceRecipientDraftFixture()
        f.editor = f.original.copy(peerAccountId = f.access.accountId)
        assertEquals(
            FailureKind.Unexpected,
            assertIs<AppResult.Failed>(f.edit()(f.access, null, f.original)).failure.kind,
        )
        assertEquals(0, f.writeCalls)
    }

    @Test
    fun anUnchangedFalseWriteIsAConflictWithoutAnAutomaticLoop() = runTest {
        val f = SpaceRecipientDraftFixture()
        f.forceWriteFalse = true
        assertEquals(
            FailureKind.Conflict,
            assertIs<AppResult.Failed>(f.edit()(f.access, null, f.original)).failure.kind,
        )
        assertEquals(1, f.writeCalls)
        assertNull(f.editor)
    }
}
