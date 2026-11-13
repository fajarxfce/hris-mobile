@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class EditCollaborationSpaceDraftTest {
    @Test
    fun offlineEditRetainsItsCapturedRevisionTimeAndExactTextAcrossDelayedReads() = runTest {
        val f = SpaceComposerUseCaseFixture()
        f.base.native.setup.workspace = null
        f.base.native.setup.beforeWorkspace = { error("No HR company required") }
        f.beforeDraftRead = { f.base.native.setup.now += 100.days }
        f.base.beforeCanonical = { f.base.native.setup.now += 100.days }
        assertEquals(AppResult.Success(f.original), f.edit()(f.access, null, f.original))
        assertEquals(listOf(f.original), f.edits)
        assertEquals(f.original, f.editor)
        assertTrue(f.base.writes.isEmpty() && f.base.schedules.isEmpty())
    }

    @Test
    fun exactReplayAfterLoginRenewalDoesNotRequireMetadataOrRewrite() = runTest {
        val f = SpaceComposerUseCaseFixture()
        val edit = f.edit()
        assertEquals(AppResult.Success(f.original), edit(f.access, null, f.original))
        f.base.canonical = null
        val renewed = f.access.copy(sessionId = "renewed")
        f.base.identity.value =
            AppResult.Success(f.base.native.session.copy(id = renewed.sessionId))
        assertEquals(AppResult.Success(f.original), edit(renewed, null, f.original))
        assertEquals(1, f.edits.size)
        assertEquals(1, f.base.canonicalReads)
    }

    @Test
    fun changingContentWithoutChangingRevisionFailsBeforeReadingStorage() = runTest {
        val f = SpaceComposerUseCaseFixture()
        val result =
            assertIs<AppResult.Failed>(
                f.edit()(f.access, f.original, f.original.copy(text = "changed"))
            )
        assertEquals(FailureKind.Validation, result.failure.kind)
        assertEquals(0, f.draftReads)
        assertTrue(f.edits.isEmpty())
    }

    @Test
    fun concurrentNewerEditorCannotBeOverwrittenByTheLateCompareAndSwap() = runTest {
        val f = SpaceComposerUseCaseFixture()
        val newer = f.submission.nextDraft.copy(text = "Newer typing")
        f.beforeEdit = { f.editor = newer }
        assertEquals(
            FailureKind.Conflict,
            assertIs<AppResult.Failed>(f.edit()(f.access, null, f.original)).failure.kind,
        )
        assertEquals(newer, f.editor)
        assertTrue(f.edits.isEmpty())
    }

    @Test
    fun lostCommitReplyRequiresExactReadbackAndPreservesUnprovenStorageFailure() = runTest {
        for (changed in listOf(false, true)) {
            val f = SpaceComposerUseCaseFixture()
            f.editFailure = f.base.base.storageFailure
            if (changed) f.afterEdit = { f.editor = f.original.copy(text = "Someone else's value") }
            val result = f.edit()(f.access, null, f.original)
            if (changed) assertEquals(AppResult.Failed(f.base.base.storageFailure), result)
            else assertEquals(AppResult.Success(f.original), result)
            assertEquals(1, f.edits.size)
        }
    }

    @Test
    fun priorDenialAndAccountReplacementDuringAdmissionNeverReadPrivateDrafts() = runTest {
        for (denied in listOf(false, true)) {
            val f = SpaceComposerUseCaseFixture()
            if (denied) f.base.base.deny()
            else f.base.base.beforeAdmission = { f.base.identity.value = AppResult.Success(null) }
            val failure = assertIs<AppResult.Failed>(f.edit()(f.access, null, f.original)).failure
            assertEquals(
                if (denied) FailureKind.AccessDenied else FailureKind.Unauthorized,
                failure.kind,
            )
            assertEquals(0, f.draftReads)
            assertTrue(f.edits.isEmpty())
        }
    }

    @Test
    fun lateDenialOrSignOutPreservesCommittedDraftWithoutPublishingSuccess() = runTest {
        for (denied in listOf(false, true)) {
            val f = SpaceComposerUseCaseFixture()
            f.afterEdit = {
                if (denied) f.base.base.deny() else f.base.identity.value = AppResult.Success(null)
            }
            val failure = assertIs<AppResult.Failed>(f.edit()(f.access, null, f.original)).failure
            assertEquals(
                if (denied) FailureKind.AccessDenied else FailureKind.Unauthorized,
                failure.kind,
            )
            assertEquals(f.original, f.editor)
            assertEquals(1, f.edits.size)
        }
    }

    @Test
    fun cancellationAfterCommitPropagatesAndExactRetryDoesNotRewrite() = runTest {
        val f = SpaceComposerUseCaseFixture()
        val entered = CompletableDeferred<Unit>()
        f.afterEdit = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val work = async { f.edit()(f.access, null, f.original) }
        entered.await()
        work.cancelAndJoin()
        assertTrue(work.isCancelled)
        assertEquals(f.original, f.editor)
        f.afterEdit = {}
        assertEquals(AppResult.Success(f.original), f.edit()(f.access, null, f.original))
        assertEquals(1, f.edits.size)
    }

    @Test
    fun absentOrForeignCanonicalConversationCannotCreateADraft() = runTest {
        for (variant in 0..2) {
            val f = SpaceComposerUseCaseFixture()
            f.base.canonical =
                when (variant) {
                    0 -> null
                    1 -> f.base.canonical!!.copy(spaceId = f.access.accountId)
                    else -> f.base.canonical!!.copy(id = f.access.accountId)
                }
            assertEquals(
                FailureKind.Unavailable,
                assertIs<AppResult.Failed>(f.edit()(f.access, null, f.original)).failure.kind,
            )
            assertTrue(f.edits.isEmpty())
        }
    }

    @Test
    fun malformedRetainedDraftNeverConfirmsOrGetsReplaced() = runTest {
        val f = SpaceComposerUseCaseFixture()
        val wrong = f.original.copy(conversationId = f.access.accountId)
        f.editor = wrong
        assertEquals(
            FailureKind.Unexpected,
            assertIs<AppResult.Failed>(f.edit()(f.access, null, f.original)).failure.kind,
        )
        assertEquals(wrong, f.editor)
        assertTrue(f.edits.isEmpty())
    }
}
