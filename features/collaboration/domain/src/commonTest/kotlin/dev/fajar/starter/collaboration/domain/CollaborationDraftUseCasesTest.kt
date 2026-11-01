package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest

class CollaborationDraftUseCasesTest {
    @Test
    fun expiredAuthorityKeepsExactOwnedTextReadableAndEditableWithoutNetwork() = runTest {
        val f = DraftIntentFixture()
        f.f.setup.now += 365.days
        val saved = assertIs<AppResult.Success<CollaborationDraft>>(f.edit()).value
        assertEquals("  Draft\n🙂  ", saved.text)
        assertEquals(AppResult.Success(saved), f.observe().first())
        assertEquals(0, f.f.setup.calls)
        assertEquals(0, f.f.insertions)
        assertTrue(f.f.scheduledKeys.isEmpty())
    }

    @Test
    fun lostEditReplyRetainsTimestampAndRejectsAnotherBodyForTheSameOperation() = runTest {
        val f = DraftIntentFixture()
        f.writeFailure = f.f.storage
        assertIs<AppResult.Failed>(f.edit())
        val original = f.slot.value!!
        f.f.setup.now += 2.days
        f.writeFailure = null
        assertEquals(AppResult.Success(original), f.edit())
        assertIs<AppResult.Failed>(f.edit(text = "Replacement"))
        assertEquals(original, f.slot.value)
        assertEquals(1, f.writes)
    }

    @Test
    fun staleEditorsAndFrozenTextCannotOverwriteTheSlot() = runTest {
        val f = DraftIntentFixture()
        val saved = assertIs<AppResult.Success<CollaborationDraft>>(f.edit()).value
        assertIs<AppResult.Failed>(f.edit(revision = draftId(8)))
        val frozen = assertIs<AppResult.Success<CollaborationDraft>>(f.prepare(saved)).value
        assertIs<AppResult.Failed>(f.edit(expected = frozen, revision = draftId(9)))
        assertEquals(frozen, f.slot.value)
        assertEquals(saved.revision, frozen.submissionId)
        assertNotEquals(saved.revision, frozen.revision)
    }

    @Test
    fun interruptedFreezeRecoversItsOriginalBodyAndIdWithoutConflatingLaterIdenticalText() =
        runTest {
            val f = DraftIntentFixture()
            val editing = assertIs<AppResult.Success<CollaborationDraft>>(f.edit()).value
            f.writeFailure = f.f.storage
            assertIs<AppResult.Failed>(f.prepare(editing))
            val frozen = f.slot.value!!
            f.writeFailure = null
            f.f.setup.now += 2.days
            assertEquals(AppResult.Success(frozen), f.prepare(editing))
            assertEquals(AppResult.Success(frozen), f.prepare(frozen))
            f.slot.value = frozen.copy(revision = draftId(9), submissionId = draftId(10))
            assertIs<AppResult.Failed>(f.prepare(editing))
            assertEquals(draftId(10), f.slot.value!!.submissionId)
        }

    @Test
    fun absentOrMismatchingCommandNeverClearsFrozenText() = runTest {
        val f = DraftIntentFixture()
        val frozen = f.freeze()
        assertIs<AppResult.Failed>(f.confirm(frozen))
        assertEquals(frozen, f.slot.value)
        assertIs<AppResult.Success<*>>(f.queue(frozen))
        val record = f.f.records.value.single()
        val bad =
            listOf(
                record.copy(intent = record.intent.copy(text = "Changed")),
                record.copy(intent = record.intent.copy(conversationId = draftId(9))),
                record.copy(
                    intent =
                        record.intent.copy(owner = record.intent.owner.copy(accountId = draftId(9)))
                ),
            )
        for (value in bad) {
            f.commandOverride = AppResult.Success(value)
            assertIs<AppResult.Failed>(f.confirm(frozen))
            assertEquals(frozen, f.slot.value)
        }
        f.commandOverride = AppResult.Failed(f.f.storage)
        assertIs<AppResult.Failed>(f.confirm(frozen))
        assertEquals(frozen, f.slot.value)
    }

    @Test
    fun lostQueueReplyRecoversOneCommandAndClearsOnlyAfterItsExactLookup() = runTest {
        val f = DraftIntentFixture()
        val frozen = f.freeze()
        f.f.insertResult = AppResult.Failed(f.f.storage)
        assertIs<AppResult.Failed>(f.queue(frozen))
        val retained = f.f.records.value.single()
        f.f.insertResult = AppResult.Success(CollaborationMessageInsertion.STORED)
        assertEquals(AppResult.Success(retained), f.queue(frozen))
        val cleared = assertIs<AppResult.Success<CollaborationDraft?>>(f.confirm(frozen)).value!!
        assertEquals("", cleared.text)
        assertNull(cleared.submissionId)
        assertNotEquals(frozen.revision, cleared.revision)
        assertEquals(1, f.f.insertions)
        assertEquals(retained.intent.id, frozen.submissionId)
    }

    @Test
    fun archivedCompletionAndLostClearReplyPreserveANewerDraft() = runTest {
        val f = DraftIntentFixture()
        val frozen = f.freeze()
        val command = assertIs<AppResult.Success<CollaborationMessageRecord>>(f.queue(frozen)).value
        // The repository contract authenticates archived completions through the same exact lookup.
        f.f.records.value = emptyList()
        f.commandOverride = AppResult.Success(command)
        f.writeFailure = f.f.storage
        assertIs<AppResult.Failed>(f.confirm(frozen))
        val cleared = f.slot.value!!
        assertEquals("", cleared.text)
        f.writeFailure = null
        val next =
            assertIs<AppResult.Success<CollaborationDraft>>(
                    f.edit(cleared, draftId(10), "Next draft")
                )
                .value
        assertEquals(AppResult.Success(next), f.confirm(frozen))
        assertEquals(next, f.slot.value)
    }

    @Test
    fun scopeChangesBeforeAndAfterPersistenceCannotAcknowledgeForAnotherAccount() = runTest {
        val before = DraftIntentFixture()
        before.afterRead = { before.f.setup.session = null }
        assertIs<AppResult.Failed>(before.edit())
        assertEquals(0, before.writes)
        val after = DraftIntentFixture()
        after.afterWrite = { after.f.setup.session = null }
        assertIs<AppResult.Failed>(after.edit())
        assertNotNull(after.slot.value)
        assertIs<AppResult.Failed>(after.observe().first())
        assertEquals(0, after.observers)
    }

    @Test
    fun confirmedDenialWinsOverFailedSessionAndFailedAdmissionCannotWrite() = runTest {
        val f = DraftIntentFixture()
        f.afterRead = {
            f.f.gate.value = CollaborationListAdmission("denied", f.f.denied)
            f.f.setup.sessionFailure = f.f.storage
        }
        assertEquals(f.f.denied, assertIs<AppResult.Failed>(f.edit()).failure)
        assertEquals(0, f.writes)
        val failed = DraftIntentFixture()
        failed.f.gateFailure = failed.f.storage
        assertIs<AppResult.Failed>(failed.edit())
        assertEquals(0, failed.reads)
    }

    @Test
    fun cancelledFreezeRetainsTheOperationAndRetryDoesNotPrepareAgain() = runTest {
        val f = DraftIntentFixture()
        val editing = assertIs<AppResult.Success<CollaborationDraft>>(f.edit()).value
        val committed = CompletableDeferred<Unit>()
        f.afterWrite = {
            committed.complete(Unit)
            awaitCancellation()
        }
        val task = launch { f.prepare(editing) }
        committed.await()
        task.cancelAndJoin()
        val frozen = f.slot.value!!
        f.afterWrite = {}
        assertEquals(AppResult.Success(frozen), f.prepare(editing))
        assertEquals(2, f.writes)
        assertEquals(0, f.f.insertions)
    }

    @Test
    fun admissionClosesPendingObservationAndReopensOnlyAfterAdmittedPublication() = runTest {
        val f = DraftIntentFixture()
        val saved = assertIs<AppResult.Success<CollaborationDraft>>(f.edit()).value
        val ready = CompletableDeferred<Unit>()
        val denied = CompletableDeferred<Unit>()
        val reopened = CompletableDeferred<Unit>()
        var successes = 0
        val task = launch {
            f.observe().collect {
                if (it is AppResult.Failed) {
                    assertEquals(f.f.denied, it.failure)
                    denied.complete(Unit)
                } else if (++successes == 1) ready.complete(Unit) else reopened.complete(Unit)
            }
        }
        ready.await()
        f.f.gate.value = CollaborationListAdmission("denied", f.f.denied)
        denied.await()
        assertEquals(0, f.observers)
        assertEquals(saved, f.slot.value)
        f.f.gate.value = CollaborationListAdmission("admitted", null)
        reopened.await()
        task.cancelAndJoin()
        assertEquals(0, f.observers)
    }

    @Test
    fun unownedConversationAndInvalidInputCannotReadOrMutateDrafts() = runTest {
        val f = DraftIntentFixture()
        for (text in listOf("bad\u0000text", "x".repeat(16_385), "\ud800")) assertIs<
            AppResult.Failed
        >(
            f.edit(text = text)
        )
        assertIs<AppResult.Failed>(f.edit(revision = "invalid"))
        assertEquals(0, f.reads)
        f.f.summary =
            f.f.summary!!.copy(
                conversation = f.f.summary!!.conversation.copy(companyId = draftId(99))
            )
        assertIs<AppResult.Failed>(f.edit())
        assertEquals(0, f.reads)
        assertEquals(0, f.writes)
    }
}

private class DraftIntentFixture {
    val f = MessageIntentFixture()
    private val conversation = f.summary!!.conversation.id
    val slot = MutableStateFlow<CollaborationDraft?>(null)
    var reads = 0
    var writes = 0
    var observers = 0
    var writeFailure: Failure? = null
    var afterRead: suspend () -> Unit = {}
    var afterWrite: suspend () -> Unit = {}
    var commandOverride: AppResult<CollaborationMessageRecord?>? = null
    private val drafts =
        object : CollaborationDraftRepository {
            override fun observe(access: CollaborationLocalAccess, conversationId: String) = flow {
                observers++
                try {
                    emitAll(slot.map { AppResult.Success(it) })
                } finally {
                    observers--
                }
            }

            override suspend fun get(
                access: CollaborationLocalAccess,
                conversationId: String,
            ): AppResult<CollaborationDraft?> {
                reads++
                val value = slot.value
                afterRead()
                return AppResult.Success(value)
            }

            override suspend fun replace(
                access: CollaborationLocalAccess,
                expected: CollaborationDraft?,
                replacement: CollaborationDraft,
            ): AppResult<Boolean> {
                if (slot.value == replacement) return AppResult.Success(true)
                if (slot.value != expected) return AppResult.Success(false)
                writes++
                slot.value = replacement
                afterWrite()
                return writeFailure?.let { AppResult.Failed(it) } ?: AppResult.Success(true)
            }
        }
    private val commands =
        object : CollaborationMessageRepository by f.messages {
            override suspend fun get(access: CollaborationLocalAccess, id: String) =
                commandOverride ?: f.messages.get(access, id)
        }
    private val editor =
        EditCollaborationDraft(
            drafts,
            f.lists,
            f.setup.sessions,
            f.setup.workspaces,
            f.server,
            f.build,
            f.clock,
        )
    private val preparer =
        PrepareCollaborationDraftSend(
            drafts,
            f.lists,
            f.setup.sessions,
            f.setup.workspaces,
            f.server,
            f.build,
            f.clock,
        )
    private val confirmer =
        ConfirmCollaborationDraftSend(
            drafts,
            f.lists,
            f.setup.sessions,
            f.setup.workspaces,
            f.server,
            f.build,
            f.clock,
            commands,
        )
    private val observer =
        ObserveCollaborationDraft(
            drafts,
            f.lists,
            f.setup.sessions,
            f.setup.workspaces,
            f.server,
            f.build,
            f.clock,
        )
    private val queue =
        QueueCollaborationText(
            f.messages,
            f.lists,
            f.setup.sessions,
            f.setup.workspaces,
            f.server,
            f.build,
            f.clock,
            f.schedules,
        )

    suspend fun edit(
        expected: CollaborationDraft? = null,
        revision: String = draftId(7),
        text: String = "  Draft\n🙂  ",
    ) = editor("selection", conversation, expected, revision, text)

    suspend fun prepare(expected: CollaborationDraft) = preparer("selection", expected)

    suspend fun confirm(expected: CollaborationDraft) = confirmer("selection", expected)

    suspend fun queue(expected: CollaborationDraft) =
        queue("selection", conversation, expected.submissionId!!, expected.text)

    fun observe() = observer("selection", conversation)

    suspend fun freeze(): CollaborationDraft {
        val saved = assertIs<AppResult.Success<CollaborationDraft>>(edit()).value
        return assertIs<AppResult.Success<CollaborationDraft>>(prepare(saved)).value
    }
}

private fun draftId(value: Int) = "00000000-0000-0000-0000-" + value.toString().padStart(12, '0')
