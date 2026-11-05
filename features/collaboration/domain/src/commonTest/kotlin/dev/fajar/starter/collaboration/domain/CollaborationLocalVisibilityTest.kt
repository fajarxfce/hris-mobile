package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationLocalVisibilityRepository
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest

class CollaborationLocalVisibilityTest {
    @Test
    fun ownedOfflineDeletionUsesOneBatchAndRetriesOnlyOneLocalRace() = runTest {
        val f = VisibilityFixture()
        f.owner.setup.now += 365.days
        f.reject = 1
        assertEquals(AppResult.Success(Unit), f.delete())
        assertEquals(2, f.writes)
        assertEquals(AppResult.Success(f.messages.toSet()), f.observe().first())
    }

    @Test
    fun knownDenialFailedAdmissionAndForeignConversationNeverWrite() = runTest {
        val f = VisibilityFixture()
        f.owner.gate.value = CollaborationListAdmission("denied", f.owner.denied)
        assertEquals(AppResult.Failed(f.owner.denied), f.delete())
        assertEquals(AppResult.Failed(f.owner.denied), f.observe().first())
        f.owner.gateFailure = f.owner.storage
        assertEquals(AppResult.Failed(f.owner.storage), f.delete())
        f.owner.gate.value = CollaborationListAdmission("admitted", null)
        f.owner.gateFailure = null
        f.owner.summary = f.owner.summary!!.copy(peer = CollaborationParticipant("wrong", "Wrong"))
        assertIs<AppResult.Failed>(f.delete())
        assertEquals(0, f.writes)
        assertEquals(0, f.reads)
    }

    @Test
    fun replacementDuringConversationReadRejectsOldSelection() = runTest {
        val f = VisibilityFixture()
        f.owner.afterConversation = { f.owner.setup.session = null }
        assertIs<AppResult.Failed>(f.delete())
        assertEquals(0, f.writes)
    }

    @Test
    fun lateDenialAfterCommitPreservesFactButDoesNotPublishSuccess() = runTest {
        val f = VisibilityFixture()
        f.afterWrite = { f.owner.gate.value = CollaborationListAdmission("denied", f.owner.denied) }
        assertEquals(AppResult.Failed(f.owner.denied), f.delete())
        assertEquals(f.messages.toSet(), f.hidden.value)
        assertEquals(AppResult.Failed(f.owner.denied), f.observe().first())
        assertEquals(1, f.writes)
    }

    @Test
    fun cancellationAndFiniteStorageBudgetDoNotAcknowledgePendingWrites() = runTest {
        val f = VisibilityFixture()
        f.beforeWrite = { awaitCancellation() }
        assertEquals(
            "client.collaboration_local_delete_pending",
            assertIs<AppResult.Failed>(f.delete()).failure.code,
        )
        assertTrue(f.hidden.value.isEmpty())
        val entered = CompletableDeferred<Unit>()
        f.beforeWrite = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val pending = async { f.delete() }
        entered.await()
        pending.cancelAndJoin()
        assertTrue(pending.isCancelled)
        assertTrue(f.hidden.value.isEmpty())
    }

    @Test
    fun repeatedCasRacesAndLostCommitResponseRemainRetryable() = runTest {
        val f = VisibilityFixture()
        f.reject = 5
        assertIs<AppResult.Failed>(f.delete())
        assertEquals(2, f.writes)
        f.reject = 0
        f.writeResult = AppResult.Failed(f.owner.storage)
        assertEquals(AppResult.Failed(f.owner.storage), f.delete())
        assertEquals(f.messages.toSet(), f.hidden.value)
        f.writeResult = AppResult.Success(true)
        assertEquals(AppResult.Success(Unit), f.delete())
    }

    @Test
    fun callerMutationCannotChangeDeletionAfterFirstSuspension() = runTest {
        val f = VisibilityFixture()
        val messages = f.messages.toMutableList()
        f.owner.afterConversation = { messages.clear() }
        assertEquals(AppResult.Success(Unit), f.delete(messages))
        assertEquals(f.messages.toSet(), f.hidden.value)
    }

    @Test
    fun invalidBatchAndWrongExpectedOwnerNeverReadOrWriteContent() = runTest {
        val f = VisibilityFixture()
        assertIs<AppResult.Failed>(f.delete(emptyList()))
        assertIs<AppResult.Failed>(f.delete(f.messages + f.messages))
        assertIs<AppResult.Failed>(
            f.deleter(
                "selection",
                f.access.copy(sessionId = "old"),
                f.conversation,
                f.room,
                f.messages,
            )
        )
        assertIs<AppResult.Failed>(
            f.observer(
                    "selection",
                    f.access.copy(accountId = "wrong"),
                    f.conversation,
                    f.room,
                    f.messages,
                )
                .first()
        )
        assertEquals(0, f.reads)
        assertEquals(0, f.writes)
    }

    @Test
    fun accountReplacementDuringFinalReadCannotPublishHiddenSelection() = runTest {
        val f = VisibilityFixture()
        f.beforeRead = { f.owner.setup.session = null }
        assertIs<AppResult.Failed>(f.observe().first())
        assertEquals(0, f.active)
    }

    @Test
    fun admissionLossCancelsPendingObserverAndOnlyAdmissionReopensIt() = runTest {
        val f = VisibilityFixture()
        val entered = CompletableDeferred<Unit>()
        val denied = CompletableDeferred<Unit>()
        val resumed = CompletableDeferred<Unit>()
        f.beforeRead = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val pending = launch {
            f.observe().collect {
                if (it is AppResult.Failed) denied.complete(Unit) else resumed.complete(Unit)
            }
        }
        entered.await()
        f.owner.gate.value = CollaborationListAdmission("denied", f.owner.denied)
        denied.await()
        assertEquals(0, f.active)
        assertFalse(resumed.isCompleted)
        f.beforeRead = {}
        f.owner.gate.value = CollaborationListAdmission("admitted", null)
        resumed.await()
        pending.cancelAndJoin()
        assertEquals(0, f.active)
    }
}

private class VisibilityFixture {
    val owner = MessageIntentFixture()
    val access =
        CollaborationLocalAccess(
            owner.setup.session!!.id,
            owner.server.audience,
            owner.setup.session!!.user.id,
            owner.setup.workspace!!.company.id,
        )
    val conversation = owner.summary!!.conversation.id
    val room = "!local:example.test"
    val messages =
        listOf(
            CollaborationMessageReference("@peer:example.test", "\$one"),
            CollaborationMessageReference("@self:example.test", "\$two"),
        )
    val hidden = MutableStateFlow(emptySet<CollaborationMessageReference>())
    var reject = 0
    var writes = 0
    var reads = 0
    var active = 0
    var beforeWrite: suspend () -> Unit = {}
    var afterWrite: suspend () -> Unit = {}
    var beforeRead: suspend () -> Unit = {}
    var writeResult: AppResult<Boolean> = AppResult.Success(true)
    val visibility =
        object : CollaborationLocalVisibilityRepository {
            override suspend fun hide(
                access: CollaborationLocalAccess,
                conversationId: String,
                roomId: String,
                messages: List<CollaborationMessageReference>,
            ): AppResult<Boolean> {
                assertEquals(this@VisibilityFixture.access, access)
                assertEquals(conversation, conversationId)
                assertEquals(room, roomId)
                writes++
                beforeWrite()
                if (reject-- > 0) return AppResult.Success(false)
                hidden.value += messages
                afterWrite()
                return writeResult
            }

            override fun observeHidden(
                access: CollaborationLocalAccess,
                conversationId: String,
                roomId: String,
                messages: List<CollaborationMessageReference>,
            ) = flow {
                reads++
                active++
                try {
                    beforeRead()
                    emitAll(hidden.map { AppResult.Success(it.intersect(messages.toSet())) })
                } finally {
                    active--
                }
            }
        }
    val deleter =
        DeleteCollaborationMessagesForMe(
            visibility,
            owner.lists,
            owner.setup.sessions,
            owner.setup.workspaces,
            owner.server,
            owner.build,
            owner.clock,
        )
    val observer =
        ObserveCollaborationHiddenMessages(
            visibility,
            owner.lists,
            owner.setup.sessions,
            owner.setup.workspaces,
            owner.server,
            owner.build,
            owner.clock,
        )

    suspend fun delete(selected: List<CollaborationMessageReference> = messages) =
        deleter("selection", access, conversation, room, selected)

    fun observe() = observer("selection", access, conversation, room, messages)
}
