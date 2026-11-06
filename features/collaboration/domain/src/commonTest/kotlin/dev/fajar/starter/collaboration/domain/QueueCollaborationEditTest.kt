package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.QueueCollaborationEdit
import dev.fajar.starter.collaboration.domain.usecases.QueueCollaborationText
import dev.fajar.starter.common.result.AppResult
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class QueueCollaborationEditTest {
    @Test
    fun aRecentOwnedEditQueuesWithExpiredCredentialsWithoutTransportOrDraftMutation() = runTest {
        val f = EditQueueFixture()
        f.owner.setup.now += 365.days
        f.reviewed =
            f.reviewed.copy(
                target =
                    f.reviewed.target.copy(
                        targetAcceptedAt = f.owner.clock.now().toEpochMilliseconds() - 300_000,
                        firstAcceptedAt = f.owner.clock.now().toEpochMilliseconds() - 300_000,
                    )
            )
        f.currentCandidate = f.reviewed
        val record = assertIs<AppResult.Success<CollaborationMessageRecord>>(f.queue()).value
        assertEquals(f.reviewed.target, record.intent.editTarget)
        assertEquals(f.text, record.intent.text)
        assertEquals(CollaborationMessageState.Queued, record.state)
        assertEquals(0, f.owner.setup.calls)
        assertEquals(1, f.owner.insertions)
        assertEquals(listOf("hris.collaboration-delivery"), f.owner.scheduledKeys)
    }

    @Test
    fun aLostStorageResponseRecoversTheExactOperationAfterTheEditWindowExpires() = runTest {
        val f = EditQueueFixture()
        f.owner.insertResult = AppResult.Failed(f.owner.storage)
        assertIs<AppResult.Failed>(f.queue())
        val stored = f.owner.records.value.single()
        f.owner.setup.now += 1.days
        f.owner.insertResult = AppResult.Success(CollaborationMessageInsertion.STORED)
        assertEquals(AppResult.Success(stored), f.queue())
        assertEquals(1, f.owner.insertions)
        assertIs<AppResult.Failed>(f.queue(text = "Different input"))
        assertEquals(listOf(stored), f.owner.records.value)
    }

    @Test
    fun repeatedConcurrentTapsPublishOnlyOneImmutableIntent() = runTest {
        val f = EditQueueFixture()
        val a = async { f.queue() }
        val b = async { f.queue() }
        assertEquals(a.await(), b.await())
        assertEquals(1, f.owner.insertions)
    }

    @Test
    fun changedTextEarlierAliasesForeignIdentityAndHiddenTargetsRejectNewEdits() = runTest {
        for (kind in 0..4) {
            val f = EditQueueFixture()
            when (kind) {
                0 -> f.currentCandidate = f.reviewed.copy(text = "New remote edit")
                1 ->
                    f.currentCandidate =
                        f.reviewed.copy(
                            target =
                                f.reviewed.target.copy(
                                    firstAcceptedAt = f.reviewed.target.firstAcceptedAt - 1
                                )
                        )
                2 -> f.identity = CollaborationSelfIdentity("@other:example.test")
                3 -> f.currentCandidate = null
                4 -> f.owner.gate.value = CollaborationListAdmission("denied", f.owner.denied)
            }
            assertIs<AppResult.Failed>(f.queue())
            assertEquals(0, f.owner.insertions)
        }
    }

    @Test
    fun lateOwnerReplacementDoesNotAcknowledgeAnOldCommitOrEraseIt() = runTest {
        val f = EditQueueFixture()
        f.owner.afterInsert = { f.owner.setup.session = null }
        assertIs<AppResult.Failed>(f.queue())
        assertEquals(1, f.owner.records.value.size)
        assertTrue(f.owner.scheduledKeys.isEmpty())
        val before = EditQueueFixture()
        before.afterTarget = { before.owner.setup.session = null }
        assertIs<AppResult.Failed>(before.queue())
        assertEquals(0, before.owner.insertions)
    }

    @Test
    fun cancellationAfterInsertionLeavesRecoverableWorkAndDrainsTheCallback() = runTest {
        val f = EditQueueFixture()
        val entered = CompletableDeferred<Unit>()
        val released = CompletableDeferred<Unit>()
        f.owner.afterInsert = {
            entered.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                released.complete(Unit)
            }
        }
        val pending = async { f.queue() }
        entered.await()
        pending.cancelAndJoin()
        assertTrue(released.isCompleted)
        val retained = f.owner.records.value.single()
        f.owner.afterInsert = {}
        assertEquals(AppResult.Success(retained), f.queue())
        assertEquals(1, f.owner.insertions)
    }

    @Test
    fun aBackwardClockAdjustmentCannotReverseConsecutiveEdits() = runTest {
        val f = EditQueueFixture()
        val first = assertIs<AppResult.Success<CollaborationMessageRecord>>(f.queue()).value
        f.owner.setup.now -= 1.minutes
        val second =
            assertIs<AppResult.Success<CollaborationMessageRecord>>(
                    f.queue(id = editQueueId(2), text = "Another change")
                )
                .value
        assertTrue(second.intent.createdAt > first.intent.createdAt)
        assertNotEquals(first.intent.id, second.intent.id)
    }

    @Test
    fun anEditCannotRecoverANormalSendUsingTheSameOperationId() = runTest {
        val f = EditQueueFixture()
        val normal = assertIs<AppResult.Success<CollaborationMessageRecord>>(f.owner.queue()).value
        assertIs<AppResult.Failed>(f.queue(id = normal.intent.id, text = normal.intent.text))
        assertEquals(listOf(normal), f.owner.records.value)
        assertEquals(1, f.owner.insertions)
    }

    @Test
    fun aNormalSendCannotRecoverAnEditWithOtherwiseIdenticalContent() = runTest {
        val f = EditQueueFixture()
        f.reviewed =
            f.reviewed.copy(
                target = f.reviewed.target.copy(replyToEventId = null, forwarded = false)
            )
        f.currentCandidate = f.reviewed
        val edit = assertIs<AppResult.Success<CollaborationMessageRecord>>(f.queue()).value
        val normal =
            QueueCollaborationText(
                f.owner.messages,
                f.owner.lists,
                f.owner.setup.sessions,
                f.owner.setup.workspaces,
                f.owner.server,
                f.owner.build,
                f.owner.clock,
                f.owner.schedules,
            )
        assertIs<AppResult.Failed>(
            normal("selection", f.conversation, edit.intent.id, edit.intent.text)
        )
        assertEquals(listOf(edit), f.owner.records.value)
        assertEquals(1, f.owner.insertions)
    }
}

private class EditQueueFixture {
    val owner = MessageIntentFixture()
    val access =
        CollaborationLocalAccess(
            owner.setup.session!!.id,
            owner.server.audience,
            owner.setup.session!!.user.id,
            owner.setup.workspace!!.company.id,
        )
    val conversation = owner.summary!!.conversation.id
    val text = " Edited offline 🙂\n"
    var reviewed =
        CollaborationEditCandidate(
            CollaborationEditTarget(
                "!room:example.test",
                "\$original",
                editQueueId(90),
                "@self:example.test",
                "a".repeat(64),
                owner.clock.now().toEpochMilliseconds() - 300_000,
                owner.clock.now().toEpochMilliseconds() - 300_000,
                "\$quoted",
                true,
            ),
            "Original text",
        )
    var currentCandidate: CollaborationEditCandidate? = reviewed
    var identity: CollaborationSelfIdentity? = CollaborationSelfIdentity(reviewed.target.sender)
    var afterTarget: suspend () -> Unit = {}
    private val identities =
        object : CollaborationIdentityRepository {
            override suspend fun current(
                access: CollaborationLocalAccess
            ): AppResult<CollaborationSelfIdentity?> {
                assertEquals(this@EditQueueFixture.access, access)
                return AppResult.Success(identity)
            }

            override suspend fun retain(
                access: CollaborationLocalAccess,
                identity: CollaborationSelfIdentity,
            ): AppResult<Boolean> = error("Read only")
        }
    private val targets =
        object : CollaborationEditTargetRepository {
            override suspend fun getEditCandidate(
                access: CollaborationLocalAccess,
                conversationId: String,
                roomId: String,
                eventId: String,
            ): AppResult<CollaborationEditCandidate?> {
                assertEquals(this@EditQueueFixture.access, access)
                assertEquals(conversation, conversationId)
                assertEquals(reviewed.target.roomId, roomId)
                assertEquals(reviewed.target.eventId, eventId)
                val result = currentCandidate
                afterTarget()
                return AppResult.Success(result)
            }
        }
    private val sender =
        QueueCollaborationEdit(
            owner.messages,
            targets,
            identities,
            owner.lists,
            owner.setup.sessions,
            owner.setup.workspaces,
            owner.server,
            owner.build,
            owner.clock,
            owner.schedules,
        )

    suspend fun queue(id: String = editQueueId(1), text: String = this.text) =
        sender("selection", access, conversation, id, reviewed, text)
}

private fun editQueueId(i: Int) = "e1000000-0000-4000-8000-" + i.toString().padStart(12, '0')
