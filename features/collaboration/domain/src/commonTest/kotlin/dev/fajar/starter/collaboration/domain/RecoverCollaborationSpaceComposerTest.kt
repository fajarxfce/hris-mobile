@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.collaborationSpaceComposerIntent
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.test.runTest

class RecoverCollaborationSpaceComposerTest {
    @Test
    fun missingSessionOrRootDoesNotReadPrivateOutboxes() = runTest {
        for (noSession in listOf(false, true)) {
            val f = SpaceComposerRecoveryFixture()
            if (noSession) f.base.identity.value = AppResult.Success(null) else f.root = null
            assertEquals(
                AppResult.Success(CollaborationSpaceComposerRecovery(false, false)),
                f.recover()(),
            )
            assertEquals(0, f.cursorReads + f.outboxReads + f.messageReads + f.inventoryReads)
            assertTrue(f.inserted.isEmpty())
        }
    }

    @Test
    fun onePassMaterializesOnlyEightExactOriginalsAndLeavesTheEditorUntouched() = runTest {
        val f = SpaceComposerRecoveryFixture()
        val originals = f.retain(10).sortedBy { it.id }
        val editing = f.composer.original.copy(text = "Newer unsent typing")
        f.composer.editor = editing
        f.base.native.setup.workspace = null
        f.base.native.setup.beforeWorkspace = { error("No selected HR company") }
        f.beforeMessageRead = { assertEquals(it, assertNotNull(f.cursor).afterSubmissionId) }
        assertEquals(
            AppResult.Success(CollaborationSpaceComposerRecovery(true, true)),
            f.recover()(),
        )
        assertEquals(originals.take(8).map { it.id }, f.inserted.map { it.id })
        for ((original, actual) in originals.take(8).zip(f.inserted)) {
            assertEquals(original.submittedAt, actual.createdAt)
            assertEquals(original.original.text, actual.text)
            assertEquals(original.original.replyToEventId, actual.replyToEventId)
        }
        assertEquals(originals.takeLast(2).map { it.id }.toSet(), f.retained.keys)
        assertEquals(editing, f.composer.editor)
        assertEquals(8, f.cursorWrites)
    }

    @Test
    fun laterPassContinuesFromTheRetainedPositionWithoutDuplicateInsertion() = runTest {
        val f = SpaceComposerRecoveryFixture()
        val originals = f.retain(10)
        val recover = f.recover()
        assertEquals(AppResult.Success(CollaborationSpaceComposerRecovery(true, true)), recover())
        assertEquals(AppResult.Success(CollaborationSpaceComposerRecovery(false, true)), recover())
        assertEquals(originals.map { it.id }.toSet(), f.inserted.map { it.id }.toSet())
        assertEquals(10, f.inserted.size)
        assertTrue(f.retained.isEmpty())
        val count = f.inserted.size
        assertEquals(AppResult.Success(CollaborationSpaceComposerRecovery(false, true)), recover())
        assertEquals(count, f.inserted.size)
    }

    @Test
    fun unavailableConversationIsHeldWhileOtherRoomsContinue() = runTest {
        val f = SpaceComposerRecoveryFixture()
        val values = f.retain(2)
        val missing =
            values
                .first()
                .copy(original = values.first().original.copy(conversationId = deliveryId(5000)))
        f.retained[missing.id] = missing
        f.missingConversations += missing.original.conversationId
        assertEquals(
            AppResult.Success(CollaborationSpaceComposerRecovery(false, true)),
            f.recover()(),
        )
        assertEquals(setOf(missing.id), f.held)
        assertEquals(mapOf(missing.id to missing), f.retained)
        assertEquals(listOf(values.last().id), f.inserted.map { it.id })
        val before = f.metadataReads
        assertEquals(
            AppResult.Success(CollaborationSpaceComposerRecovery(false, true)),
            f.recover()(),
        )
        assertEquals(before, f.metadataReads)
    }

    @Test
    fun exactExistingActiveAndTerminalCommandsAuthorizeRetirementWithoutInsertion() = runTest {
        for (state in
            listOf(CollaborationMessageState.Queued, CollaborationMessageState.Cancelled)) {
            val f = SpaceComposerRecoveryFixture()
            val original = f.retain(1).single()
            f.records[original.id] =
                CollaborationSpaceMessageRecord(
                    collaborationSpaceComposerIntent(f.access, original),
                    0,
                    state,
                )
            f.base.canonical = null
            assertEquals(
                AppResult.Success(
                    CollaborationSpaceComposerRecovery(
                        false,
                        state == CollaborationMessageState.Queued,
                    )
                ),
                f.recover()(),
            )
            assertEquals(listOf(original), f.retired)
            assertTrue(f.inserted.isEmpty() && f.retained.isEmpty())
            assertEquals(0, f.metadataReads)
        }
    }

    @Test
    fun changedOrMalformedExistingCommandKeepsTheOriginalForReview() = runTest {
        for (malformed in listOf(false, true)) {
            val f = SpaceComposerRecoveryFixture()
            val original = f.retain(1).single()
            val intent = collaborationSpaceComposerIntent(f.access, original)
            val record =
                CollaborationSpaceMessageRecord(
                    if (malformed) intent
                    else intent.copy(createdAt = intent.createdAt + 1.milliseconds),
                    if (malformed) -1L else 0L,
                    CollaborationMessageState.Queued,
                )
            f.records[original.id] = record
            val result = f.recover()()
            if (malformed)
                assertEquals(FailureKind.Conflict, assertIs<AppResult.Failed>(result).failure.kind)
            else
                assertEquals(
                    AppResult.Success(CollaborationSpaceComposerRecovery(false, true)),
                    result,
                )
            assertEquals(original, f.retained[original.id])
            assertEquals(record, f.records[original.id])
            assertEquals(setOf(original.id), f.held)
            assertTrue(f.inserted.isEmpty() && f.retired.isEmpty())
        }
    }

    @Test
    fun disappearedOutboxEntryNeverAuthorizesAnotherInsertion() = runTest {
        val f = SpaceComposerRecoveryFixture()
        f.retain(1)
        f.beforeOutboxRead = { f.retained.remove(it) }
        assertEquals(
            AppResult.Success(CollaborationSpaceComposerRecovery(false, false)),
            f.recover()(),
        )
        assertTrue(f.inserted.isEmpty() && f.retired.isEmpty())
        assertEquals(0, f.messageReads)
    }

    @Test
    fun lostInsertReplyIsConfirmedByTheExactRetainedCommand() = runTest {
        val f = SpaceComposerRecoveryFixture()
        val original = f.retain(1).single()
        f.onInsert = {
            f.records[it.id] =
                CollaborationSpaceMessageRecord(it, 0, CollaborationMessageState.Queued)
            AppResult.Failed(f.base.base.storageFailure)
        }
        assertEquals(
            AppResult.Success(CollaborationSpaceComposerRecovery(false, true)),
            f.recover()(),
        )
        assertEquals(listOf(original), f.retired)
        assertEquals(1, f.inserted.size)
        assertEquals(original.submittedAt, f.records[original.id]!!.intent.createdAt)
        assertTrue(f.retained.isEmpty())
    }

    @Test
    fun changedReadbackTimestampCannotRetireTheOriginal() = runTest {
        val f = SpaceComposerRecoveryFixture()
        val original = f.retain(1).single()
        f.onInsert = {
            f.records[it.id] =
                CollaborationSpaceMessageRecord(
                    it.copy(createdAt = it.createdAt + 1.milliseconds),
                    0,
                    CollaborationMessageState.Queued,
                )
            AppResult.Success(CollaborationMessageInsertion.STORED)
        }
        assertEquals(
            AppResult.Success(CollaborationSpaceComposerRecovery(false, true)),
            f.recover()(),
        )
        assertEquals(original, f.retained[original.id])
        assertEquals(setOf(original.id), f.held)
        assertTrue(f.retired.isEmpty())
    }

    @Test
    fun fullMessageQueueLeavesTheOriginalAndAdvancesTheFairnessCursor() = runTest {
        val f = SpaceComposerRecoveryFixture()
        val original = f.retain(1).single()
        f.onInsert = { AppResult.Success(CollaborationMessageInsertion.CAPACITY_EXCEEDED) }
        val failure = assertIs<AppResult.Failed>(f.recover()()).failure
        assertEquals("client.command_queue_full", failure.code)
        assertEquals(FailureKind.Storage, failure.kind)
        assertEquals(original.id, f.cursor?.afterSubmissionId)
        assertEquals(original, f.retained[original.id])
        assertTrue(f.retired.isEmpty() && f.records.isEmpty())
        assertTrue(f.positions.size <= 3)
    }

    @Test
    fun oneStorageFailureDoesNotStarveOtherOriginalsInTheSameBoundedPass() = runTest {
        val f = SpaceComposerRecoveryFixture()
        val originals = f.retain(2).sortedBy { it.id }
        f.beforeMessageRead = {
            f.readFailure = if (it == originals.first().id) f.base.base.storageFailure else null
        }
        assertEquals(AppResult.Failed(f.base.base.storageFailure), f.recover()())
        assertEquals(listOf(originals.last().id), f.inserted.map { it.id })
        assertEquals(originals.last().id, f.cursor?.afterSubmissionId)
        assertEquals(mapOf(originals.first().id to originals.first()), f.retained)
        f.beforeMessageRead = { f.readFailure = null }
        assertEquals(
            AppResult.Success(CollaborationSpaceComposerRecovery(false, true)),
            f.recover()(),
        )
        assertTrue(f.retained.isEmpty())
        assertEquals(2, f.inserted.size)
    }

    @Test
    fun lostRetirementReplyDoesNotCreateAReplacementOnTheNextPass() = runTest {
        val f = SpaceComposerRecoveryFixture()
        val original = f.retain(1).single()
        f.onRemove = {
            f.retained.remove(it.id)
            AppResult.Failed(f.base.base.storageFailure)
        }
        assertEquals(AppResult.Failed(f.base.base.storageFailure), f.recover()())
        assertTrue(f.retained.isEmpty())
        assertEquals(
            AppResult.Success(CollaborationSpaceComposerRecovery(false, true)),
            f.recover()(),
        )
        assertEquals(listOf(original.id), f.inserted.map { it.id })
    }
}
