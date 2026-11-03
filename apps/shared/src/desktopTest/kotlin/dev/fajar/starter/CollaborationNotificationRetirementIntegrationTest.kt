package dev.fajar.starter

import dev.fajar.starter.app.notifications.CollaborationNotificationPublicationResult
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import org.koin.dsl.module

/**
 * Real Koin/encrypted SQLite; the existing publication fixture uses fictional inputs and fake OS.
 */
class CollaborationNotificationRetirementIntegrationTest {
    @Test
    fun deniedAccountDiscoversOnlyReferencesAndRetiresOwnedPreviewAfterReopen() = runBlocking {
        withFixture { f ->
            f.preferences(CollaborationNotificationPreview.Message)
            assertEquals(
                AppResult.Success(CollaborationNotificationPublicationResult.Submitted),
                f.publish(),
            )
            val original = f.publication()!!
            val denied = Failure(FailureKind.AccessDenied, "Restricted", code = "permission_denied")
            assertIs<AppResult.Success<*>>(
                f.app.koin.get<CollaborationListRepository>().restrict(f.scope.access, denied)
            )
            f.reopen()
            val reference = refs(f).references.single()
            assertEquals(original.id, reference.id)
            assertEquals(original.body.target, reference.target)
            assertIs<AppResult.Failed>(f.publish())
            val cancel = f.app.koin.get<CancelCollaborationNotificationPublication>()
            assertSame(cancel, f.app.koin.get<CancelCollaborationNotificationPublication>())
            val receipt = settled(cancel(f.parent, reference))
            assertEquals(CollaborationNotificationPublicationState.Cancelled, receipt.state)
            val retired = f.publication()!!
            assertNull(retired.body.message)
            assertNull(retired.body.actions)
            assertEquals(original.body.timestamp, retired.body.timestamp)
            assertEquals(original.body.contentHash, retired.body.contentHash)
            f.reopen()
            assertTrue(refs(f).references.isEmpty())
            assertEquals(retired, f.publication())
        }
    }

    @Test
    fun staleCleanupCannotRetireNewPrivacyRenderAndCancelledSourceCannotRevive() = runBlocking {
        withFixture { f ->
            f.preferences(CollaborationNotificationPreview.Message)
            assertIs<AppResult.Success<*>>(f.publish())
            val old = refs(f).references.single()
            f.preferences(CollaborationNotificationPreview.Hidden)
            assertIs<AppResult.Success<*>>(f.publish())
            val current = f.publication()!!
            val cancel = f.app.koin.get<CancelCollaborationNotificationPublication>()
            assertEquals(
                AppResult.Success(CollaborationNotificationPublicationSettlement.Contended),
                cancel(f.parent, old),
            )
            assertEquals(current, f.publication())
            val fresh = refs(f).references.single()
            settled(cancel(f.parent, fresh))
            assertTrue(refs(f).references.isEmpty())
            // Domain retirement supplies identity for the app owner; it performs no native I/O.
            assertEquals(fresh.target.renderId, f.native.cards.values.single().reference.renderId)
            f.reopen()
            assertEquals(
                AppResult.Success(CollaborationNotificationPublicationResult.Unchanged),
                f.publish(),
            )
            assertTrue(f.native.cards.isEmpty())
        }
    }

    @Test
    fun lostCancellationResponsePreservesRetiredWatermarkAcrossContainerReopen() = runBlocking {
        withFixture { f ->
            assertIs<AppResult.Success<*>>(f.publish())
            val reference = refs(f).references.single()
            val actual = f.app.koin.get<CollaborationNotificationPublicationRepository>()
            val uncertain =
                object : CollaborationNotificationPublicationRepository by actual {
                    override suspend fun replace(
                        access: CollaborationRecoveryAccess,
                        expected: CollaborationNotificationPublication?,
                        body: CollaborationNotificationPublicationBody,
                    ): AppResult<CollaborationNotificationPublicationWrite> {
                        val result = actual.replace(access, expected, body)
                        assertIs<AppResult.Success<*>>(result)
                        return AppResult.Failed(
                            Failure(FailureKind.Storage, "Lost committed cancellation")
                        )
                    }
                }
            f.app.koin.loadModules(
                listOf(
                    module { single<CollaborationNotificationPublicationRepository> { uncertain } }
                )
            )
            assertIs<AppResult.Failed>(
                f.app.koin.get<CancelCollaborationNotificationPublication>()(f.parent, reference)
            )
            assertEquals(
                CollaborationNotificationPublicationState.Cancelled,
                f.publication()!!.body.state,
            )
            f.reopen()
            assertTrue(refs(f).references.isEmpty())
            assertEquals(
                AppResult.Success(CollaborationNotificationPublicationResult.Unchanged),
                f.publish(),
            )
            assertTrue(f.app.sdk.enqueued.isEmpty())
        }
    }

    private suspend fun refs(f: NotificationPublisherFixture) =
        assertIs<AppResult.Success<CollaborationNotificationPublicationReferences>>(
                f.app.koin.get<ReadCollaborationNotificationPublications>()(f.parent)
            )
            .value

    private fun settled(result: AppResult<CollaborationNotificationPublicationSettlement>) =
        assertIs<CollaborationNotificationPublicationSettlement.Settled>(
            assertIs<AppResult.Success<CollaborationNotificationPublicationSettlement>>(result)
                .value
        )

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
