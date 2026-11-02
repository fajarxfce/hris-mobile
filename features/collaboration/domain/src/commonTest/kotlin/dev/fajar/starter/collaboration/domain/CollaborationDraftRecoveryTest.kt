package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class CollaborationDraftRecoveryTest {
    @Test
    fun capturedFrozenDraftKeepsItsOriginalIdAcrossQueueCommitFailureAndConfirmation() = runTest {
        val f = RecoveryDraftFixture()
        val frozen = f.base.freeze()
        val scope = f.f.captureRecoveryScope()
        f.f.insertResult = AppResult.Failed(f.f.storage)
        assertIs<AppResult.Failed>(f.base.queue(frozen, scope))
        assertEquals(frozen, f.base.slot.value)
        assertEquals(frozen.submissionId, f.f.records.value.single().intent.id)
        f.f.insertResult = AppResult.Success(CollaborationMessageInsertion.STORED)
        assertIs<AppResult.Success<*>>(f.base.queue(frozen, scope))
        assertEquals(1, f.f.insertions)
        assertIs<AppResult.Success<*>>(f.base.confirm(frozen, scope))
        assertEquals("", f.base.slot.value?.text)
        assertNull(f.base.slot.value?.submissionId)
        assertEquals(
            listOf(QueueCollaborationText.DELIVERY_KEY, QueueCollaborationText.DELIVERY_KEY),
            f.f.scheduledKeys,
        )
        assertEquals(0, f.f.setup.calls)
    }

    @Test
    fun capturedDraftDiscoveryPreservesTheEightItemBoundWrapAndCursorCas() = runTest {
        val f = RecoveryDraftFixture()
        val frozen = f.base.freeze()
        f.source = (1..9).map { frozen.copy(conversationId = recoveryId(it)) }
        val scope = f.f.captureRecoveryScope()
        val first =
            assertIs<AppResult.Success<CollaborationDraftRecoveryWork?>>(f.read(scope)).value!!
        assertEquals(scope, first.scope)
        assertEquals(f.source.take(8), first.drafts)
        assertTrue(first.hasMore)
        assertIs<AppResult.Success<*>>(f.advance(first, first.drafts.last().conversationId))
        assertIs<AppResult.Failed>(f.advance(first, first.drafts.first().conversationId))
        val next =
            assertIs<AppResult.Success<CollaborationDraftRecoveryWork?>>(f.read(scope)).value!!
        assertEquals(listOf(f.source.last()), next.drafts)
        assertIs<AppResult.Success<*>>(f.advance(next, next.drafts.single().conversationId))
        val wrapped =
            assertIs<AppResult.Success<CollaborationDraftRecoveryWork?>>(f.read(scope)).value!!
        assertEquals(f.source.take(8), wrapped.drafts)
        assertEquals(listOf(null, recoveryId(8), recoveryId(9), null), f.anchors)
        assertEquals(0, f.f.insertions)
    }

    @Test
    fun aCapturedUnavailableDraftIsHeldWithoutLosingTextOrClearingAnotherEditorsSlot() = runTest {
        val f = RecoveryDraftFixture()
        val frozen = f.base.freeze()
        val scope = f.f.captureRecoveryScope()
        val summary = f.f.summary
        f.f.summary = null
        val held =
            assertIs<AppResult.Success<CollaborationDraft?>>(f.suspendSend(frozen, scope)).value!!
        assertTrue(held.reviewRequired)
        assertEquals(frozen.text, held.text)
        assertEquals(frozen.submissionId, held.submissionId)
        f.f.summary = summary
        assertIs<AppResult.Success<*>>(f.base.queue(held, scope))
        val later =
            held.copy(revision = recoveryId(77), text = "Later editor text", submissionId = null)
        f.base.slot.value = later
        assertEquals(AppResult.Success(later), f.base.confirm(frozen, scope))
        assertEquals(later, f.base.slot.value)
        assertEquals(frozen.submissionId, f.f.records.value.single().intent.id)
    }

    @Test
    fun capturedDenialAndLateOwnerLossStopDraftRecoveryWithoutErasingItsOriginal() = runTest {
        val f = RecoveryDraftFixture()
        val frozen = f.base.freeze()
        f.source = listOf(frozen)
        val scope = f.f.captureRecoveryScope()
        f.afterPage = {
            f.f.gate.value = CollaborationListAdmission("denied", f.f.denied)
            f.f.setup.sessionFailure = f.f.storage
        }
        assertEquals(AppResult.Failed(f.f.denied), f.read(scope))
        f.f.setup.sessionFailure = null
        assertEquals(AppResult.Failed(f.f.denied), f.base.queue(frozen, scope))
        assertEquals(AppResult.Failed(f.f.denied), f.base.confirm(frozen, scope))
        assertEquals(AppResult.Failed(f.f.denied), f.suspendSend(frozen, scope))
        assertEquals(0, f.f.messageReads)
        assertEquals(frozen, f.base.slot.value)
        f.f.gate.value = CollaborationListAdmission("readmitted", null)
        f.f.afterInsert = { f.f.setup.session = null }
        assertIs<AppResult.Failed>(f.base.queue(frozen, scope))
        assertEquals(frozen.submissionId, f.f.records.value.single().intent.id)
        assertEquals(frozen, f.base.slot.value)
        assertNull(f.cursor)
    }

    @Test
    fun cancellationDuringCapturedDraftConfirmationRetainsTheCommittedAcknowledgement() = runTest {
        val f = RecoveryDraftFixture()
        val frozen = f.base.freeze()
        val scope = f.f.captureRecoveryScope()
        assertIs<AppResult.Success<*>>(f.base.queue(frozen, scope))
        val entered = CompletableDeferred<Unit>()
        f.base.afterWrite = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val task = launch { f.base.confirm(frozen, scope) }
        entered.await()
        task.cancelAndJoin()
        assertEquals("", f.base.slot.value?.text)
        assertNull(f.base.slot.value?.submissionId)
        assertEquals(frozen.submissionId, f.f.records.value.single().intent.id)
        f.base.afterWrite = {}
        assertEquals(AppResult.Success(f.base.slot.value), f.base.confirm(frozen, scope))
        assertEquals(1, f.f.insertions)
    }

    @Test
    fun expiredCredentialsStillDiscoverAnOwnedFrozenBatchWithoutNetwork() = runTest {
        val f = RecoveryDraftFixture()
        val frozen = f.base.freeze()
        f.source = (1..9).map { frozen.copy(conversationId = recoveryId(it)) }
        f.f.setup.now += 365.days
        val work = assertIs<AppResult.Success<CollaborationDraftRecoveryWork?>>(f.read()).value!!
        assertEquals(f.source.take(8), work.drafts)
        assertTrue(work.hasMore)
        assertEquals(0, f.f.setup.calls)
        assertEquals(0, f.f.insertions)
    }

    @Test
    fun cursorWrapsAtMostOnceAndOnlyTheSelectedOwnedAttemptCanAdvance() = runTest {
        val f = RecoveryDraftFixture()
        val draft = f.base.freeze()
        f.source = listOf(draft)
        f.cursor = CollaborationDraftRecoveryCursor(recoveryId(91), recoveryId(99))
        val work = assertIs<AppResult.Success<CollaborationDraftRecoveryWork?>>(f.read()).value!!
        assertEquals(listOf(recoveryId(99), null), f.anchors)
        assertIs<AppResult.Failed>(f.advance(work, recoveryId(98)))
        val advanced =
            assertIs<AppResult.Success<CollaborationDraftRecoveryCursor>>(
                    f.advance(work, draft.conversationId)
                )
                .value
        assertEquals(draft.conversationId, advanced.afterConversationId)
        assertIs<AppResult.Failed>(f.advance(work, draft.conversationId))
        assertEquals(advanced, f.cursor)
    }

    @Test
    fun deniedDiscoveryWinsOverFailedSessionAndCannotReturnRetainedText() = runTest {
        val f = RecoveryDraftFixture()
        f.source = listOf(f.base.freeze())
        f.afterPage = {
            f.f.gate.value = CollaborationListAdmission("denied", f.f.denied)
            f.f.setup.sessionFailure = f.f.storage
        }
        assertEquals(f.f.denied, assertIs<AppResult.Failed>(f.read()).failure)
        assertNull(f.cursor)
    }

    @Test
    fun ownershipChangeDuringDiscoveryOrAdvanceCannotAcknowledgeOldWork() = runTest {
        val first = RecoveryDraftFixture()
        first.source = listOf(first.base.freeze())
        first.afterPage = { first.f.setup.session = null }
        assertIs<AppResult.Failed>(first.read())
        val second = RecoveryDraftFixture()
        second.source = listOf(second.base.freeze())
        val work =
            assertIs<AppResult.Success<CollaborationDraftRecoveryWork?>>(second.read()).value!!
        second.afterAdvance = { second.f.setup.session = null }
        assertIs<AppResult.Failed>(second.advance(work, work.drafts.single().conversationId))
        assertNotNull(second.cursor)
    }

    @Test
    fun malformedOrHeldCandidatesAreRejectedBeforeRecoveryCanUseThem() = runTest {
        val f = RecoveryDraftFixture()
        val draft = f.base.freeze()
        for (bad in
            listOf(
                CollaborationPreparedDraftPage(listOf(draft.copy(reviewRequired = true)), null),
                CollaborationPreparedDraftPage(listOf(draft, draft), null),
                CollaborationPreparedDraftPage(listOf(draft), draft.conversationId),
                CollaborationPreparedDraftPage(listOf(draft.copy(submissionId = null)), null),
            )) {
            f.pageOverride = bad
            assertIs<AppResult.Failed>(f.read())
        }
        assertEquals(0, f.f.insertions)
    }

    @Test
    fun unavailableConversationIsRetainedForReviewAndKnownQueueCanStillClearTheSameIntent() =
        runTest {
            val f = RecoveryDraftFixture()
            val frozen = f.base.freeze()
            val summary = f.f.summary
            f.f.summary = null
            val held =
                assertIs<AppResult.Success<CollaborationDraft?>>(f.suspendSend(frozen)).value!!
            assertTrue(held.reviewRequired)
            assertEquals(frozen.text, held.text)
            assertEquals(frozen.submissionId, held.submissionId)
            assertNotEquals(frozen.revision, held.revision)
            assertTrue(f.f.records.value.isEmpty())
            f.f.summary = summary
            assertIs<AppResult.Success<*>>(f.base.queue(held))
            // A worker hold racing an existing caller is still the same immutable ID and body.
            val cleared =
                assertIs<AppResult.Success<CollaborationDraft?>>(f.base.confirm(frozen)).value!!
            assertEquals("", cleared.text)
            assertFalse(cleared.reviewRequired)
            assertNull(cleared.submissionId)
        }

    @Test
    fun transientFailureOrKnownCommandDoesNotCreateAPermanentHold() = runTest {
        val f = RecoveryDraftFixture()
        val frozen = f.base.freeze()
        assertEquals(AppResult.Success(null), f.suspendSend(frozen))
        assertEquals(frozen, f.base.slot.value)
        assertIs<AppResult.Success<*>>(f.base.queue(frozen))
        f.f.summary = null
        assertEquals(AppResult.Success(null), f.suspendSend(frozen))
        assertEquals(frozen, f.base.slot.value)
    }

    @Test
    fun capturedOwnerRejectsACurrentCoParticipantBeforeAnyCommandReadOrInsertion() = runTest {
        val f = RecoveryDraftFixture()
        val owned = f.access()
        val original = f.f.setup.session!!
        val peer = f.f.summary!!.conversation.secondAccountId
        f.f.setup.session = original.copy(user = original.user.copy(id = peer))
        f.f.setup.workspace =
            f.f.setup.workspace!!.let { it.copy(admission = it.admission.copy(accountId = peer)) }
        val queue =
            QueueCollaborationText(
                f.f.messages,
                f.f.lists,
                f.f.setup.sessions,
                f.f.setup.workspaces,
                f.f.server,
                f.f.build,
                f.f.clock,
                f.f.schedules,
            )
        assertIs<AppResult.Failed>(
            queue(
                "selection",
                f.f.summary!!.conversation.id,
                recoveryId(88),
                "Old owner's text",
                owned,
            )
        )
        assertEquals(0, f.f.messageReads)
        assertEquals(0, f.f.insertions)
        val observer =
            ObserveCollaborationDraft(
                f.base.drafts,
                f.f.lists,
                f.f.setup.sessions,
                f.f.setup.workspaces,
                f.f.server,
                f.f.build,
                f.f.clock,
            )
        assertIs<AppResult.Failed>(
            observer("selection", f.f.summary!!.conversation.id, owned).first()
        )
        assertEquals(0, f.base.observers)
    }

    @Test
    fun freezeSchedulesOnlyTheTaskKeyAndCancellationAfterSchedulingRetainsTheOriginal() = runTest {
        val f = RecoveryDraftFixture()
        val editing = assertIs<AppResult.Success<CollaborationDraft>>(f.base.edit()).value
        val entered = CompletableDeferred<Unit>()
        f.f.schedule = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val task = launch { f.base.prepare(editing) }
        entered.await()
        task.cancelAndJoin()
        val frozen = f.base.slot.value!!
        assertEquals(editing.revision, frozen.submissionId)
        assertEquals(listOf(QueueCollaborationText.DELIVERY_KEY), f.f.scheduledKeys)
        f.f.schedule = { AppResult.Success(Unit) }
        assertEquals(AppResult.Success(frozen), f.base.prepare(editing))
        assertEquals(0, f.f.insertions)
    }
}

private class RecoveryDraftFixture {
    val base = DraftIntentFixture()
    val f = base.f
    var source = emptyList<CollaborationDraft>()
    var cursor: CollaborationDraftRecoveryCursor? = null
    val anchors = mutableListOf<String?>()
    var afterPage: suspend () -> Unit = {}
    var afterAdvance: suspend () -> Unit = {}
    var pageOverride: CollaborationPreparedDraftPage? = null
    private val drafts =
        object : CollaborationDraftRepository by base.drafts {
            override suspend fun prepared(
                access: CollaborationLocalAccess,
                afterConversationId: String?,
                limit: Int,
            ): AppResult<CollaborationPreparedDraftPage> {
                anchors += afterConversationId
                val rows =
                    source
                        .filter {
                            !it.reviewRequired &&
                                (afterConversationId == null ||
                                    it.conversationId > afterConversationId)
                        }
                        .sortedBy { it.conversationId }
                val page =
                    pageOverride
                        ?: CollaborationPreparedDraftPage(
                            rows.take(limit),
                            if (rows.size > limit) rows[limit - 1].conversationId else null,
                        )
                afterPage()
                return AppResult.Success(page)
            }
        }
    private val cursors =
        object : CollaborationDraftRecoveryCursorRepository {
            override suspend fun current(access: CollaborationLocalAccess) =
                AppResult.Success(cursor)

            override suspend fun advance(
                access: CollaborationLocalAccess,
                expected: CollaborationDraftRecoveryCursor?,
                conversationId: String,
            ): AppResult<CollaborationDraftRecoveryCursor?> {
                if (cursor != expected) return AppResult.Success(null)
                val next = CollaborationDraftRecoveryCursor(recoveryId(92), conversationId)
                cursor = next
                afterAdvance()
                return AppResult.Success(next)
            }
        }
    private val reader =
        ReadCollaborationDraftRecovery(
            drafts,
            cursors,
            f.lists,
            f.setup.sessions,
            f.setup.workspaces,
            f.server,
            f.build,
            f.clock,
        )
    val advance =
        AdvanceCollaborationDraftRecovery(
            cursors,
            f.lists,
            f.setup.sessions,
            f.setup.workspaces,
            f.server,
            f.build,
            f.clock,
        )
    private val suspender =
        SuspendCollaborationDraftSend(
            base.drafts,
            f.lists,
            f.setup.sessions,
            f.setup.workspaces,
            f.server,
            f.build,
            f.clock,
            f.messages,
        )

    suspend fun read(scope: CollaborationLocalScope? = null) =
        if (scope == null) reader() else reader(scope)

    suspend fun suspendSend(
        expected: CollaborationDraft,
        scope: CollaborationLocalScope = CollaborationLocalScope.Selected("selection"),
    ) = suspender(scope, expected)

    fun access() =
        CollaborationLocalAccess(
            f.setup.session!!.id,
            f.server.audience,
            f.setup.session!!.user.id,
            f.setup.workspace!!.company.id,
        )
}

private fun recoveryId(value: Int) = "00000000-0000-0000-0000-" + value.toString().padStart(12, '0')
