package dev.fajar.starter

import dev.fajar.starter.app.navigation.*
import dev.fajar.starter.app.notifications.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.notifications.data.dto.*
import dev.fajar.starter.workspace.domain.entities.*
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import dev.fajar.starter.workspace.domain.usecases.LeaveWorkspace
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import org.koin.dsl.module

/** Actual Koin, sealed inputs and encrypted SQLite; native display remains simulated. */
class NativeNotificationNavigationIntegrationTest {
    @Test
    fun authenticatedTapOpensWhileDisplayHostIsUnboundWithoutReadingOrSending() = runBlocking {
        fixture { f, repository ->
            val workspace = assertNotNull(success(repository.current(f.app.session.id)))
            val input = f.input()
            val publication = f.publication()
            val reads = f.reads()
            assertEquals(
                "client.collaboration_notification_host_unavailable",
                assertIs<AppResult.Failed>(
                        f.app.koin.get<CollaborationNotificationDelivery>().act(input)
                    )
                    .failure
                    .code,
            )
            val origin = f.origin(workspace.id)
            val link =
                success(f.app.koin.get<NativeNotificationNavigation>()(input, origin, workspace))
            assertEquals(
                PendingAppLink(
                    AppLink.ChatThread(
                        origin.owner,
                        f.candidate.companyId,
                        f.candidate.conversationId,
                    ),
                    origin.owner.sessionId,
                    workspace.id,
                ),
                link,
            )
            assertEquals(AppResult.Success(workspace), repository.current(f.app.session.id))
            assertEquals(publication, f.publication())
            assertEquals(reads, f.reads())
            assertEquals(1, f.native.cards.size)
            assertTrue(f.app.sdk.enqueued.isEmpty())
        }
    }

    @Test
    fun onlyAnAuthenticatedExactReferenceCanAcquireTheCompanyWhenNoneIsSelected() = runBlocking {
        fixture { f, repository ->
            val workspace = assertNotNull(success(repository.current(f.app.session.id)))
            assertIs<AppResult.Success<*>>(f.app.koin.get<LeaveWorkspace>()(workspace.id))
            val input = f.input()
            val origin = f.origin(null)
            val open = f.app.koin.get<NativeNotificationNavigation>()
            val invalid =
                input.copy(
                    reference = input.reference.copy(renderId = UUID.randomUUID().toString())
                )
            assertEquals(
                FailureKind.Validation,
                assertIs<AppResult.Failed>(open(invalid, origin, null)).failure.kind,
            )
            assertEquals(AppResult.Success(null), repository.current(f.app.session.id))
            val link = success(open(input, origin, null))
            val selected = assertNotNull(success(repository.current(f.app.session.id)))
            assertEquals(f.candidate.companyId, selected.company.id)
            assertEquals(selected.id, link.workspaceId)
            assertEquals(
                f.candidate.conversationId,
                assertIs<AppLink.ChatThread>(link.target).conversationId,
            )
            assertTrue(f.reads().pending.isEmpty())
            assertTrue(f.app.sdk.enqueued.isEmpty())
        }
    }

    private fun NotificationPublisherFixture.input(): ActionNotificationInput {
        val card = native.cards.values.single()
        return ActionNotificationInput(
            card.reference,
            ActionNotificationKind.Open,
            card.envelopes.getValue(ActionNotificationKind.Open),
        )
    }

    private fun NotificationPublisherFixture.origin(workspaceId: String?) =
        WorkspaceSelectionOrigin(
            WorkspaceSelectionOwner(
                parent.sessionId,
                parent.audience,
                parent.accountId,
                parent.nativeSessionId,
            ),
            workspaceId,
        )

    private suspend fun NotificationPublisherFixture.reads() =
        success(
            app.koin
                .get<CollaborationReadStateRepository>()
                .read(
                    scope.access,
                    candidate.conversationId,
                    candidate.roomId,
                    listOf(candidate.eventId),
                )
        )

    private fun <T> success(value: AppResult<T>): T = assertIs<AppResult.Success<T>>(value).value

    private suspend fun fixture(
        block: suspend (NotificationPublisherFixture, WorkspaceRepository) -> Unit
    ) {
        val f = NotificationPublisherFixture()
        val repository = f.app.koin.get<WorkspaceRepository>()
        try {
            f.initialize()
            // Restore the actual repository after the incoming-only fixture's no-selection spy.
            f.app.koin.loadModules(listOf(module { single<WorkspaceRepository> { repository } }))
            assertEquals(
                AppResult.Success(CollaborationNotificationPublicationResult.Submitted),
                f.publish(),
            )
            block(f, repository)
        } finally {
            f.close()
        }
    }
}
