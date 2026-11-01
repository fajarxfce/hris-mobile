package dev.fajar.starter

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.fixtures.CollaborationWorkerFixture
import dev.fajar.starter.sync.domain.SyncResult
import java.util.UUID
import kotlin.test.*
import kotlin.time.Clock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.koin.dsl.module

class CollaborationDraftWorkerTest {
    @Test
    fun discoveryContentionRetriesBeforeAndAfterAdmissionWithoutDuplicatingTheMessage() =
        runBlocking {
            for (failAt in listOf(1, 2)) withFixture { f ->
                val frozen = f.freeze("Concurrent editor during discovery $failAt")
                val drafts = f.koin.get<CollaborationDraftRepository>()
                var reads = 0
                val fault =
                    object : CollaborationDraftRepository by drafts {
                        override suspend fun prepared(
                            access: CollaborationLocalAccess,
                            afterConversationId: String?,
                            limit: Int,
                        ): AppResult<CollaborationPreparedDraftPage> {
                            reads++
                            return if (reads == failAt)
                                AppResult.Failed(
                                    Failure(
                                        FailureKind.Conflict,
                                        "Draft changed",
                                        code = "client.collaboration_draft_changed",
                                    )
                                )
                            else drafts.prepared(access, afterConversationId, limit)
                        }
                    }
                f.koin.loadModules(
                    listOf(module { single<CollaborationDraftRepository> { fault } })
                )
                val retry = assertIs<SyncResult.Retry>(f.worker())
                assertEquals("client.collaboration_draft_changed", retry.failure?.code)
                assertEquals(SyncResult.Complete, f.worker())
                assertEquals(listOf(frozen.submissionId), f.sdk.enqueued)
                assertEquals(1, f.sdk.deliveries)
                assertEquals("", f.draft()?.text)
            }
        }

    @Test
    fun frozenOnlyIntentRestoresAndDeliversAfterContainerRestartWithoutOpeningChat() = runBlocking {
        withFixture { f ->
            val frozen = f.freeze("  prepared before process death\n  ")
            assertEquals(listOf(QueueCollaborationText.DELIVERY_KEY), f.scheduled)
            assertEquals(0, f.sdk.deliveries)
            assertTrue(f.pending().isEmpty())
            f.reopen()
            assertEquals(SyncResult.Complete, f.worker())
            assertEquals(listOf(frozen.submissionId), f.sdk.enqueued)
            assertEquals(1, f.sdk.deliveries)
            assertEquals("", f.draft()?.text)
            assertNull(f.draft()?.submissionId)
            assertEquals(SyncResult.Complete, f.worker())
            assertEquals(1, f.sdk.deliveries)
        }
    }

    @Test
    fun missingNativeSetupStillMovesFrozenIntentIntoDurableOutboxExactlyOnce() = runBlocking {
        withFixture { f ->
            val frozen = f.freeze("Waiting for device setup")
            f.server.collaboration.activeDevice = false
            f.reopen()
            assertIs<SyncResult.Blocked>(f.worker())
            assertEquals("", f.draft()?.text)
            assertEquals(frozen.submissionId, f.pending().single().intent.id)
            assertEquals(0, f.sdk.deliveries)
            f.server.collaboration.activeDevice = true
            assertEquals(SyncResult.Complete, f.worker())
            assertEquals(listOf(frozen.submissionId), f.sdk.enqueued)
        }
    }

    @Test
    fun lostQueueCommitReplyCannotCreateAnotherMessageAfterRestart() = runBlocking {
        withFixture { f ->
            val frozen = f.freeze("Commit reply lost")
            val messages = f.koin.get<CollaborationMessageRepository>()
            val fault =
                object : CollaborationMessageRepository by messages {
                    override suspend fun insert(
                        access: CollaborationLocalAccess,
                        intent: CollaborationMessageIntent,
                    ): AppResult<CollaborationMessageInsertion> {
                        assertEquals(
                            AppResult.Success(CollaborationMessageInsertion.STORED),
                            messages.insert(access, intent),
                        )
                        return AppResult.Failed(Failure(FailureKind.Storage, "Unavailable"))
                    }
                }
            f.koin.loadModules(listOf(module { single<CollaborationMessageRepository> { fault } }))
            assertIs<SyncResult.Blocked>(f.worker())
            assertEquals(frozen.submissionId, f.draft()?.submissionId)
            f.reopen()
            assertEquals(SyncResult.Complete, f.worker())
            assertEquals("", f.draft()?.text)
            assertEquals(listOf(frozen.submissionId), f.sdk.enqueued)
            assertEquals(1, f.sdk.deliveries)
        }
    }

    @Test
    fun lostClearReplyAndLaterEditorTextArePreservedAcrossWorkerRestart() = runBlocking {
        withFixture { f ->
            val frozen = f.freeze("Original operation")
            val drafts = f.koin.get<CollaborationDraftRepository>()
            val fault =
                object : CollaborationDraftRepository by drafts {
                    override suspend fun replace(
                        access: CollaborationLocalAccess,
                        expected: CollaborationDraft?,
                        replacement: CollaborationDraft,
                    ): AppResult<Boolean> {
                        val result = drafts.replace(access, expected, replacement)
                        return if (result == AppResult.Success(true) && replacement.text.isEmpty())
                            AppResult.Failed(Failure(FailureKind.Storage, "Unavailable"))
                        else result
                    }
                }
            f.koin.loadModules(listOf(module { single<CollaborationDraftRepository> { fault } }))
            assertIs<SyncResult.Blocked>(f.worker())
            val cleared = f.draft()!!
            assertEquals("", cleared.text)
            val next =
                assertIs<AppResult.Success<CollaborationDraft>>(
                        f.koin.get<EditCollaborationDraft>()(
                            f.workspaceId,
                            f.first,
                            cleared,
                            UUID.randomUUID().toString(),
                            "Later editor text",
                            f.access(),
                        )
                    )
                    .value
            f.reopen()
            assertEquals(SyncResult.Complete, f.worker())
            assertEquals(next, f.draft())
            assertEquals(listOf(frozen.submissionId), f.sdk.enqueued)
            assertEquals(1, f.sdk.deliveries)
        }
    }

    @Test
    fun moreThanOneBatchOfUnavailableConversationsCannotSpinOrStarveAValidMessage() = runBlocking {
        withFixture { f ->
            val access = f.access()
            val drafts = f.koin.get<CollaborationDraftRepository>()
            val lostContexts =
                (1..10).map { n ->
                    CollaborationDraft(
                        "00000000-0000-0000-0000-" + n.toString().padStart(12, '0'),
                        UUID.randomUUID().toString(),
                        "Retained unavailable conversation $n",
                        Clock.System.now(),
                        UUID.randomUUID().toString(),
                    )
                }
            // Models previously admitted intents whose conversation metadata is no longer retained.
            for (draft in lostContexts) assertEquals(
                AppResult.Success(true),
                drafts.replace(access, null, draft),
            )
            val valid = f.freeze("Independent valid conversation")
            assertIs<SyncResult.Retry>(f.worker())
            assertEquals(0, f.sdk.deliveries)
            assertEquals(
                8,
                lostContexts.count {
                    assertIs<AppResult.Success<CollaborationDraft?>>(
                            drafts.get(access, it.conversationId)
                        )
                        .value!!
                        .reviewRequired
                },
            )
            assertEquals(SyncResult.Complete, f.worker())
            assertEquals(listOf(valid.submissionId), f.sdk.enqueued)
            assertEquals(1, f.sdk.deliveries)
            for (original in lostContexts) {
                val held =
                    assertIs<AppResult.Success<CollaborationDraft?>>(
                            drafts.get(access, original.conversationId)
                        )
                        .value!!
                assertTrue(held.reviewRequired)
                assertEquals(original.submissionId, held.submissionId)
                assertEquals(original.text, held.text)
            }
            f.reopen()
            assertEquals(SyncResult.Complete, f.worker())
            assertEquals(1, f.sdk.deliveries)
        }
    }

    private suspend fun CollaborationWorkerFixture.freeze(text: String): CollaborationDraft {
        val owned = access()
        val edited =
            assertIs<AppResult.Success<CollaborationDraft>>(
                    koin.get<EditCollaborationDraft>()(
                        workspaceId,
                        first,
                        draft(),
                        UUID.randomUUID().toString(),
                        text,
                        owned,
                    )
                )
                .value
        return assertIs<AppResult.Success<CollaborationDraft>>(
                koin.get<PrepareCollaborationDraftSend>()(workspaceId, edited, owned)
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
        val fixture = CollaborationWorkerFixture()
        try {
            fixture.initialize()
            block(fixture)
        } finally {
            fixture.close()
        }
    }
}
