package dev.fajar.starter

import dev.fajar.starter.app.bootstrap.ApplicationCollaborationLifetime
import dev.fajar.starter.app.bootstrap.ApplicationCollaborationState
import dev.fajar.starter.app.notifications.*
import dev.fajar.starter.app.work.CollaborationIncomingRecovery
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationReadStateRepository
import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.notifications.data.dto.*
import dev.fajar.starter.sync.domain.SyncResult
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.test.*
import kotlinx.coroutines.*
import org.koin.core.parameter.parametersOf
import org.koin.dsl.module

/** Actual host/process/Koin/SQLite; provider and OS display are explicitly fictional. */
class ApplicationNotificationHostIntegrationTest {
    @Test
    fun coldMarkReadWaitsForRestoredApplicationSessionThenRetiresTheExactJournal() = runBlocking {
        val f = NotificationPublisherFixture()
        val workspace = f.app.koin.get<WorkspaceRepository>()
        var host: ApplicationNotificationHost? = null
        try {
            f.initialize()
            assertIs<AppResult.Success<*>>(f.publish())
            val card = f.native.cards.values.single()
            val input =
                ActionNotificationInput(
                    card.reference,
                    ActionNotificationKind.MarkRead,
                    card.envelopes.getValue(ActionNotificationKind.MarkRead),
                )
            f.owner.closeAndJoin()
            f.app.koin.loadModules(listOf(module { single<WorkspaceRepository> { workspace } }))
            val lifetime = f.app.koin.get<ApplicationCollaborationLifetime>()
            assertEquals(ApplicationCollaborationState.Pending, lifetime.state.value)
            val current =
                f.app.koin.get<ApplicationNotificationHost> {
                    parametersOf(
                        f.native,
                        CollaborationNotificationCopySource {
                            CollaborationNotificationCopy("HRIS", "New message")
                        },
                    )
                }
            host = current
            current.start()
            val completed = CompletableDeferred<CollaborationActionCallbackOutcome>()
            f.app.koin.get<CollaborationNotificationActionCallbacks>().receive(input) {
                completed.complete(it)
            }
            assertNull(withTimeoutOrNull(150) { completed.await() })
            lifetime.start()
            assertEquals(
                CollaborationActionCallbackOutcome.Applied,
                withTimeout(8_000) { completed.await() },
            )
            assertEquals(
                CollaborationNotificationPublicationState.Cancelled,
                f.publication()!!.body.state,
            )
            val read =
                assertIs<AppResult.Success<CollaborationReadState>>(
                        f.app.koin
                            .get<CollaborationReadStateRepository>()
                            .read(
                                f.scope.access,
                                f.candidate.conversationId,
                                f.candidate.roomId,
                                listOf(f.candidate.eventId),
                            )
                    )
                    .value
            assertEquals(
                listOf(CollaborationReadReceiptVisibility.Private),
                read.pending.map { it.visibility },
            )
            assertTrue(f.native.cards.isEmpty())
            assertTrue(f.app.sdk.enqueued.isEmpty())
        } finally {
            host?.closeAndJoin()
            f.close()
        }
    }

    @Test
    fun applicationParentOwnsDeliveryAndEachPublicationUsesCurrentLocalizedCopy() = runBlocking {
        val f = NotificationPublisherFixture()
        val workspace = f.app.koin.get<WorkspaceRepository>()
        var host: ApplicationNotificationHost? = null
        try {
            f.initialize()
            f.preferences(CollaborationNotificationPreview.Hidden)
            f.owner.closeAndJoin()
            f.app.koin.loadModules(listOf(module { single<WorkspaceRepository> { workspace } }))
            var copy = CollaborationNotificationCopy("HRIS", "New message")
            val source = CollaborationNotificationCopySource { copy }
            val current =
                f.app.koin.get<ApplicationNotificationHost> { parametersOf(f.native, source) }
            host = current
            f.app.koin.get<ApplicationCollaborationLifetime>().start()
            current.start()
            val delivery = f.app.koin.get<CollaborationNotificationDelivery>()
            assertEquals(
                AppResult.Success(CollaborationNotificationPublicationResult.Submitted),
                delivery.publish(f.parent, f.candidate),
            )
            val first = f.native.cards.values.single()
            assertEquals("HRIS", first.title)
            assertEquals("New message", first.body)
            copy = CollaborationNotificationCopy("HRIS", "Pesan baru")
            val next = f.app.sdk.incoming(f.app.first, "Fictional next original")
            assertEquals(
                SyncResult.Complete,
                f.app.koin.get<CollaborationIncomingRecovery>()(f.app.incomingGrant()),
            )
            assertEquals(
                AppResult.Success(CollaborationNotificationPublicationResult.Submitted),
                delivery.publish(f.parent, f.candidate.copy(eventId = next.eventId)),
            )
            val updated = f.native.cards.values.single()
            assertNotEquals(first.reference, updated.reference)
            assertEquals("Pesan baru", updated.body)
            assertFalse(updated.body.contains("Fictional"))
            current.closeAndJoin()
            assertTrue(f.native.cards.isEmpty())
            assertIs<AppResult.Failed>(delivery.retry())
            assertTrue(f.app.sdk.enqueued.isEmpty())
        } finally {
            host?.closeAndJoin()
            f.close()
        }
    }
}
