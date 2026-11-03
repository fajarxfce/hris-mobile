package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class CollaborationReviewedReplyTest {
    @Test
    fun mutableDraftIncludingEmptyTextDoesNotApproveOrQueue() = runTest {
        val f = Fixture()
        for (text in listOf("Edited fictional reply", "")) {
            val saved = f.value(f.save("selection", f.access, f.parent(), f.current(), text))
            assertEquals(text, saved.draft)
            assertNull(saved.resolution)
            assertEquals(f.base.expected.candidate, saved.candidate)
        }
        assertTrue(f.commands.isEmpty())
        assertIs<AppResult.Failed>(
            f.confirm("selection", f.access, f.parent(), f.current(), f.id(600), "")
        )
    }

    @Test
    fun explicitConfirmationFencesOriginalBeforeOneImmutableEditedCommand() = runTest {
        val f = Fixture()
        val confirmed = f.confirm()
        assertNull(confirmed.review)
        assertTrue(f.commands.isEmpty())
        f.beforeInsert = {
            assertEquals(CollaborationMessageState.Cancelled, f.commands[f.originalId]?.state)
            assertEquals(confirmed, f.current())
        }
        assertQueued(f.value(f.resume(confirmed)))
        assertNull(f.base.saved)
        assertEquals("Edited reply", f.commands[f.id(600)]?.intent?.text)
        assertEquals(2, f.commands.size)
        assertEquals(listOf(QueueCollaborationText.DELIVERY_KEY), f.local.scheduledKeys)
    }

    @Test
    fun everyCommittedBoundaryRecoversWithTheSameOperationAndNoSecondSend() = runTest {
        for (boundary in listOf("confirm", "fence", "insert", "remove")) {
            val f = Fixture()
            val crash: suspend () -> Unit = {
                throw CancellationException("Simulated process interruption")
            }
            when (boundary) {
                "confirm" -> f.afterConfirm = crash
                "fence" -> f.afterFence = crash
                "insert" -> f.afterInsert = crash
                "remove" -> f.base.afterRemove = crash
            }
            assertFailsWith<CancellationException> { f.resume(f.confirm()) }
            val storedResolution = f.base.saved?.resolution ?: f.lastResolution
            assertEquals(f.id(600), storedResolution?.operationId)
            f.afterConfirm = {}
            f.afterFence = {}
            f.afterInsert = {}
            f.base.afterRemove = {}
            f.base.saved?.let { assertQueued(f.value(f.resume(it))) }
            assertEquals(2, f.commands.size, boundary)
            assertEquals(CollaborationMessageState.Cancelled, f.commands[f.originalId]?.state)
            assertEquals(1, f.insertions)
        }
    }

    @Test
    fun oldCancellationCannotCompleteAnUnqueuedEditedResolution() = runTest {
        val f = Fixture()
        val confirmed = f.confirm()
        f.commands[f.originalId] = f.base.command(CollaborationMessageState.Cancelled)
        val complete =
            CompleteCollaborationNotificationReply(
                f.inputs,
                f.messages,
                f.local.lists,
                f.local.setup.sessions,
                f.local.setup.workspaces,
                f.local.server,
                f.local.build,
                f.local.clock,
            )
        assertEquals(
            AppResult.Success(CollaborationNotificationReplyCompletion.Pending),
            complete(confirmed),
        )
        assertEquals(confirmed, f.current())
        assertQueued(f.value(f.resume(confirmed)))
    }

    @Test
    fun replacementFamilyNeedsExplicitApprovalAndPreservesIdBodyAndTime() = runTest {
        val f = Fixture()
        val confirmed = f.confirm()
        f.base.native.replaceFamily()
        f.alignWorkspace()
        assertEquals(
            AppResult.Success(
                CollaborationReviewedReplyResult.NeedsReview(
                    CollaborationNotificationReplyReview.SessionChanged
                )
            ),
            f.resume(confirmed),
        )
        assertTrue(f.commands.isEmpty())
        val held = f.current()
        assertIs<AppResult.Failed>(
            f.confirm("selection", f.access, f.parent(), held, f.id(600), "Changed confirmed text")
        )
        val approved =
            f.value(f.confirm("selection", f.access, f.parent(), held, f.id(600), "Edited reply"))
        assertEquals(confirmed.resolution?.copy(parent = f.parent()), approved.resolution)
        assertQueued(f.value(f.resume(approved)))
    }

    @Test
    fun existingNewCommandCanBeRecognizedAcrossFamilyChangeWithoutAnotherGrant() = runTest {
        val f = Fixture()
        val confirmed = f.confirm()
        f.afterInsert = { throw CancellationException("Lost queue response") }
        assertFailsWith<CancellationException> { f.resume(confirmed) }
        f.base.native.replaceFamily()
        f.alignWorkspace()
        f.afterInsert = {}
        assertQueued(f.value(f.resume(f.current())))
        assertEquals(1, f.insertions)
        assertNull(f.base.saved)
    }

    @Test
    fun startedOriginalHoldsEditAndExplicitEditOnlyDiscardNeverCancelsOriginal() = runTest {
        val f = Fixture()
        val original =
            f.base.command(CollaborationMessageState.Staging(f.base.device(), emptySet()))
        f.commands[f.originalId] = original
        val confirmed = f.confirm()
        assertEquals(
            AppResult.Success(
                CollaborationReviewedReplyResult.NeedsReview(
                    CollaborationNotificationReplyReview.OriginalProcessing
                )
            ),
            f.resume(confirmed),
        )
        assertEquals(
            AppResult.Success(CollaborationReplyDiscard.AlreadyProcessing),
            f.discard("selection", f.access, f.current()),
        )
        assertEquals(
            AppResult.Success(CollaborationReplyDiscard.EditedDraftDiscarded),
            f.discard(
                "selection",
                f.access,
                f.current(),
                CollaborationReplyDiscardMode.EditedDraftOnly,
                f.parent(),
            ),
        )
        assertEquals(original, f.commands[f.originalId])
        assertEquals(CollaborationMessageState.Cancelled, f.commands[f.id(600)]?.state)
        assertNull(f.base.saved)
        assertEquals(0, f.insertions)
    }

    @Test
    fun discardBothOperationIdsPreventsAStaleResumeFromCreatingWork() = runTest {
        val f = Fixture()
        val confirmed = f.confirm()
        assertEquals(
            AppResult.Success(CollaborationReplyDiscard.Discarded),
            f.discard("selection", f.access, confirmed),
        )
        assertEquals(setOf(f.originalId, f.id(600)), f.commands.keys)
        assertTrue(f.commands.values.all { it.state == CollaborationMessageState.Cancelled })
        assertEquals(
            AppResult.Success(CollaborationReviewedReplyResult.Absent),
            f.resume(confirmed),
        )
        assertEquals(0, f.insertions)
    }

    @Test
    fun differentOriginalAndDifferentNewBodiesStayIntact() = runTest {
        for (newCommand in listOf(false, true)) {
            val f = Fixture()
            val confirmed = f.confirm()
            val id = if (newCommand) f.id(600) else f.originalId
            val record =
                f.base.command().let {
                    it.copy(intent = it.intent.copy(id = id, text = "Another message"))
                }
            f.commands[id] = record
            assertEquals(
                AppResult.Success(
                    CollaborationReviewedReplyResult.NeedsReview(
                        CollaborationNotificationReplyReview.MessageChanged
                    )
                ),
                f.resume(confirmed),
            )
            assertEquals(record, f.commands[id])
            assertNotNull(f.base.saved)
            assertEquals(0, f.insertions)
        }
    }

    @Test
    fun familyReplacementAfterFenceRetainsResolutionAndNeverQueues() = runTest {
        val f = Fixture()
        val confirmed = f.confirm()
        f.afterFence = { f.base.native.replaceFamily() }
        assertIs<AppResult.Failed>(f.resume(confirmed))
        assertEquals(confirmed, f.current())
        assertEquals(0, f.insertions)
        assertEquals(CollaborationMessageState.Cancelled, f.commands[f.originalId]?.state)
    }

    @Test
    fun timeoutJoinsPendingFenceAndKeepsTheConfirmedInput() = runTest {
        val f = Fixture()
        var joined = false
        f.afterFence = {
            try {
                delay(8_001)
            } finally {
                joined = true
            }
        }
        val confirmed = f.confirm()
        assertEquals(
            FailureKind.Timeout,
            assertIs<AppResult.Failed>(f.resume(confirmed)).failure.kind,
        )
        assertTrue(joined)
        assertEquals(confirmed, f.current())
        assertEquals(0, f.insertions)
    }

    @Test
    fun duplicateCallbackCompletionPreservesWorkingEditIncludingEmptyDraft() = runTest {
        for (text in listOf("Private edit", "")) {
            val f = Fixture()
            val input = f.value(f.save("selection", f.access, f.parent(), f.current(), text))
            f.commands[f.originalId] = f.base.command(CollaborationMessageState.Cancelled)
            val complete =
                CompleteCollaborationNotificationReply(
                    f.inputs,
                    f.messages,
                    f.local.lists,
                    f.local.setup.sessions,
                    f.local.setup.workspaces,
                    f.local.server,
                    f.local.build,
                    f.local.clock,
                )
            assertEquals(
                AppResult.Success(CollaborationNotificationReplyCompletion.Pending),
                complete(input),
            )
            assertEquals(input, f.current())
        }
    }

    @Test
    fun discardWinningAfterFinalInputReadPreventsTheLateEditedInsert() = runTest {
        val f = Fixture()
        val confirmed = f.confirm()
        f.beforeInsert = {
            assertEquals(
                AppResult.Success(CollaborationReplyDiscard.Discarded),
                f.discard("selection", f.access, f.current()),
            )
        }
        assertIs<AppResult.Failed>(f.resume(confirmed))
        assertNull(f.base.saved)
        assertEquals(0, f.insertions)
        assertEquals(setOf(f.originalId, f.id(600)), f.commands.keys)
        assertTrue(f.commands.values.all { it.state == CollaborationMessageState.Cancelled })
    }

    @Test
    fun newCommandStartingBeforeDiscardIsNeverClaimedCancelled() = runTest {
        val f = Fixture()
        val confirmed = f.confirm()
        f.afterInsert = {
            val queued = assertNotNull(f.commands[f.id(600)])
            f.commands[f.id(600)] =
                queued.copy(
                    revision = 1,
                    state = CollaborationMessageState.Staging(f.base.device(), emptySet()),
                )
            assertEquals(
                AppResult.Success(CollaborationReplyDiscard.AlreadyProcessing),
                f.discard("selection", f.access, f.current()),
            )
            assertNotNull(f.base.saved)
        }
        val result =
            assertIs<CollaborationReviewedReplyResult.CommandRetained>(f.value(f.resume(confirmed)))
        assertIs<CollaborationMessageState.Staging>(result.state)
        assertNull(f.base.saved)
        assertEquals(1, f.insertions)
    }

    private fun assertQueued(result: CollaborationReviewedReplyResult) {
        assertEquals(
            CollaborationMessageState.Queued,
            assertIs<CollaborationReviewedReplyResult.CommandRetained>(result).state,
        )
    }

    private class Fixture {
        val base = CollaborationNotificationReplyCompletionFixture()
        val local = base.local
        val access = base.native.scope().access
        val originalId = base.expected.candidate.action.operationId
        val commands = mutableMapOf<String, CollaborationMessageRecord>()
        var insertions = 0
        var beforeInsert: suspend () -> Unit = {}
        var afterInsert: suspend () -> Unit = {}
        var afterFence: suspend () -> Unit = {}
        var afterConfirm: suspend () -> Unit = {}
        var lastResolution: CollaborationNotificationReplyResolution? = null

        init {
            alignWorkspace()
            base.saved =
                base.expected.copy(review = CollaborationNotificationReplyReview.SessionChanged)
        }

        fun alignWorkspace() {
            local.setup.workspace =
                local.setup.workspace!!.let {
                    it.copy(admission = it.admission.copy(familyId = parent().nativeSessionId))
                }
        }

        fun parent() =
            local.setup.session!!.let {
                CollaborationNativeParent(
                    it.id,
                    it.binding.audience,
                    it.user.id,
                    it.binding.serverId,
                )
            }

        fun id(n: Int) = base.id(n)

        fun current() = assertNotNull(base.saved)

        fun <T> value(result: AppResult<T>) = assertIs<AppResult.Success<T>>(result).value

        val inputs =
            object : CollaborationNotificationReplyRepository by base.inputs {
                override suspend fun hold(
                    access: CollaborationRecoveryAccess,
                    expected: CollaborationNotificationReplyInput,
                    reason: CollaborationNotificationReplyReview,
                ): AppResult<Boolean> {
                    if (base.saved != expected) return AppResult.Success(false)
                    base.saved =
                        expected.copy(
                            version =
                                expected.version.copy(revision = expected.version.revision + 1),
                            review = reason,
                        )
                    return AppResult.Success(true)
                }
            }
        val resolutions =
            object : CollaborationNotificationReplyResolutionRepository {
                override suspend fun saveDraft(
                    access: CollaborationRecoveryAccess,
                    expected: CollaborationNotificationReplyInput,
                    text: String,
                ): AppResult<Boolean> {
                    if (base.saved != expected) return AppResult.Success(false)
                    base.saved =
                        expected.copy(
                            version =
                                expected.version.copy(revision = expected.version.revision + 1),
                            draft = text,
                        )
                    return AppResult.Success(true)
                }

                override suspend fun confirm(
                    access: CollaborationRecoveryAccess,
                    expected: CollaborationNotificationReplyInput,
                    resolution: CollaborationNotificationReplyResolution,
                ): AppResult<Boolean> {
                    if (base.saved != expected) return AppResult.Success(false)
                    lastResolution = resolution
                    base.saved =
                        expected.copy(
                            version =
                                expected.version.copy(revision = expected.version.revision + 1),
                            draft = null,
                            review = null,
                            resolution = resolution,
                        )
                    afterConfirm()
                    return AppResult.Success(true)
                }
            }
        val messages =
            object : CollaborationMessageRepository by base.messages {
                override suspend fun get(access: CollaborationLocalAccess, id: String) =
                    AppResult.Success(commands[id])

                override suspend fun insert(
                    access: CollaborationLocalAccess,
                    intent: CollaborationMessageIntent,
                ): AppResult<CollaborationMessageInsertion> {
                    beforeInsert()
                    if (!commands.containsKey(intent.id)) {
                        insertions++
                        commands[intent.id] =
                            CollaborationMessageRecord(intent, 0, CollaborationMessageState.Queued)
                    }
                    afterInsert()
                    return AppResult.Success(CollaborationMessageInsertion.STORED)
                }
            }
        val prevention =
            object : CollaborationMessagePreventionRepository {
                override suspend fun prevent(
                    access: CollaborationLocalAccess,
                    intent: CollaborationMessageIntent,
                ): AppResult<Boolean> {
                    if (commands.containsKey(intent.id)) return AppResult.Success(false)
                    commands[intent.id] =
                        CollaborationMessageRecord(intent, 0, CollaborationMessageState.Cancelled)
                    afterFence()
                    return AppResult.Success(true)
                }
            }
        val cancellations =
            object : CollaborationMessageCancellationRepository {
                override suspend fun cancel(
                    access: CollaborationLocalAccess,
                    expected: CollaborationMessageRecord,
                ): AppResult<Boolean> {
                    if (commands[expected.intent.id] != expected) return AppResult.Success(false)
                    commands[expected.intent.id] =
                        expected.copy(revision = 0, state = CollaborationMessageState.Cancelled)
                    return AppResult.Success(true)
                }
            }
        val save =
            SaveCollaborationReplyDraft(
                inputs,
                resolutions,
                local.lists,
                local.setup.sessions,
                local.setup.workspaces,
                local.server,
                local.build,
                local.clock,
            )
        val confirm =
            ConfirmCollaborationReply(
                inputs,
                resolutions,
                local.lists,
                local.setup.sessions,
                local.setup.workspaces,
                local.server,
                local.build,
                local.clock,
            )
        val resume =
            ResumeCollaborationReviewedReply(
                inputs,
                messages,
                cancellations,
                prevention,
                local.lists,
                local.setup.sessions,
                local.setup.workspaces,
                local.server,
                local.build,
                local.clock,
                local.schedules,
            )
        val discard =
            DiscardCollaborationNotificationReply(
                inputs,
                messages,
                cancellations,
                prevention,
                local.lists,
                local.setup.sessions,
                local.setup.workspaces,
                local.server,
                local.build,
                local.clock,
            )

        suspend fun confirm() =
            value(confirm("selection", access, parent(), current(), id(600), "Edited reply"))
    }
}
