package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.collaborationSpaceInboxEntry
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class EditSpaceDraftInboxTest {
    private val generation = "d5000000-0000-4000-8000-000000000001"

    @Test
    fun successfulEditingConfirmsTheSameSourceAndIndexedSlot() = runTest {
        val f = SpaceComposerUseCaseFixture()
        assertEquals(AppResult.Success(f.original), f.edit()(f.access, null, f.original))
        assertEquals(f.original, f.editor)
        assertEquals(f.original, f.inboxState.draft?.value)
        assertEquals(f.original, f.inboxState.entry?.draft?.value)
        assertEquals(1L, f.inboxState.version?.lastOrdinal)
    }

    @Test
    fun sourceAcknowledgementAloneCannotConfirmAnAbsentIndexedEditor() = runTest {
        val f = SpaceComposerUseCaseFixture()
        f.afterEdit = {
            f.inboxState = CollaborationSpaceInboxConversation(null, null, null, emptyList())
        }
        val result = assertIs<AppResult.Failed>(f.edit()(f.access, null, f.original))
        assertEquals(FailureKind.Conflict, result.failure.kind)
        assertEquals(3, f.edits.size)
        assertEquals(f.original, f.editor)
        assertNull(f.inboxState.draft)
    }

    @Test
    fun oneIndependentIndexConflictRereadsWithoutChangingTheCapturedGesture() = runTest {
        val f = SpaceComposerUseCaseFixture()
        var attempts = 0
        f.beforeEdit = {
            attempts++
            if (attempts == 1)
                f.inboxState =
                    f.inboxState.copy(version = CollaborationSpaceInboxVersion(generation, 0, 0))
        }
        assertEquals(AppResult.Success(f.original), f.edit()(f.access, null, f.original))
        assertEquals(2, attempts)
        assertEquals(listOf(f.original), f.edits)
        assertEquals(f.original, f.inboxState.draft?.value)
        assertEquals(1L, f.inboxState.draft?.position?.ordinal)
    }

    @Test
    fun repeatedIndexContentionStopsAfterThreeAttemptsWithoutWritingTheSource() = runTest {
        val f = SpaceComposerUseCaseFixture()
        var attempts = 0
        f.beforeEdit = {
            attempts++
            f.inboxState =
                f.inboxState.copy(
                    version = CollaborationSpaceInboxVersion(generation, attempts.toLong(), 0)
                )
        }
        assertEquals(
            FailureKind.Conflict,
            assertIs<AppResult.Failed>(f.edit()(f.access, null, f.original)).failure.kind,
        )
        assertEquals(3, attempts)
        assertTrue(f.edits.isEmpty())
        assertNull(f.editor)
    }

    @Test
    fun cancellationDuringIndexAcquisitionEscapesBeforeAnyWrite() = runTest {
        val f = SpaceComposerUseCaseFixture()
        val entered = CompletableDeferred<Unit>()
        f.beforeInboxRead = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val work = async { f.edit()(f.access, null, f.original) }
        entered.await()
        work.cancelAndJoin()
        assertTrue(work.isCancelled)
        assertTrue(f.edits.isEmpty())
        assertNull(f.editor)
        assertNull(f.inboxState.version)
    }

    @Test
    fun denialDuringIndexReadPreventsMetadataAcquisitionAndSourcePublication() = runTest {
        val f = SpaceComposerUseCaseFixture()
        f.beforeInboxRead = { f.base.base.deny() }
        assertEquals(
            FailureKind.AccessDenied,
            assertIs<AppResult.Failed>(f.edit()(f.access, null, f.original)).failure.kind,
        )
        assertEquals(0, f.base.canonicalReads)
        assertTrue(f.edits.isEmpty())
    }

    @Test
    fun lostReplyWithMissingIndexPreservesTheUnprovenStorageFailure() = runTest {
        val f = SpaceComposerUseCaseFixture()
        f.editFailure = f.base.base.storageFailure
        f.afterEdit = {
            f.inboxState = CollaborationSpaceInboxConversation(null, null, null, emptyList())
        }
        assertEquals(
            AppResult.Failed(f.base.base.storageFailure),
            f.edit()(f.access, null, f.original),
        )
        assertEquals(1, f.edits.size)
        assertEquals(f.original, f.editor)
    }

    @Test
    fun emptySlotReplayNeedsNeitherMetadataNorAnotherWriteAndForeignRowsAreRejected() = runTest {
        val f = SpaceComposerUseCaseFixture()
        val cleared = f.original.copy(text = "")
        assertEquals(AppResult.Success(cleared), f.edit()(f.access, null, cleared))
        assertNull(f.inboxState.entry?.draft)
        f.base.canonical = null
        assertEquals(AppResult.Success(cleared), f.edit()(f.access, null, cleared))
        assertEquals(1, f.edits.size)
        assertEquals(1, f.base.canonicalReads)
        f.inboxState =
            f.inboxState.copy(entry = collaborationSpaceInboxEntry(generation, 0, null, null))
        assertEquals(
            FailureKind.Unexpected,
            assertIs<AppResult.Failed>(f.edit()(f.access, null, cleared)).failure.kind,
        )
        assertEquals(1, f.edits.size)
    }
}
