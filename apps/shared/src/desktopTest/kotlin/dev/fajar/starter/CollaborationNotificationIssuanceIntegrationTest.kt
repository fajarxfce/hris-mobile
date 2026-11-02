package dev.fajar.starter

import dev.fajar.starter.app.notifications.*
import dev.fajar.starter.app.work.CollaborationIncomingRecovery
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.localListAccess
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.fixtures.CollaborationWorkerFixture
import dev.fajar.starter.sync.domain.SyncResult
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import org.koin.dsl.module

/**
 * Actual application graph/SQLite/action cipher, simulated SDK and fictional HTTP; no OS display.
 */
class CollaborationNotificationIssuanceIntegrationTest {
    @Test
    fun issuedReplyEnvelopeReopensAndQueuesOneOriginalCommandThroughApplicationFlow() =
        runBlocking {
            withFixture { f, event ->
                val message = f.project(event)
                val first =
                    ready(f.koin.get<IssueCollaborationMessageNotification>()(f.scope(), message))
                f.reopen()
                f.withoutIncomingSelection()
                assertEquals(
                    first,
                    ready(f.koin.get<IssueCollaborationMessageNotification>()(f.scope(), message)),
                )
                val tokens = first.body.actions!!
                val invocation =
                    assertIs<AppResult.Success<CollaborationNotificationInvocation>>(
                            f.koin.get<OpenCollaborationNotificationAction>()(tokens.reply)
                        )
                        .value
                val reply = assertIs<CollaborationNotificationAction.Reply>(invocation.action)
                val result =
                    assertIs<AppResult.Success<CollaborationNotificationReplyResult>>(
                            f.koin
                                .get<CollaborationNotificationReplies>()
                                .submit(
                                    tokens.reply,
                                    "Fictional reply through retained publication",
                                )
                        )
                        .value
                assertEquals(
                    CollaborationMessageState.Queued,
                    assertIs<CollaborationNotificationReplyResult.CommandRetained>(result).state,
                )
                val retained =
                    assertIs<AppResult.Success<CollaborationMessageRecord?>>(
                            f.koin
                                .get<CollaborationMessageRepository>()
                                .get(f.scope().access, reply.operationId)
                        )
                        .value!!
                assertEquals("Fictional reply through retained publication", retained.intent.text)
                assertEquals(first, f.readPublication())
                assertTrue(f.sdk.enqueued.isEmpty())
            }
        }

    @Test
    fun uncertainActualJournalWriteReplaysEncryptedEnvelopesAfterApplicationReopen() = runBlocking {
        withFixture { f, event ->
            val original = f.koin.get<CollaborationNotificationPublicationRepository>()
            var writes = 0
            val uncertain =
                object : CollaborationNotificationPublicationRepository by original {
                    override suspend fun replace(
                        access: CollaborationRecoveryAccess,
                        expected: CollaborationNotificationPublication?,
                        body: CollaborationNotificationPublicationBody,
                    ): AppResult<CollaborationNotificationPublicationWrite> {
                        writes++
                        assertIs<CollaborationNotificationPublicationWrite.Stored>(
                            assertIs<AppResult.Success<CollaborationNotificationPublicationWrite>>(
                                    original.replace(access, expected, body)
                                )
                                .value
                        )
                        return AppResult.Failed(
                            Failure(FailureKind.Storage, "Lost storage response")
                        )
                    }
                }
            f.koin.loadModules(
                listOf(
                    module { single<CollaborationNotificationPublicationRepository> { uncertain } }
                )
            )
            val message = f.project(event)
            assertIs<AppResult.Failed>(
                f.koin.get<IssueCollaborationMessageNotification>()(f.scope(), message)
            )
            val retained = f.readPublication()!!
            assertEquals(1, writes)
            f.reopen()
            f.withoutIncomingSelection()
            assertEquals(
                retained,
                ready(f.koin.get<IssueCollaborationMessageNotification>()(f.scope(), message)),
            )
            assertEquals(1, writes)
        }
    }

    @Test
    fun confirmedCompanyDenialNeverReturnsTheRawRetainedPreview() = runBlocking {
        withFixture { f, event ->
            val message = f.project(event)
            val retained =
                ready(f.koin.get<IssueCollaborationMessageNotification>()(f.scope(), message))
            val denied = Failure(FailureKind.AccessDenied, "Restricted", code = "permission_denied")
            assertIs<AppResult.Success<*>>(
                f.koin.get<CollaborationListRepository>().restrict(f.scope().access, denied)
            )
            val firstFailure =
                assertIs<AppResult.Failed>(
                        f.koin.get<IssueCollaborationMessageNotification>()(f.scope(), message)
                    )
                    .failure
            assertEquals(denied.kind, firstFailure.kind)
            assertEquals(denied.code, firstFailure.code)
            f.reopen()
            f.withoutIncomingSelection()
            val reopenedFailure =
                assertIs<AppResult.Failed>(
                        f.koin.get<IssueCollaborationMessageNotification>()(f.scope(), message)
                    )
                    .failure
            assertEquals(denied.kind, reopenedFailure.kind)
            assertEquals(denied.code, reopenedFailure.code)
            assertEquals(retained, f.readPublication())
        }
    }

    private fun CollaborationWorkerFixture.scope() =
        CollaborationLocalScope.Captured(
            incomingGrant().owner.localListAccess(),
            session.binding.serverId,
        )

    private suspend fun CollaborationWorkerFixture.project(event: String) =
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

    private suspend fun CollaborationWorkerFixture.readPublication():
        CollaborationNotificationPublication? {
        val access = scope().access
        return assertIs<AppResult.Success<CollaborationNotificationPublication?>>(
                koin
                    .get<CollaborationNotificationPublicationRepository>()
                    .read(
                        CollaborationRecoveryAccess(
                            access.sessionId,
                            access.audience,
                            access.accountId,
                        ),
                        access.companyId,
                        first,
                    )
            )
            .value
    }

    private fun ready(result: AppResult<CollaborationNotificationIssuance>) =
        assertIs<CollaborationNotificationIssuance.Ready>(
                assertIs<AppResult.Success<CollaborationNotificationIssuance>>(result).value
            )
            .publication

    private suspend fun withFixture(block: suspend (CollaborationWorkerFixture, String) -> Unit) {
        val f = CollaborationWorkerFixture()
        try {
            f.initialize()
            f.server.collaboration.listedIds = listOf(f.first)
            f.sdk.backgroundEnabled = true
            f.withoutIncomingSelection()
            val event = f.sdk.incoming(f.first, "Fictional notification original")
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
