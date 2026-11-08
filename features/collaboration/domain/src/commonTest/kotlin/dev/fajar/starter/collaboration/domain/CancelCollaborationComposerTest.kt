package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.collaborationComposerIntent
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.CancelCollaborationComposer
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class CancelCollaborationComposerTest {
    @Test
    fun offlineHeldOriginalCanBePreventedWithoutAnAvailableConversationOrTransport() = runTest {
        val f = ComposerCancellationFixture()
        f.base.setup.now += 365.days
        f.base.summary = null
        assertEquals(AppResult.Success(Unit), f.cancel())
        assertEquals(CollaborationMessageState.Cancelled, f.base.records.value.single().state)
        assertEquals(f.intent, f.base.records.value.single().intent)
        assertTrue(f.outbox.entries.value.isEmpty())
        assertEquals(1, f.preventions)
        assertEquals(0, f.base.setup.calls)
        assertEquals(0, f.base.insertions)
    }

    @Test
    fun anAlreadyMaterializedQueuedOriginalUsesExactCancellationAndRetiresItsOutbox() = runTest {
        val f = ComposerCancellationFixture()
        f.base.records.value = listOf(f.record(CollaborationMessageState.Queued))
        assertEquals(AppResult.Success(Unit), f.cancel())
        assertEquals(0, f.preventions)
        assertEquals(1, f.cancellations)
        assertEquals(1, f.outbox.removals)
        assertEquals(AppResult.Success(Unit), f.cancel())
        assertEquals(1, f.cancellations)
        assertEquals(1, f.outbox.removals)
    }

    @Test
    fun anOriginalWithTransportEvidenceCannotBeCancelledOrRemoved() = runTest {
        for (state in
            listOf(
                CollaborationMessageState.Staging(ComposerCancellationFixture.device, emptySet()),
                CollaborationMessageState.Staged(ComposerCancellationFixture.device, "transaction"),
                CollaborationMessageState.Sent(
                    ComposerCancellationFixture.device,
                    "transaction",
                    "\$event",
                ),
            )) {
            val f = ComposerCancellationFixture()
            f.base.records.value = listOf(f.record(state))
            assertEquals(
                "client.collaboration_cancellation_unavailable",
                assertIs<AppResult.Failed>(f.cancel()).failure.code,
            )
            assertEquals(0, f.cancellations)
            assertEquals(0, f.outbox.removals)
        }
    }

    @Test
    fun changedTimestampOrOriginalCannotBorrowCancellationEvidence() = runTest {
        for (time in listOf(false, true)) {
            val f = ComposerCancellationFixture()
            f.base.records.value =
                listOf(
                    f.record(CollaborationMessageState.Cancelled).let {
                        it.copy(
                            intent =
                                if (time)
                                    it.intent.copy(createdAt = it.intent.createdAt + 1.seconds)
                                else it.intent.copy(text = "Other original")
                        )
                    }
                )
            assertIs<AppResult.Failed>(f.cancel())
            assertEquals(0, f.outbox.removals)
        }
    }

    @Test
    fun aTruePreventionResultStillRequiresTheFullAuthenticatedOriginal() = runTest {
        val f = ComposerCancellationFixture()
        f.afterPrevention = {
            f.base.records.value =
                listOf(
                    f.record(CollaborationMessageState.Cancelled).let {
                        it.copy(
                            intent = it.intent.copy(createdAt = it.intent.createdAt + 1.seconds)
                        )
                    }
                )
        }
        assertIs<AppResult.Failed>(f.cancel())
        assertEquals(0, f.outbox.removals)
    }

    @Test
    fun lostCancellationRepliesReconcileOnlyTheExactArchivedProof() = runTest {
        for (queued in listOf(false, true)) {
            val f = ComposerCancellationFixture()
            if (queued) f.base.records.value = listOf(f.record(CollaborationMessageState.Queued))
            f.mutationFailure = f.base.storage
            assertEquals(AppResult.Success(Unit), f.cancel())
            assertEquals(1, f.outbox.removals)
        }
    }

    @Test
    fun stagingWinningTheRacePreservesTheOriginalAndReportsCancellationUnavailable() = runTest {
        val f = ComposerCancellationFixture()
        f.afterPrevention = {
            f.base.records.value =
                listOf(
                    f.record(
                        CollaborationMessageState.Staged(
                            ComposerCancellationFixture.device,
                            "transaction",
                        )
                    )
                )
        }
        assertIs<AppResult.Failed>(f.cancel())
        assertEquals(0, f.outbox.removals)
        assertEquals(0, f.cancellations)
    }

    @Test
    fun deniedOrReplacementOwnerCannotCancelTheOldOriginal() = runTest {
        for (replacement in listOf(false, true)) {
            val f = ComposerCancellationFixture()
            if (replacement)
                f.base.setup.session =
                    f.base.setup.session!!.copy(
                        user = f.base.setup.session!!.user.copy(id = deliveryId(995))
                    )
            else f.base.gate.value = CollaborationListAdmission("denied", f.base.denied)
            assertIs<AppResult.Failed>(f.cancel())
            assertEquals(0, f.preventions)
            assertEquals(0, f.outbox.removals)
        }
    }

    @Test
    fun cancellationOrAccountReplacementAfterPreventionCannotRetireUnderAnotherOwner() = runTest {
        for (cancel in listOf(false, true)) {
            val f = ComposerCancellationFixture()
            f.afterPrevention = {
                if (cancel) currentCoroutineContext().cancel()
                else
                    f.base.setup.session =
                        f.base.setup.session!!.copy(
                            user = f.base.setup.session!!.user.copy(id = deliveryId(995))
                        )
            }
            if (cancel) {
                val job = launch { f.cancel() }
                job.join()
                assertTrue(job.isCancelled)
            } else assertIs<AppResult.Failed>(f.cancel())
            assertEquals(CollaborationMessageState.Cancelled, f.base.records.value.single().state)
            assertEquals(0, f.outbox.removals)
        }
    }

    @Test
    fun missingOriginalOrChangedHandoffCannotBeRemovedAndCleanupFailureIsRetryable() = runTest {
        val f = ComposerCancellationFixture()
        f.outbox.entries.value = emptyList()
        assertIs<AppResult.Failed>(f.cancel())
        assertEquals(0, f.preventions)
        f.outbox.entries.value =
            listOf(
                CollaborationComposerOutboxEntry(
                    f.original.copy(nextRevision = deliveryId(996)),
                    true,
                )
            )
        assertIs<AppResult.Failed>(f.cancel())
        assertEquals(0, f.preventions)
        f.outbox.entries.value = listOf(CollaborationComposerOutboxEntry(f.original, true))
        f.outbox.removeFailure = f.base.storage
        assertEquals(AppResult.Failed(f.base.storage), f.cancel())
        assertEquals(0, f.outbox.removals)
        f.outbox.removeFailure = null
        assertEquals(AppResult.Success(Unit), f.cancel())
        assertEquals(1, f.preventions)
    }
}

private class ComposerCancellationFixture {
    val base = MessageIntentFixture()
    val access =
        CollaborationLocalAccess(
            "local",
            base.server.audience,
            base.summary!!.conversation.firstAccountId,
            base.summary!!.conversation.companyId,
        )
    val original =
        CollaborationComposerSubmission(
            CollaborationDraft(
                base.summary!!.conversation.id,
                deliveryId(900),
                "Original",
                base.setup.now,
            ),
            deliveryId(901),
            base.setup.now,
        )
    val intent = collaborationComposerIntent(access, original)
    val outbox =
        ComposerOutboxTestStore().apply {
            entries.value = listOf(CollaborationComposerOutboxEntry(original, true))
        }
    var preventions = 0
    var cancellations = 0
    var afterPrevention: suspend () -> Unit = {}
    var mutationFailure: Failure? = null
    private val prevention =
        object : CollaborationMessagePreventionRepository {
            override suspend fun prevent(
                access: CollaborationLocalAccess,
                intent: CollaborationMessageIntent,
            ): AppResult<Boolean> {
                preventions++
                base.records.value = listOf(record(CollaborationMessageState.Cancelled))
                afterPrevention()
                return mutationFailure?.let { AppResult.Failed(it) } ?: AppResult.Success(true)
            }
        }
    private val cancellation =
        object : CollaborationMessageCancellationRepository {
            override suspend fun cancel(
                access: CollaborationLocalAccess,
                expected: CollaborationMessageRecord,
            ): AppResult<Boolean> {
                cancellations++
                base.records.value =
                    listOf(expected.copy(state = CollaborationMessageState.Cancelled))
                return mutationFailure?.let { AppResult.Failed(it) } ?: AppResult.Success(true)
            }
        }
    private val operation =
        CancelCollaborationComposer(
            outbox,
            base.messages,
            prevention,
            cancellation,
            base.lists,
            base.setup.sessions,
            base.setup.workspaces,
            base.server,
            base.build,
            base.clock,
        )

    fun record(state: CollaborationMessageState) = CollaborationMessageRecord(intent, 0, state)

    suspend fun cancel() = operation("selection", access, original)

    companion object {
        val device =
            CollaborationMessageDevice(
                deliveryId(11),
                deliveryId(12),
                deliveryId(13),
                "!room:example.test",
            )
    }
}
