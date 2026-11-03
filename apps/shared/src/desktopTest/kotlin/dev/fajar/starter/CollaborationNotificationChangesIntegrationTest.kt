package dev.fajar.starter

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.koin.dsl.module

/** Actual app graph and encrypted SQLite; fictional acquisition and a fake OS surface. */
class CollaborationNotificationChangesIntegrationTest {
    @Test
    fun publicationAcknowledgmentsDoNotReplaceSourceSignalsAndSavedReadInvalidatesProjection() =
        runBlocking {
            withFixture { f ->
                val values = Channel<AppResult<Unit>>(8)
                val job = launch { f.changes().collect { values.send(it) } }
                try {
                    assertEquals(AppResult.Success(Unit), values.receive())
                    assertIs<AppResult.Success<*>>(f.publish())
                    val before = f.publication()!!
                    assertTrue(values.tryReceive().isFailure)
                    f.preferences(CollaborationNotificationPreview.Message)
                    assertEquals(AppResult.Success(Unit), values.receive())
                    assertIs<AppResult.Failed>(
                        f.app.koin.get<ValidateCollaborationNotificationPublication>()(before)
                    )
                    assertIs<AppResult.Success<*>>(f.publish())
                    assertTrue(values.tryReceive().isFailure)
                    val c = f.candidate
                    assertIs<AppResult.Success<*>>(
                        f.app.koin.get<MarkCollaborationMessagesRead>()(
                            f.scope,
                            c.conversationId,
                            c.roomId,
                            listOf(c.eventId),
                            CollaborationReadReceiptIntent(
                                UUID.randomUUID().toString(),
                                c.eventId,
                                CollaborationReadReceiptVisibility.Private,
                            ),
                        )
                    )
                    assertEquals(AppResult.Success(Unit), values.receive())
                    assertEquals(CollaborationNotificationSuppression.Read, f.suppression())
                    assertTrue(f.app.sdk.enqueued.isEmpty())
                    // The observer only requests revalidation; no application host removes the card
                    // yet.
                    assertEquals(1, f.native.cards.size)
                } finally {
                    withContext(NonCancellable) { job.cancelAndJoin() }
                    values.close()
                }
                f.reopen()
                assertEquals(CollaborationNotificationSuppression.Read, f.suppression())
                assertEquals(AppResult.Success(Unit), f.changes().first())
            }
        }

    @Test
    fun redactedRetainedOriginalWakesTheRealGraphWithoutAnyNewSend() = runBlocking {
        withFixture { f ->
            assertIs<AppResult.Success<*>>(f.publish())
            val before = f.publication()!!
            val values = Channel<AppResult<Unit>>(8)
            val job = launch { f.changes().collect { values.send(it) } }
            try {
                assertEquals(AppResult.Success(Unit), values.receive())
                val c = f.candidate
                val history = f.app.koin.get<CollaborationHistoryRepository>()
                val original =
                    assertIs<AppResult.Success<CollaborationHistoryMessage?>>(
                            history.getByEvent(
                                f.scope.access,
                                c.conversationId,
                                c.roomId,
                                c.eventId,
                            )
                        )
                        .value!!
                assertEquals(
                    AppResult.Success(true),
                    history.merge(
                        f.scope.access,
                        c.conversationId,
                        c.roomId,
                        listOf(CollaborationHistoryChange.Redacted(c.eventId, original.sender)),
                    ),
                )
                assertEquals(AppResult.Success(Unit), values.receive())
                assertEquals(CollaborationNotificationSuppression.Redacted, f.suppression())
                assertIs<AppResult.Failed>(
                    f.app.koin.get<ValidateCollaborationNotificationPublication>()(before)
                )
                assertTrue(f.app.sdk.enqueued.isEmpty())
            } finally {
                withContext(NonCancellable) { job.cancelAndJoin() }
                values.close()
            }
        }
    }

    @Test
    fun companyDenialJoinsSourceSubscriptionsAndRemainsClosedAcrossContainerReopen() = runBlocking {
        withFixture { f ->
            val actual = f.app.koin.get<CollaborationNotificationChangeRepository>()
            val active = AtomicInteger()
            val tracked =
                object : CollaborationNotificationChangeRepository {
                    override fun observe(access: CollaborationLocalAccess, conversationId: String) =
                        flow {
                            active.incrementAndGet()
                            try {
                                emitAll(actual.observe(access, conversationId))
                            } finally {
                                active.decrementAndGet()
                            }
                        }
                }
            f.app.koin.loadModules(
                listOf(module { single<CollaborationNotificationChangeRepository> { tracked } })
            )
            val denied = Failure(FailureKind.AccessDenied, "Restricted", code = "permission_denied")
            val values = Channel<AppResult<Unit>>(8)
            val job = launch { f.changes().collect { values.send(it) } }
            try {
                assertEquals(AppResult.Success(Unit), values.receive())
                assertEquals(1, active.get())
                assertIs<AppResult.Success<*>>(
                    f.app.koin.get<CollaborationListRepository>().restrict(f.scope.access, denied)
                )
                assertDenial(denied, values.receive())
                assertEquals(0, active.get())
                assertIs<AppResult.Failed>(f.publish())
            } finally {
                withContext(NonCancellable) { job.cancelAndJoin() }
                values.close()
            }
            f.reopen()
            assertDenial(denied, f.changes().first())
            assertTrue(f.app.sdk.enqueued.isEmpty())
        }
    }

    private fun NotificationPublisherFixture.changes() =
        app.koin.get<ObserveCollaborationNotificationChanges>()(
            parent,
            candidate.companyId,
            candidate.conversationId,
        )

    private fun assertDenial(expected: Failure, result: AppResult<Unit>) {
        val actual = assertIs<AppResult.Failed>(result).failure
        assertEquals(expected.kind, actual.kind)
        assertEquals(expected.code, actual.code)
    }

    private suspend fun NotificationPublisherFixture.suppression() =
        assertIs<CollaborationNotificationProjection.Suppressed>(
                assertIs<AppResult.Success<CollaborationNotificationProjection>>(
                        app.koin.get<PrepareCollaborationMessageNotification>()(
                            scope,
                            candidate.conversationId,
                            candidate.roomId,
                            candidate.eventId,
                        )
                    )
                    .value
            )
            .reason

    private suspend fun withFixture(block: suspend (NotificationPublisherFixture) -> Unit) {
        val f = NotificationPublisherFixture()
        try {
            f.initialize()
            block(f)
        } finally {
            f.close()
        }
    }
}
