package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.collaborationComposerIntent
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.RecoverCollaborationComposer
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest

class RecoverCollaborationComposerTest {
    @Test
    fun offlineRecoveryRetainsOriginalIdsAndTimeWhenIndexOrderDiffersFromSendOrder() = runTest {
        val f = ComposerRecoveryFixture()
        val a = f.add(9)
        val b = f.add(1).copy(submittedAt = a.submittedAt + 1.seconds)
        f.retained[b.id] = b
        f.base.setup.now += 365.days
        assertEquals(AppResult.Success(false), f.recover())
        assertTrue(f.retained.isEmpty())
        assertEquals(
            listOf(a.id, b.id),
            f.base.records.value.sortedBy { it.intent.createdAt }.map { it.intent.id },
        )
        assertEquals(
            setOf(
                collaborationComposerIntent(f.access, a),
                collaborationComposerIntent(f.access, b),
            ),
            f.base.records.value.map { it.intent }.toSet(),
        )
        assertEquals(0, f.base.setup.calls)
        assertTrue(f.base.scheduledKeys.isEmpty())
    }

    @Test
    fun boundedPagesAdvanceAcrossNineteenOriginalsWithoutReplayingEarlierCommands() = runTest {
        val f = ComposerRecoveryFixture()
        repeat(19) { f.add(it) }
        assertEquals(AppResult.Success(true), f.recover())
        assertEquals(8, f.base.insertions)
        assertEquals(AppResult.Success(true), f.recover())
        assertEquals(16, f.base.insertions)
        assertEquals(AppResult.Success(false), f.recover())
        assertEquals(19, f.base.insertions)
        assertTrue(f.retained.isEmpty())
        assertEquals(19, f.advances)
        assertTrue(f.pages.all { it <= 8 })
    }

    @Test
    fun missingConversationIsHeldWhileIndependentOriginalIsMaterialized() = runTest {
        val f = ComposerRecoveryFixture()
        val missing =
            f.add(0).let { it.copy(original = it.original.copy(conversationId = deliveryId(800))) }
        f.retained[missing.id] = missing
        val good = f.add(1)
        assertEquals(AppResult.Success(false), f.recover())
        assertEquals(setOf(missing.id), f.held)
        assertEquals(listOf(good.id), f.base.records.value.map { it.intent.id })
        assertEquals(missing, f.retained[missing.id])
        repeat(3) { assertEquals(AppResult.Success(false), f.recover()) }
        assertEquals(1, f.base.insertions)
    }

    @Test
    fun exactArchivedCancellationRetiresOriginalWithoutRequiringConversationMetadata() = runTest {
        val f = ComposerRecoveryFixture()
        val original = f.add()
        f.base.records.value =
            listOf(
                CollaborationMessageRecord(
                    collaborationComposerIntent(f.access, original),
                    3,
                    CollaborationMessageState.Cancelled,
                )
            )
        f.base.summary = null
        assertEquals(AppResult.Success(false), f.recover())
        assertTrue(f.retained.isEmpty())
        assertEquals(0, f.base.insertions)
    }

    @Test
    fun conflictingCommandKeepsOriginalForReviewWithoutOverwritingIt() = runTest {
        for (timestamp in listOf(false, true)) {
            val f = ComposerRecoveryFixture()
            val original = f.add()
            val intent = collaborationComposerIntent(f.access, original)
            val conflict =
                if (timestamp) intent.copy(createdAt = intent.createdAt + 1.seconds)
                else intent.copy(text = "Another original")
            f.base.records.value =
                listOf(CollaborationMessageRecord(conflict, 0, CollaborationMessageState.Queued))
            assertEquals(AppResult.Success(false), f.recover())
            assertEquals(setOf(original.id), f.held)
            assertEquals(conflict, f.base.records.value.single().intent)
            assertEquals(0, f.base.insertions)
        }
    }

    @Test
    fun committedInsertionWithLostReplyUsesExactCommandAndDoesNotSendAgain() = runTest {
        val f = ComposerRecoveryFixture()
        val original = f.add()
        f.base.insertResult = AppResult.Failed(f.base.storage)
        assertEquals(AppResult.Success(false), f.recover())
        assertEquals(1, f.base.insertions)
        assertEquals(original.id, f.base.records.value.single().intent.id)
        assertTrue(f.retained.isEmpty())
        assertEquals(AppResult.Success(false), f.recover())
        assertEquals(1, f.base.insertions)
    }

    @Test
    fun cancellationAfterMessageCommitLeavesOriginalForExactRecovery() = runTest {
        val f = ComposerRecoveryFixture()
        val original = f.add()
        val committed = CompletableDeferred<Unit>()
        f.base.afterInsert = {
            committed.complete(Unit)
            awaitCancellation()
        }
        val first = launch { f.recover() }
        committed.await()
        first.cancelAndJoin()
        assertEquals(original, f.retained[original.id])
        assertEquals(1, f.base.insertions)
        f.base.afterInsert = {}
        assertEquals(AppResult.Success(false), f.recover())
        assertTrue(f.retained.isEmpty())
        assertEquals(1, f.base.insertions)
    }

    @Test
    fun capacityRetainsOriginalWithoutHoldingItAndLaterPassCanMaterialize() = runTest {
        val f = ComposerRecoveryFixture()
        val original = f.add()
        f.base.insertResult = AppResult.Success(CollaborationMessageInsertion.CAPACITY_EXCEEDED)
        assertEquals(
            "client.command_queue_full",
            assertIs<AppResult.Failed>(f.recover()).failure.code,
        )
        assertEquals(original, f.retained[original.id])
        assertTrue(f.held.isEmpty())
        f.base.insertResult = AppResult.Success(CollaborationMessageInsertion.STORED)
        assertEquals(AppResult.Success(false), f.recover())
        assertEquals(1, f.base.records.value.size)
    }

    @Test
    fun removalResponseLossDoesNotRequireAnotherInsertion() = runTest {
        val f = ComposerRecoveryFixture()
        f.add()
        f.afterRemove = { throw CancellationException("Lost completion") }
        assertFailsWith<CancellationException> { f.recover() }
        assertTrue(f.retained.isEmpty())
        f.afterRemove = {}
        assertEquals(AppResult.Success(false), f.recover())
        assertEquals(1, f.base.insertions)
    }

    @Test
    fun denialOrReplacementAfterCommitCannotRetireOrPublishThroughOldAccess() = runTest {
        for (replacement in listOf(false, true)) {
            val f = ComposerRecoveryFixture()
            val original = f.add()
            f.base.afterInsert = {
                if (replacement)
                    f.base.setup.session =
                        f.base.setup.session!!.copy(
                            user = f.base.setup.session!!.user.copy(id = deliveryId(777))
                        )
                else f.base.gate.value = CollaborationListAdmission("denied", f.base.denied)
            }
            assertIs<AppResult.Failed>(f.recover())
            assertEquals(original, f.retained[original.id])
            assertEquals(0, f.removes)
        }
    }

    @Test
    fun malformedOrDuplicateIndexedOriginalsDoNotAdvanceOrCreateCommands() = runTest {
        for (duplicate in listOf(false, true)) {
            val f = ComposerRecoveryFixture()
            val original = f.add()
            f.pageOverride =
                if (duplicate) listOf(original, original)
                else listOf(original.copy(nextRevision = original.id))
            assertIs<AppResult.Failed>(f.recover())
            assertEquals(0, f.advances)
            assertEquals(0, f.base.insertions)
        }
    }

    @Test
    fun changedCursorHasNoInsertionAndNextPassKeepsOriginal() = runTest {
        val f = ComposerRecoveryFixture()
        val original = f.add()
        f.rejectAdvance = true
        assertIs<AppResult.Failed>(f.recover())
        assertEquals(0, f.base.insertions)
        assertEquals(original, f.retained[original.id])
        f.rejectAdvance = false
        assertEquals(AppResult.Success(false), f.recover())
    }

    @Test
    fun racingCommandAfterMissingMetadataRetiresWithoutHoldingOrInserting() = runTest {
        val f = ComposerRecoveryFixture()
        val original = f.add()
        f.base.summary = null
        f.base.afterConversation = {
            f.base.records.value =
                listOf(
                    CollaborationMessageRecord(
                        collaborationComposerIntent(f.access, original),
                        0,
                        CollaborationMessageState.Queued,
                    )
                )
        }
        assertEquals(AppResult.Success(false), f.recover())
        assertTrue(f.held.isEmpty())
        assertTrue(f.retained.isEmpty())
        assertEquals(0, f.base.insertions)
    }

    @Test
    fun capturedCompanyRecoveryDoesNotNeedCurrentSelection() = runTest {
        val f = ComposerRecoveryFixture()
        f.add()
        f.base.setup.workspace = null
        assertEquals(
            AppResult.Success(false),
            f.operation(CollaborationLocalScope.Captured(f.access)),
        )
        assertEquals(1, f.base.insertions)
    }
}

private class ComposerRecoveryFixture {
    val base = MessageIntentFixture()
    val access =
        CollaborationLocalAccess(
            "local",
            base.server.audience,
            base.summary!!.conversation.firstAccountId,
            base.summary!!.conversation.companyId,
        )
    val conversation = base.summary!!.conversation.id
    val retained = mutableMapOf<String, CollaborationComposerSubmission>()
    val held = mutableSetOf<String>()
    val pages = mutableListOf<Int>()
    var cursor: CollaborationComposerRecoveryCursor? = null
    var advances = 0
    var removes = 0
    var rejectAdvance = false
    var pageOverride: List<CollaborationComposerSubmission>? = null
    var afterRemove: suspend () -> Unit = {}
    val outbox =
        object : CollaborationComposerOutboxRepository {
            override suspend fun get(access: CollaborationLocalAccess, id: String) =
                AppResult.Success(retained[id])

            override suspend fun handoff(
                access: CollaborationLocalAccess,
                submission: CollaborationComposerSubmission,
                expected: CollaborationDraft?,
            ): AppResult<CollaborationComposerHandoffResult> =
                error("Recovery cannot resubmit or clear the editor")

            override fun observe(
                access: CollaborationLocalAccess
            ): Flow<AppResult<List<CollaborationComposerOutboxEntry>>> =
                error("Recovery reads bounded index pages")

            override suspend fun pending(
                access: CollaborationLocalAccess,
                after: String?,
                limit: Int,
            ): AppResult<CollaborationComposerSubmissionPage> {
                val found =
                    pageOverride
                        ?: retained.values
                            .filter { it.id !in held && (after == null || it.id > after) }
                            .sortedBy { it.id }
                            .take(limit + 1)
                val selected = found.take(limit)
                pages += selected.size
                return AppResult.Success(
                    CollaborationComposerSubmissionPage(
                        selected,
                        if (found.size > limit) selected.last().id else null,
                    )
                )
            }

            override suspend fun hold(
                access: CollaborationLocalAccess,
                expected: CollaborationComposerSubmission,
            ): AppResult<Boolean> {
                if (retained[expected.id] != expected) return AppResult.Success(false)
                held += expected.id
                return AppResult.Success(true)
            }

            override suspend fun remove(
                access: CollaborationLocalAccess,
                expected: CollaborationComposerSubmission,
            ): AppResult<Boolean> {
                if (retained[expected.id] != expected) return AppResult.Success(false)
                retained.remove(expected.id)
                held -= expected.id
                removes++
                afterRemove()
                return AppResult.Success(true)
            }
        }
    val cursors =
        object : CollaborationComposerRecoveryCursorRepository {
            override suspend fun current(access: CollaborationLocalAccess) =
                AppResult.Success(cursor)

            override suspend fun advance(
                access: CollaborationLocalAccess,
                expected: CollaborationComposerRecoveryCursor?,
                submissionId: String,
            ): AppResult<CollaborationComposerRecoveryCursor?> {
                if (rejectAdvance || expected != cursor) return AppResult.Success(null)
                cursor =
                    CollaborationComposerRecoveryCursor(
                        deliveryId(50_000 + ++advances),
                        submissionId,
                    )
                return AppResult.Success(cursor)
            }
        }
    val operation =
        RecoverCollaborationComposer(
            outbox,
            cursors,
            base.messages,
            base.lists,
            base.setup.sessions,
            base.setup.workspaces,
            base.server,
            base.build,
            base.clock,
        )

    fun add(index: Int = 0): CollaborationComposerSubmission {
        val value =
            CollaborationComposerSubmission(
                CollaborationDraft(
                    conversation,
                    deliveryId(10_000 + index),
                    "Message $index",
                    base.setup.now,
                ),
                deliveryId(20_000 + index),
                base.setup.now + index.seconds,
            )
        retained[value.id] = value
        return value
    }

    suspend fun recover() = operation(CollaborationLocalScope.Selected("selection"))
}
