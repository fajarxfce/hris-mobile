package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.collaborationComposerIntent
import dev.fajar.starter.collaboration.domain.repositories.CollaborationComposerOutboxRepository
import dev.fajar.starter.collaboration.domain.usecases.SubmitCollaborationComposer
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class SubmitCollaborationComposerTest {
    @Test
    fun unsavedOfflineGestureRetainsItsOriginalWithoutCreatingAMessageOrUsingTransport() = runTest {
        val f = ComposerSubmitFixture()
        f.base.setup.now += 365.days
        assertEquals(AppResult.Success(f.submission), f.submit())
        assertEquals(f.submission, f.retained[f.submission.id])
        assertEquals(f.submission.nextDraft, f.editor)
        assertEquals(1, f.writes)
        assertEquals(0, f.base.insertions)
        assertEquals(0, f.base.setup.calls)
        assertEquals(listOf("hris.collaboration-delivery"), f.base.scheduledKeys)
    }

    @Test
    fun replayCannotClearTextEnteredAfterTheOriginalHandoff() = runTest {
        val f = ComposerSubmitFixture()
        assertIs<AppResult.Success<CollaborationComposerSubmission>>(f.submit())
        val next = f.submission.nextDraft.copy(revision = deliveryId(910), text = "Next draft")
        f.editor = next
        assertEquals(AppResult.Success(f.submission), f.submit())
        assertEquals(next, f.editor)
        assertEquals(1, f.writes)
        assertEquals(1, f.retained.size)
    }

    @Test
    fun lostCommitReplyIsConfirmedByTheExactStoredOriginalWithoutAnotherWrite() = runTest {
        val f = ComposerSubmitFixture()
        f.afterWriteFailure = f.base.storage
        assertEquals(AppResult.Success(f.submission), f.submit())
        assertEquals(1, f.writes)
        assertEquals(f.submission.nextDraft, f.editor)
        assertEquals(1, f.base.scheduledKeys.size)
    }

    @Test
    fun anUnprovenStorageFailureKeepsItsClassificationAndDoesNotSchedule() = runTest {
        val f = ComposerSubmitFixture()
        f.beforeWriteFailure = f.base.storage
        assertEquals(f.base.storage, assertIs<AppResult.Failed>(f.submit()).failure)
        assertEquals(0, f.writes)
        assertNull(f.editor)
        assertTrue(f.retained.isEmpty())
        assertTrue(f.base.scheduledKeys.isEmpty())
    }

    @Test
    fun archivedOriginalProvesReplayButChangedOrderingOrContentCannotReuseItsId() = runTest {
        for (changed in 0..2) {
            val f = ComposerSubmitFixture()
            val intent = collaborationComposerIntent(f.access, f.submission)
            f.base.records.value =
                listOf(
                    CollaborationMessageRecord(
                        when (changed) {
                            1 -> intent.copy(createdAt = intent.createdAt + 1.seconds)
                            2 -> intent.copy(text = "Different body")
                            else -> intent
                        },
                        3,
                        CollaborationMessageState.Cancelled,
                    )
                )
            if (changed == 0) assertEquals(AppResult.Success(f.submission), f.submit())
            else assertIs<AppResult.Failed>(f.submit())
            assertEquals(0, f.writes)
            assertTrue(f.base.scheduledKeys.isEmpty())
        }
    }

    @Test
    fun recoveryBetweenLookupAndHandoffCannotCreateAnotherOriginal() = runTest {
        val f = ComposerSubmitFixture()
        f.beforeHandoff = {
            f.editor =
                f.submission.nextDraft.copy(revision = deliveryId(910), text = "Newer editor")
            f.base.records.value =
                listOf(
                    CollaborationMessageRecord(
                        collaborationComposerIntent(f.access, f.submission),
                        3,
                        CollaborationMessageState.Cancelled,
                    )
                )
        }
        assertEquals(AppResult.Success(f.submission), f.submit())
        assertEquals("Newer editor", f.editor?.text)
        assertEquals(0, f.writes)
        assertTrue(f.retained.isEmpty())
        assertTrue(f.base.scheduledKeys.isEmpty())
    }

    @Test
    fun retainedOriginalWithAnotherNextRevisionIsNotTheSameHandoff() = runTest {
        val f = ComposerSubmitFixture()
        f.retained[f.submission.id] = f.submission.copy(nextRevision = deliveryId(911))
        assertIs<AppResult.Failed>(f.submit())
        assertEquals(0, f.writes)
        assertTrue(f.base.scheduledKeys.isEmpty())
    }

    @Test
    fun localDenialOrReplacementAccountCannotPublishTheOriginal() = runTest {
        for (replacement in listOf(false, true)) {
            val f = ComposerSubmitFixture()
            if (replacement)
                f.base.setup.session =
                    f.base.setup.session!!.copy(
                        user = f.base.setup.session!!.user.copy(id = deliveryId(912))
                    )
            else f.base.gate.value = CollaborationListAdmission("denied", f.base.denied)
            assertIs<AppResult.Failed>(f.submit())
            assertEquals(0, f.writes)
            assertTrue(f.base.scheduledKeys.isEmpty())
        }
    }

    @Test
    fun replacementAfterCommitRetainsTheOriginalWithoutPublishingItsResultToTheNewAccount() =
        runTest {
            val f = ComposerSubmitFixture()
            f.afterHandoff = {
                f.base.setup.session =
                    f.base.setup.session!!.copy(
                        user = f.base.setup.session!!.user.copy(id = deliveryId(912))
                    )
            }
            assertEquals(
                FailureKind.Unauthorized,
                assertIs<AppResult.Failed>(f.submit()).failure.kind,
            )
            assertEquals(f.submission, f.retained[f.submission.id])
            assertEquals(1, f.writes)
            assertTrue(f.base.scheduledKeys.isEmpty())
        }

    @Test
    fun cancellationAfterCommitRetainsTheOriginalForAnExactRetry() = runTest {
        val f = ComposerSubmitFixture()
        f.afterHandoff = { currentCoroutineContext().cancel() }
        val waiting = launch { f.submit() }
        waiting.join()
        assertTrue(waiting.isCancelled)
        assertEquals(f.submission, f.retained[f.submission.id])
        assertTrue(f.base.scheduledKeys.isEmpty())
        f.afterHandoff = {}
        assertEquals(AppResult.Success(f.submission), f.submit())
        assertEquals(1, f.writes)
    }

    @Test
    fun failedOrTimedOutSchedulingDoesNotUndoTheCommittedGesture() = runTest {
        for (timeout in listOf(false, true)) {
            val f = ComposerSubmitFixture()
            f.base.schedule = {
                if (timeout) awaitCancellation() else AppResult.Failed(f.base.storage)
            }
            assertEquals(AppResult.Success(f.submission), f.submit())
            assertEquals(f.submission, f.retained[f.submission.id])
            assertEquals(1, f.writes)
        }
    }

    @Test
    fun changedEditorOrCapacityLeavesInputAndExistingOriginalsUntouched() = runTest {
        for (capacity in listOf(false, true)) {
            val f = ComposerSubmitFixture()
            val editor =
                f.submission.original.copy(revision = deliveryId(912), text = "Keep this text")
            f.editor = editor
            f.full = capacity
            val failure = assertIs<AppResult.Failed>(f.submit()).failure
            if (capacity) assertEquals("client.command_queue_full", failure.code)
            assertEquals(editor, f.editor)
            assertEquals(0, f.writes)
            assertTrue(f.base.scheduledKeys.isEmpty())
        }
    }
}

private class ComposerSubmitFixture {
    val base = MessageIntentFixture()
    val access =
        CollaborationLocalAccess(
            "local",
            base.server.audience,
            base.summary!!.conversation.firstAccountId,
            base.summary!!.conversation.companyId,
        )
    val submission =
        CollaborationComposerSubmission(
            CollaborationDraft(
                base.summary!!.conversation.id,
                deliveryId(901),
                " Original\n🙂 ",
                base.setup.now,
                replyToEventId = "\$quoted",
            ),
            deliveryId(902),
            base.setup.now,
        )
    var editor: CollaborationDraft? = null
    val retained = mutableMapOf<String, CollaborationComposerSubmission>()
    var writes = 0
    var full = false
    var beforeWriteFailure: Failure? = null
    var afterWriteFailure: Failure? = null
    var beforeHandoff: suspend () -> Unit = {}
    var afterHandoff: suspend () -> Unit = {}
    val outbox =
        object : CollaborationComposerOutboxRepository {
            override suspend fun get(access: CollaborationLocalAccess, id: String) =
                AppResult.Success(retained[id])

            override suspend fun handoff(
                access: CollaborationLocalAccess,
                submission: CollaborationComposerSubmission,
                expected: CollaborationDraft?,
            ): AppResult<CollaborationComposerHandoffResult> {
                beforeHandoff()
                beforeWriteFailure?.let {
                    return AppResult.Failed(it)
                }
                if (full)
                    return AppResult.Success(CollaborationComposerHandoffResult.CAPACITY_EXCEEDED)
                retained[submission.id]?.let {
                    return AppResult.Success(
                        if (it == submission) CollaborationComposerHandoffResult.RETAINED
                        else CollaborationComposerHandoffResult.CHANGED
                    )
                }
                if (editor != expected)
                    return AppResult.Success(CollaborationComposerHandoffResult.CHANGED)
                retained[submission.id] = submission
                editor = submission.nextDraft
                writes++
                afterHandoff()
                return afterWriteFailure?.let { AppResult.Failed(it) }
                    ?: AppResult.Success(CollaborationComposerHandoffResult.STORED)
            }

            override fun observe(
                access: CollaborationLocalAccess
            ): kotlinx.coroutines.flow.Flow<AppResult<List<CollaborationComposerOutboxEntry>>> =
                error("Submission does not observe other originals")

            override suspend fun hold(
                access: CollaborationLocalAccess,
                expected: CollaborationComposerSubmission,
            ): AppResult<Boolean> = error("Submission does not hold originals")

            override suspend fun pending(
                access: CollaborationLocalAccess,
                after: String?,
                limit: Int,
            ): AppResult<CollaborationComposerSubmissionPage> =
                error("Submission does not scan other originals")

            override suspend fun remove(
                access: CollaborationLocalAccess,
                expected: CollaborationComposerSubmission,
            ): AppResult<Boolean> =
                error("Only recovery retires a submission after exact command proof")
        }
    private val operation =
        SubmitCollaborationComposer(
            outbox,
            base.messages,
            base.lists,
            base.setup.sessions,
            base.setup.workspaces,
            base.server,
            base.build,
            base.clock,
            base.schedules,
        )

    suspend fun submit() = operation("selection", null, submission, access)
}
