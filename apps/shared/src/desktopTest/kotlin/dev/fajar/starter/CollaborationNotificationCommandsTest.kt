package dev.fajar.starter

import dev.fajar.starter.app.notifications.*
import dev.fajar.starter.app.work.CollaborationIncomingRecovery
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.localListAccess
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.config.AppBuild
import dev.fajar.starter.common.result.*
import dev.fajar.starter.fixtures.CollaborationWorkerFixture
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.sync.domain.SyncResult
import dev.fajar.starter.sync.domain.repositories.SyncScheduleRepository
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import java.util.UUID
import kotlin.test.*
import kotlin.time.Clock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/** Real shared graph, crypto and SQLite, with fictional HTTP and an explicitly simulated SDK. */
class CollaborationNotificationCommandsTest {
    @Test
    fun completionReproducesQueuedAndArchivedCancelledCommandsAfterDatabaseReopen() = runBlocking {
        for (cancelled in listOf(false, true)) withFixture { f, target ->
            val action = CollaborationNotificationAction.Reply(id(), target)
            val token = f.seal(action)
            val input =
                assertIs<AppResult.Success<CollaborationNotificationReplyInput>>(
                        f.koin.get<RetainCollaborationNotificationReply>()(
                            token,
                            "Retained before dispatch",
                        )
                    )
                    .value
            assertEquals(
                AppResult.Success(CollaborationNotificationReplyCompletion.Pending),
                f.koin.get<CompleteCollaborationNotificationReply>()(input),
            )
            assertIs<AppResult.Success<*>>(
                f.koin.get<CollaborationNotificationCommands>()(
                    input.candidate.token,
                    input.candidate.text,
                )
            )
            if (cancelled) {
                val record =
                    assertIs<AppResult.Success<CollaborationMessageRecord?>>(
                            f.koin
                                .get<CollaborationMessageRepository>()
                                .get(f.access(), action.operationId)
                        )
                        .value!!
                assertEquals(
                    AppResult.Success(true),
                    f.koin
                        .get<CollaborationMessageCancellationRepository>()
                        .cancel(f.access(), record),
                )
                val pending =
                    assertIs<AppResult.Success<List<CollaborationMessageRecord>>>(
                            f.koin
                                .get<CollaborationMessageRepository>()
                                .observe(f.access(), null)
                                .first()
                        )
                        .value
                assertTrue(pending.isEmpty())
            }
            f.reopen()
            f.withoutIncomingSelection()
            val state =
                if (cancelled) CollaborationMessageState.Cancelled
                else CollaborationMessageState.Queued
            assertEquals(
                AppResult.Success(CollaborationNotificationReplyCompletion.Retired(state)),
                f.koin.get<CompleteCollaborationNotificationReply>()(input),
            )
            f.reopen()
            f.withoutIncomingSelection()
            assertEquals(
                AppResult.Success(CollaborationNotificationReplyCompletion.Absent),
                f.koin.get<CompleteCollaborationNotificationReply>()(input),
            )
            val saved =
                assertIs<AppResult.Success<CollaborationMessageRecord?>>(
                        f.koin
                            .get<CollaborationMessageRepository>()
                            .get(f.access(), action.operationId)
                    )
                    .value!!
            assertEquals(input.candidate.text, saved.intent.text)
            assertEquals(state, saved.state)
            assertTrue(f.sdk.enqueued.isEmpty())
        }
    }

    @Test
    fun changedReplyTextRemainsEncryptedAfterFirstInputCompletesAndDatabaseReopens() = runBlocking {
        withFixture { f, target ->
            val token = f.seal(CollaborationNotificationAction.Reply(id(), target))
            val retain = f.koin.get<RetainCollaborationNotificationReply>()
            val first =
                assertIs<AppResult.Success<CollaborationNotificationReplyInput>>(
                        retain(token, "First reply")
                    )
                    .value
            val changed =
                assertIs<AppResult.Success<CollaborationNotificationReplyInput>>(
                        retain(token, "Changed reply")
                    )
                    .value
            assertNotEquals(first.id, changed.id)
            assertIs<AppResult.Success<*>>(
                f.koin.get<CollaborationNotificationCommands>()(
                    first.candidate.token,
                    first.candidate.text,
                )
            )
            assertIs<AppResult.Success<*>>(
                f.koin.get<CompleteCollaborationNotificationReply>()(first)
            )
            assertEquals(
                FailureKind.Conflict,
                assertIs<AppResult.Failed>(
                        f.koin.get<CompleteCollaborationNotificationReply>()(changed)
                    )
                    .failure
                    .kind,
            )
            f.reopen()
            f.withoutIncomingSelection()
            val access =
                f.access().let {
                    CollaborationRecoveryAccess(it.sessionId, it.audience, it.accountId)
                }
            assertEquals(
                AppResult.Success(changed),
                f.koin.get<CollaborationNotificationReplyRepository>().get(access, changed.id),
            )
            assertEquals(
                AppResult.Success(null),
                f.koin.get<CollaborationNotificationReplyRepository>().get(access, first.id),
            )
            val message =
                assertIs<AppResult.Success<CollaborationMessageRecord?>>(
                        f.koin
                            .get<CollaborationMessageRepository>()
                            .get(f.access(), first.candidate.action.operationId)
                    )
                    .value!!
            assertEquals("First reply", message.intent.text)
        }
    }

    @Test
    fun encryptedReplyUsesOneDurableOperationAcrossReopenAndRejectsDifferentText() = runBlocking {
        withFixture { f, target ->
            val action = CollaborationNotificationAction.Reply(id(), target)
            val token = f.seal(action)
            val commands = f.koin.get<CollaborationNotificationCommands>()
            val result =
                assertIs<AppResult.Success<CollaborationNotificationCommandResult>>(
                        commands(token, "Fictional offline reply")
                    )
                    .value
            assertIs<CollaborationMessageState.Queued>(
                assertIs<CollaborationNotificationCommandResult.ReplyRetained>(result).state
            )
            f.reopen()
            f.withoutIncomingSelection()
            assertIs<AppResult.Success<*>>(
                f.koin.get<CollaborationNotificationCommands>()(token, "Fictional offline reply")
            )
            assertIs<AppResult.Failed>(
                f.koin.get<CollaborationNotificationCommands>()(token, "Another reply")
            )
            val stored =
                assertIs<AppResult.Success<CollaborationMessageRecord?>>(
                        f.koin
                            .get<CollaborationMessageRepository>()
                            .get(f.access(), action.operationId)
                    )
                    .value!!
            assertEquals("Fictional offline reply", stored.intent.text)
            assertEquals(action.operationId, stored.intent.id)
            val all =
                assertIs<AppResult.Success<List<CollaborationMessageRecord>>>(
                        f.koin
                            .get<CollaborationMessageRepository>()
                            .observe(f.access(), null)
                            .first()
                    )
                    .value
            assertEquals(1, all.size)
            assertTrue(f.sdk.enqueued.isEmpty())
            assertEquals(0, f.sdk.deliveries)
        }
    }

    @Test
    fun readCallbackRetainsPrivateCoverageAndDoesNotRequeueAfterLaterAcknowledgment() =
        runBlocking {
            withFixture { f, target ->
                val action = CollaborationNotificationAction.MarkRead(id(), target)
                val token = f.seal(action)
                assertIs<AppResult.Success<*>>(
                    f.koin.get<CollaborationNotificationCommands>()(token)
                )
                assertEquals(action.operationId, f.read(target).pending.single().id)
                assertEquals(
                    CollaborationReadReceiptVisibility.Private,
                    f.read(target).pending.single().visibility,
                )
                assertTrue(CollaborationRecoveryWake.WORK_KEY in f.scheduled)
                assertEquals(
                    SyncResult.Complete,
                    f.koin.get<CollaborationIncomingRecovery>()(f.incomingGrant()),
                )
                val later =
                    CollaborationReadReceiptIntent(
                        id(),
                        target.eventId,
                        CollaborationReadReceiptVisibility.Public,
                    )
                assertIs<AppResult.Success<*>>(
                    f.koin.get<MarkCollaborationMessagesRead>()(
                        CollaborationLocalScope.Captured(f.access()),
                        target.conversationId,
                        target.roomId,
                        listOf(target.eventId),
                        later,
                    )
                )
                assertEquals(
                    SyncResult.Complete,
                    f.koin.get<CollaborationIncomingRecovery>()(f.incomingGrant()),
                )
                assertEquals(later, f.read(target).acknowledged)
                f.reopen()
                f.withoutIncomingSelection()
                assertIs<AppResult.Success<*>>(
                    f.koin.get<CollaborationNotificationCommands>()(token)
                )
                assertTrue(f.read(target).pending.isEmpty())
                assertEquals(later, f.read(target).acknowledged)
                assertEquals(2, f.sdk.readReceipts.size)
            }
        }

    @Test
    fun muteReplaysAfterReopenButCannotOverwriteALaterUnmuteOrPreviewChoice() = runBlocking {
        withFixture { f, target ->
            val scope = CollaborationLocalScope.Captured(f.access())
            val updater = f.koin.get<UpdateCollaborationNotificationSettings>()
            val initial =
                assertIs<AppResult.Success<CollaborationNotificationSettingsState>>(
                        updater(
                            scope,
                            target.conversationId,
                            CollaborationNotificationSettingsChange(
                                id(),
                                null,
                                CollaborationNotificationSettings(
                                    preview = CollaborationNotificationPreview.Message
                                ),
                            ),
                        )
                    )
                    .value
            val action =
                CollaborationNotificationAction.Mute(
                    id(),
                    target,
                    initial.version,
                    initial.settings.preview,
                )
            val token = f.seal(action)
            assertIs<AppResult.Success<*>>(f.koin.get<CollaborationNotificationCommands>()(token))
            val muted = f.settings(target)
            assertEquals(CollaborationNotificationMute.Forever, muted.settings.mute)
            assertEquals(CollaborationNotificationPreview.Message, muted.settings.preview)
            f.reopen()
            f.withoutIncomingSelection()
            assertIs<AppResult.Success<*>>(f.koin.get<CollaborationNotificationCommands>()(token))
            assertEquals(muted, f.settings(target))
            assertIs<AppResult.Success<*>>(
                f.koin.get<UpdateCollaborationNotificationSettings>()(
                    scope,
                    target.conversationId,
                    CollaborationNotificationSettingsChange(
                        id(),
                        muted.version,
                        CollaborationNotificationSettings(),
                    ),
                )
            )
            assertEquals(
                FailureKind.Conflict,
                assertIs<AppResult.Failed>(f.koin.get<CollaborationNotificationCommands>()(token))
                    .failure
                    .kind,
            )
            assertEquals(CollaborationNotificationSettings(), f.settings(target).settings)
        }
    }

    @Test
    fun dismissalOnlyReturnsTheOriginalRenderAndNeverImplicitlyReadsOrReplies() = runBlocking {
        withFixture { f, target ->
            val action = CollaborationNotificationAction.Dismiss(id(), target)
            val token = f.seal(action)
            val result =
                assertIs<AppResult.Success<CollaborationNotificationCommandResult>>(
                        f.koin.get<CollaborationNotificationCommands>()(token)
                    )
                    .value
            assertEquals(
                target,
                assertIs<CollaborationNotificationCommandResult.DismissRequested>(result)
                    .invocation
                    .action
                    .target,
            )
            assertTrue(f.read(target).readEventIds.isEmpty())
            assertTrue(f.read(target).pending.isEmpty())
            assertIs<AppResult.Failed>(
                f.koin.get<CollaborationNotificationCommands>()(token, "Wrong action text")
            )
            assertEquals(CollaborationNotificationSettings(), f.settings(target).settings)
            assertTrue(f.sdk.enqueued.isEmpty())
        }
    }

    @Test
    fun missingBlankOrOversizedReplyInputNeverCreatesAMessage() = runBlocking {
        withFixture { f, target ->
            val action = CollaborationNotificationAction.Reply(id(), target)
            val token = f.seal(action)
            for (body in listOf(null, " ", "x".repeat(32_769))) assertEquals(
                FailureKind.Validation,
                assertIs<AppResult.Failed>(
                        f.koin.get<CollaborationNotificationCommands>()(token, body)
                    )
                    .failure
                    .kind,
            )
            assertNull(
                assertIs<AppResult.Success<CollaborationMessageRecord?>>(
                        f.koin
                            .get<CollaborationMessageRepository>()
                            .get(f.access(), action.operationId)
                    )
                    .value
            )
        }
    }

    @Test
    fun retainedDenialBlocksTheActionAndKeepsTheOriginalEncryptedHistory() = runBlocking {
        withFixture { f, target ->
            val token = f.seal(CollaborationNotificationAction.Reply(id(), target))
            val denied = Failure(FailureKind.AccessDenied, "Restricted", code = "permission_denied")
            assertIs<AppResult.Success<*>>(
                f.koin.get<CollaborationListRepository>().restrict(f.access(), denied)
            )
            val rejected =
                assertIs<AppResult.Failed>(
                        f.koin.get<CollaborationNotificationCommands>()(
                            token,
                            "Retained by native caller",
                        )
                    )
                    .failure
            assertEquals(denied.kind, rejected.kind)
            assertEquals(denied.code, rejected.code)
            assertNotNull(
                assertIs<AppResult.Success<CollaborationHistoryMessage?>>(
                        f.koin
                            .get<CollaborationHistoryRepository>()
                            .getByEvent(
                                f.access(),
                                target.conversationId,
                                target.roomId,
                                target.eventId,
                            )
                    )
                    .value
            )
            assertTrue(f.sdk.enqueued.isEmpty())
        }
    }

    @Test
    fun overlappingCallbackIsRejectedAndCancellationJoinsTheOwnedDecodeBeforeRetry() = runBlocking {
        withFixture { f, target ->
            val token = f.seal(CollaborationNotificationAction.Dismiss(id(), target))
            val actual = f.koin.get<CollaborationNotificationActionRepository>()
            val entered = CompletableDeferred<Unit>()
            var suspendRead = true
            var stopped = false
            val delayed =
                object : CollaborationNotificationActionRepository by actual {
                    override suspend fun open(
                        parent: CollaborationNativeParent,
                        token: CollaborationNotificationActionToken,
                    ): AppResult<CollaborationNotificationAction> {
                        if (suspendRead)
                            try {
                                entered.complete(Unit)
                                awaitCancellation()
                            } finally {
                                stopped = true
                            }
                        return actual.open(parent, token)
                    }
                }
            val commands = f.commands(delayed)
            var published = false
            val job = launch {
                commands(token)
                published = true
            }
            entered.await()
            assertEquals(
                FailureKind.Unavailable,
                assertIs<AppResult.Failed>(commands(token)).failure.kind,
            )
            job.cancelAndJoin()
            assertTrue(stopped)
            assertFalse(published)
            suspendRead = false
            assertIs<AppResult.Success<*>>(commands(token))
        }
    }

    @Test
    fun cancelledSchedulingKeepsTheCommittedReplyAndReopenRetryReusesIt() = runBlocking {
        withFixture { f, target ->
            val action = CollaborationNotificationAction.Reply(id(), target)
            val token = f.seal(action)
            val entered = CompletableDeferred<Unit>()
            var stopped = false
            val scheduler =
                object : SyncScheduleRepository {
                    override suspend fun request(key: String): AppResult<Unit> {
                        assertEquals(QueueCollaborationText.DELIVERY_KEY, key)
                        try {
                            entered.complete(Unit)
                            awaitCancellation()
                        } finally {
                            stopped = true
                        }
                    }
                }
            val queue =
                QueueCollaborationText(
                    f.koin.get(),
                    f.koin.get(),
                    f.koin.get(),
                    f.koin.get(),
                    f.koin.get(),
                    f.koin.get(),
                    Clock.System,
                    scheduler,
                )
            val commands =
                CollaborationNotificationCommands(
                    f.koin.get(),
                    queue,
                    f.koin.get(),
                    f.koin.get(),
                    f.koin.get(),
                )
            var acknowledged = false
            val job = launch {
                commands(token, "Fictional retained reply")
                acknowledged = true
            }
            entered.await()
            job.cancelAndJoin()
            assertTrue(stopped)
            assertFalse(acknowledged)
            val before =
                assertIs<AppResult.Success<CollaborationMessageRecord?>>(
                        f.koin
                            .get<CollaborationMessageRepository>()
                            .get(f.access(), action.operationId)
                    )
                    .value!!
            f.reopen()
            f.withoutIncomingSelection()
            assertIs<AppResult.Success<*>>(
                f.koin.get<CollaborationNotificationCommands>()(token, "Fictional retained reply")
            )
            assertEquals(
                before,
                assertIs<AppResult.Success<CollaborationMessageRecord?>>(
                        f.koin
                            .get<CollaborationMessageRepository>()
                            .get(f.access(), action.operationId)
                    )
                    .value,
            )
            assertTrue(f.sdk.enqueued.isEmpty())
        }
    }

    @Test
    fun deadlineFinishesPendingDecodeAndReleasesTheSingleInvocationOwner() = runBlocking {
        withFixture { f, target ->
            val token = f.seal(CollaborationNotificationAction.Dismiss(id(), target))
            val actual = f.koin.get<CollaborationNotificationActionRepository>()
            var stopped = false
            var pending = true
            val delayed =
                object : CollaborationNotificationActionRepository by actual {
                    override suspend fun open(
                        parent: CollaborationNativeParent,
                        token: CollaborationNotificationActionToken,
                    ): AppResult<CollaborationNotificationAction> {
                        if (pending)
                            try {
                                awaitCancellation()
                            } finally {
                                stopped = true
                            }
                        return actual.open(parent, token)
                    }
                }
            val commands = f.commands(delayed)
            assertEquals(
                FailureKind.Timeout,
                assertIs<AppResult.Failed>(commands(token)).failure.kind,
            )
            assertTrue(stopped)
            pending = false
            assertIs<AppResult.Success<*>>(commands(token))
        }
    }

    private fun CollaborationWorkerFixture.commands(
        actions: CollaborationNotificationActionRepository
    ) =
        CollaborationNotificationCommands(
            OpenCollaborationNotificationAction(
                actions,
                koin.get(),
                koin.get(),
                koin.get<SessionRepository>(),
                koin.get<WorkspaceRepository>(),
                koin.get<IdentityServer>(),
                koin.get<AppBuild>(),
                Clock.System,
            ),
            koin.get(),
            koin.get(),
            koin.get(),
            koin.get(),
        )

    private fun CollaborationWorkerFixture.access() = incomingGrant().owner.localListAccess()

    private suspend fun CollaborationWorkerFixture.seal(action: CollaborationNotificationAction) =
        assertIs<AppResult.Success<CollaborationNotificationActionToken>>(
                koin
                    .get<CollaborationNotificationActionRepository>()
                    .seal(
                        CollaborationNativeParent(
                            session.id,
                            session.binding.audience,
                            session.user.id,
                            session.binding.serverId,
                        ),
                        action,
                    )
            )
            .value

    private suspend fun CollaborationWorkerFixture.read(
        target: CollaborationNotificationActionTarget
    ) =
        assertIs<AppResult.Success<CollaborationReadState>>(
                koin.get<ReadCollaborationReadState>()(
                    CollaborationLocalScope.Captured(access()),
                    target.conversationId,
                    target.roomId,
                    listOf(target.eventId),
                )
            )
            .value

    private suspend fun CollaborationWorkerFixture.settings(
        target: CollaborationNotificationActionTarget
    ) =
        assertIs<AppResult.Success<CollaborationNotificationSettingsState>>(
                koin.get<ReadCollaborationNotificationSettings>()(
                    CollaborationLocalScope.Captured(access()),
                    target.conversationId,
                )
            )
            .value

    private suspend fun withFixture(
        block: suspend (CollaborationWorkerFixture, CollaborationNotificationActionTarget) -> Unit
    ) {
        val f = CollaborationWorkerFixture()
        try {
            f.initialize()
            f.server.collaboration.listedIds = listOf(f.first)
            f.sdk.backgroundEnabled = true
            f.withoutIncomingSelection()
            val original = f.sdk.incoming(f.first, "Fictional notification message")
            assertEquals(
                SyncResult.Complete,
                f.koin.get<CollaborationIncomingRecovery>()(f.incomingGrant()),
            )
            val target =
                CollaborationNotificationActionTarget(
                    f.access().companyId,
                    f.first,
                    f.server.collaboration.roomId(f.first),
                    original.eventId,
                    id(),
                    id(),
                )
            block(f, target)
        } finally {
            f.close()
        }
    }

    private fun id() = UUID.randomUUID().toString()
}
