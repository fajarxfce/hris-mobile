package dev.fajar.starter

import dev.fajar.starter.app.work.CollaborationIncomingRecovery
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.localListAccess
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.fixtures.CollaborationWorkerFixture
import dev.fajar.starter.identity.domain.entities.Session
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.sync.domain.SyncResult
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import org.koin.dsl.module

/** Actual Koin, SQLite and cipher; native OS submission is not simulated by a success assertion. */
class CollaborationNotificationPublicationLifecycleIntegrationTest {
    @Test
    fun exactSubmissionStateAndStrippedWatermarkReopenWithTheSameOpaqueCard() = runBlocking {
        withFixture { f, event ->
            val original = f.issue(event)
            assertEquals(
                AppResult.Success(Unit),
                f.koin.get<ValidateCollaborationNotificationPublication>()(original),
            )
            val acknowledged =
                settled(
                    f.koin.get<FinishCollaborationNotificationPublication>()(
                        original,
                        CollaborationNotificationPublicationState.Published,
                    )
                )
            f.reopen()
            f.withoutIncomingSelection()
            val published = f.read()!!
            assertEquals(acknowledged.version, published.version)
            assertEquals(original.body.actions, published.body.actions)
            assertEquals(
                AppResult.Success(Unit),
                f.koin.get<ValidateCollaborationNotificationPublication>()(published),
            )
            val retired =
                settled(
                    f.koin.get<FinishCollaborationNotificationPublication>()(
                        published,
                        CollaborationNotificationPublicationState.Dismissed,
                    )
                )
            f.reopen()
            f.withoutIncomingSelection()
            val watermark = f.read()!!
            assertEquals(retired.version, watermark.version)
            assertNull(watermark.body.message)
            assertNull(watermark.body.actions)
            assertEquals(original.body.target, watermark.body.target)
            assertIs<AppResult.Failed>(
                f.koin.get<ValidateCollaborationNotificationPublication>()(watermark)
            )
            assertTrue(f.sdk.enqueued.isEmpty())
        }
    }

    @Test
    fun newLocalAndNativeFamilyCanRetireItsDeniedOldCardWithoutRepublishing() = runBlocking {
        withFixture { f, event ->
            val original = f.issue(event)
            val denied = Failure(FailureKind.AccessDenied, "Restricted", code = "permission_denied")
            assertIs<AppResult.Success<*>>(
                f.koin.get<CollaborationListRepository>().restrict(f.scope().access, denied)
            )
            val blocked =
                assertIs<AppResult.Failed>(
                        f.koin.get<ValidateCollaborationNotificationPublication>()(original)
                    )
                    .failure
            assertEquals(denied.kind, blocked.kind)
            assertEquals(denied.code, blocked.code)
            val sessions = f.koin.get<SessionRepository>()
            val old = assertIs<AppResult.Success<Session?>>(sessions.current()).value!!
            val next =
                old.copy(
                    id = UUID.randomUUID().toString(),
                    binding = old.binding.copy(serverId = UUID.randomUUID().toString()),
                )
            assertEquals(AppResult.Success(true), sessions.compareAndSet(old, next))
            f.session = next
            f.reopen()
            f.withoutIncomingSelection()
            assertIs<AppResult.Failed>(
                f.koin.get<FinishCollaborationNotificationPublication>()(
                    original,
                    CollaborationNotificationPublicationState.Published,
                )
            )
            settled(
                f.koin.get<FinishCollaborationNotificationPublication>()(
                    original,
                    CollaborationNotificationPublicationState.Cancelled,
                )
            )
            val retained = f.read()!!
            assertEquals(CollaborationNotificationPublicationState.Cancelled, retained.body.state)
            assertEquals(original.body.parent, retained.body.parent)
            assertNull(retained.body.message)
            assertNull(retained.body.actions)
        }
    }

    @Test
    fun oldRenderCannotSettleAReplacementWithDifferentPrivacy() = runBlocking {
        withFixture { f, event ->
            val original = f.issue(event)
            val settings =
                assertIs<AppResult.Success<CollaborationNotificationSettingsState>>(
                        f.koin.get<ReadCollaborationNotificationSettings>()(f.scope(), f.first)
                    )
                    .value
            assertIs<AppResult.Success<*>>(
                f.koin.get<UpdateCollaborationNotificationSettings>()(
                    f.scope(),
                    f.first,
                    CollaborationNotificationSettingsChange(
                        UUID.randomUUID().toString(),
                        settings.version,
                        CollaborationNotificationSettings(
                            preview = CollaborationNotificationPreview.Hidden
                        ),
                    ),
                )
            )
            val current = f.issue(event)
            assertNotEquals(original.body.target.renderId, current.body.target.renderId)
            assertEquals(
                AppResult.Success(CollaborationNotificationPublicationSettlement.Contended),
                f.koin.get<FinishCollaborationNotificationPublication>()(
                    original,
                    CollaborationNotificationPublicationState.Cancelled,
                ),
            )
            assertEquals(current, f.read())
            assertEquals(
                AppResult.Success(Unit),
                f.koin.get<ValidateCollaborationNotificationPublication>()(current),
            )
        }
    }

    @Test
    fun cancellationAfterActualCommitJoinsAndLeavesTheWatermarkAcrossAppReopen() = runBlocking {
        withFixture { f, event ->
            val original = f.issue(event)
            val raw = f.koin.get<CollaborationNotificationPublicationRepository>()
            val entered = CompletableDeferred<Unit>()
            var joined = false
            val paused =
                object : CollaborationNotificationPublicationRepository by raw {
                    override suspend fun replace(
                        access: CollaborationRecoveryAccess,
                        expected: CollaborationNotificationPublication?,
                        body: CollaborationNotificationPublicationBody,
                    ): AppResult<CollaborationNotificationPublicationWrite> {
                        assertIs<CollaborationNotificationPublicationWrite.Stored>(
                            assertIs<AppResult.Success<CollaborationNotificationPublicationWrite>>(
                                    raw.replace(access, expected, body)
                                )
                                .value
                        )
                        try {
                            entered.complete(Unit)
                            awaitCancellation()
                        } finally {
                            joined = true
                        }
                    }
                }
            f.koin.loadModules(
                listOf(module { single<CollaborationNotificationPublicationRepository> { paused } })
            )
            coroutineScope {
                val job = async {
                    f.koin.get<FinishCollaborationNotificationPublication>()(
                        original,
                        CollaborationNotificationPublicationState.Cancelled,
                    )
                }
                select {
                    entered.onAwait {}
                    job.onAwait { fail("Settlement returned before commit barrier") }
                }
                job.cancelAndJoin()
            }
            assertTrue(joined)
            f.reopen()
            f.withoutIncomingSelection()
            val retained = f.read()!!
            assertEquals(CollaborationNotificationPublicationState.Cancelled, retained.body.state)
            assertNull(retained.body.message)
            assertNull(retained.body.actions)
            assertEquals(
                AppResult.Success(CollaborationNotificationPublicationSettlement.Contended),
                f.koin.get<FinishCollaborationNotificationPublication>()(
                    original,
                    CollaborationNotificationPublicationState.Cancelled,
                ),
            )
            assertEquals(retained, f.read())
        }
    }

    private fun CollaborationWorkerFixture.scope() =
        CollaborationLocalScope.Captured(
            incomingGrant().owner.localListAccess(),
            session.binding.serverId,
        )

    private suspend fun CollaborationWorkerFixture.issue(
        event: String
    ): CollaborationNotificationPublication {
        val message =
            assertIs<CollaborationNotificationProjection.Ready>(
                    assertIs<AppResult.Success<CollaborationNotificationProjection>>(
                            koin.get<PrepareCollaborationMessageNotification>()(
                                scope(),
                                first,
                                server.collaboration.roomId(first),
                                event,
                            )
                        )
                        .value
                )
                .message
        return assertIs<CollaborationNotificationIssuance.Ready>(
                assertIs<AppResult.Success<CollaborationNotificationIssuance>>(
                        koin.get<IssueCollaborationMessageNotification>()(scope(), message)
                    )
                    .value
            )
            .publication
    }

    private suspend fun CollaborationWorkerFixture.read(): CollaborationNotificationPublication? {
        val a = scope().access
        return assertIs<AppResult.Success<CollaborationNotificationPublication?>>(
                koin
                    .get<CollaborationNotificationPublicationRepository>()
                    .read(
                        CollaborationRecoveryAccess(a.sessionId, a.audience, a.accountId),
                        a.companyId,
                        first,
                    )
            )
            .value
    }

    private fun settled(result: AppResult<CollaborationNotificationPublicationSettlement>) =
        assertIs<CollaborationNotificationPublicationSettlement.Settled>(
            assertIs<AppResult.Success<CollaborationNotificationPublicationSettlement>>(result)
                .value
        )

    private suspend fun withFixture(block: suspend (CollaborationWorkerFixture, String) -> Unit) {
        val f = CollaborationWorkerFixture()
        try {
            f.initialize()
            f.server.collaboration.listedIds = listOf(f.first)
            f.sdk.backgroundEnabled = true
            f.withoutIncomingSelection()
            val event = f.sdk.incoming(f.first, "Fictional publication lifecycle original")
            assertEquals(
                SyncResult.Complete,
                f.koin.get<CollaborationIncomingRecovery>()(f.incomingGrant()),
            )
            assertIs<AppResult.Success<*>>(
                f.koin
                    .get<CollaborationIdentityRepository>()
                    .retain(f.scope().access, CollaborationSelfIdentity(f.sdk.sender))
            )
            assertIs<AppResult.Success<*>>(
                f.koin.get<UpdateCollaborationNotificationSettings>()(
                    f.scope(),
                    f.first,
                    CollaborationNotificationSettingsChange(
                        UUID.randomUUID().toString(),
                        null,
                        CollaborationNotificationSettings(
                            preview = CollaborationNotificationPreview.Message
                        ),
                    ),
                )
            )
            block(f, event.eventId)
        } finally {
            f.close()
        }
    }
}
