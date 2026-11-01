package dev.fajar.starter

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.fixtures.CollaborationWorkerFixture
import dev.fajar.starter.sync.domain.SyncResult
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class CollaborationCancellationWorkerTest {
    @Test
    fun cancelledQueuedMessageCannotWakeOrSendAfterContainerRestart() = runBlocking {
        withFixture { f ->
            val original = f.queue()
            assertEquals(
                AppResult.Success(Unit),
                f.koin.get<CancelCollaborationText>()(f.workspaceId, f.access(), original),
            )
            f.reopen()
            f.scheduled.clear()
            val replay =
                f.koin.get<QueueCollaborationText>()(
                    f.workspaceId,
                    f.first,
                    original.intent.id,
                    original.intent.text,
                    f.access(),
                )
            assertEquals(
                AppResult.Success(original.copy(state = CollaborationMessageState.Cancelled)),
                replay,
            )
            assertTrue(f.scheduled.isEmpty())
            assertEquals(SyncResult.Complete, f.worker())
            assertTrue(f.sdk.enqueued.isEmpty())
            assertEquals(0, f.sdk.deliveries)
            assertTrue(f.pending().isEmpty())
        }
    }

    @Test
    fun frozenDraftRecoveryRecognizesCancellationWithoutResurrectingItsOriginal() = runBlocking {
        withFixture { f ->
            val frozen = f.freeze()
            val original =
                assertIs<AppResult.Success<CollaborationMessageRecord>>(
                        f.koin.get<QueueCollaborationText>()(
                            f.workspaceId,
                            f.first,
                            frozen.submissionId!!,
                            frozen.text,
                            f.access(),
                        )
                    )
                    .value
            assertEquals(
                AppResult.Success(Unit),
                f.koin.get<CancelCollaborationText>()(f.workspaceId, f.access(), original),
            )
            assertEquals(frozen, f.draft()) // Simulate lost queue acknowledgement before clear.
            f.reopen()
            assertEquals(SyncResult.Complete, f.worker())
            assertEquals("", f.draft()?.text)
            assertNull(f.draft()?.submissionId)
            assertTrue(f.sdk.enqueued.isEmpty())
            assertEquals(0, f.sdk.deliveries)
            assertTrue(f.pending().isEmpty())
            assertEquals(
                AppResult.Success(null),
                f.koin
                    .get<CollaborationMessageCompletionRepository>()
                    .readCompletion(f.access(), original.intent.id),
            )
        }
    }

    @Test
    fun cancellationConfirmationPreservesANewerDraftAndIndependentQueuedWork() = runBlocking {
        withFixture { f ->
            val frozen = f.freeze()
            val original =
                assertIs<AppResult.Success<CollaborationMessageRecord>>(
                        f.koin.get<QueueCollaborationText>()(
                            f.workspaceId,
                            f.first,
                            frozen.submissionId!!,
                            frozen.text,
                            f.access(),
                        )
                    )
                    .value
            assertEquals(
                AppResult.Success(Unit),
                f.koin.get<CancelCollaborationText>()(f.workspaceId, f.access(), original),
            )
            val confirmed = f.koin.get<ConfirmCollaborationDraftSend>()
            val cleared =
                assertIs<AppResult.Success<CollaborationDraft?>>(
                        confirmed(f.workspaceId, frozen, f.access())
                    )
                    .value!!
            val next =
                assertIs<AppResult.Success<CollaborationDraft>>(
                        f.koin.get<EditCollaborationDraft>()(
                            f.workspaceId,
                            f.first,
                            cleared,
                            UUID.randomUUID().toString(),
                            "New text",
                            f.access(),
                        )
                    )
                    .value
            assertEquals(AppResult.Success(next), confirmed(f.workspaceId, frozen, f.access()))
            val independent = f.queue(text = "Another explicit message")
            f.reopen()
            assertEquals(SyncResult.Complete, f.worker())
            assertEquals(listOf(independent.intent.id), f.sdk.enqueued)
            assertEquals(1, f.sdk.deliveries)
            assertEquals(next, f.draft())
        }
    }

    private suspend fun CollaborationWorkerFixture.freeze(): CollaborationDraft {
        val editing =
            assertIs<AppResult.Success<CollaborationDraft>>(
                    koin.get<EditCollaborationDraft>()(
                        workspaceId,
                        first,
                        null,
                        UUID.randomUUID().toString(),
                        "Frozen original",
                        access(),
                    )
                )
                .value
        return assertIs<AppResult.Success<CollaborationDraft>>(
                koin.get<PrepareCollaborationDraftSend>()(workspaceId, editing, access())
            )
            .value
    }

    private suspend fun CollaborationWorkerFixture.access() =
        assertIs<AppResult.Success<CollaborationLocalAccess>>(
                koin.get<ObserveCollaborationAccess>()(workspaceId).first()
            )
            .value

    private suspend fun CollaborationWorkerFixture.draft() =
        assertIs<AppResult.Success<CollaborationDraft?>>(
                koin.get<ObserveCollaborationDraft>()(workspaceId, first).first()
            )
            .value

    private suspend fun CollaborationWorkerFixture.pending() =
        assertIs<AppResult.Success<List<CollaborationMessageRecord>>>(
                koin.get<ObserveCollaborationMessages>()(workspaceId, first).first()
            )
            .value

    private suspend fun withFixture(block: suspend (CollaborationWorkerFixture) -> Unit) {
        val f = CollaborationWorkerFixture()
        try {
            f.initialize()
            block(f)
        } finally {
            f.close()
        }
    }
}
