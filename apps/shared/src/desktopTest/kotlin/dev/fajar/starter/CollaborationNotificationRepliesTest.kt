package dev.fajar.starter

import dev.fajar.starter.app.notifications.*
import dev.fajar.starter.app.work.CollaborationIncomingRecovery
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.localListAccess
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.fixtures.CollaborationWorkerFixture
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.sync.domain.SyncResult
import dev.fajar.starter.sync.domain.repositories.SyncScheduleRepository
import java.util.UUID
import kotlin.test.*
import kotlin.time.Clock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select

/** Real application graph, encrypted SQLite and reopen; fictional HTTP and simulated Matrix SDK. */
class CollaborationNotificationRepliesTest {
    @Test
    fun submitAndDuplicateCallbackRetainOneOperationAcrossReopen() = runBlocking {
        withFixture { f, target ->
            val action = CollaborationNotificationAction.Reply(id(), target)
            val token = f.seal(action)
            assertQueued(
                f.koin.get<CollaborationNotificationReplies>().submit(token, "Offline reply")
            )
            assertTrue(f.inputs().isEmpty())
            val original = f.message(action.operationId)!!
            f.reopen()
            f.withoutIncomingSelection()
            // Resealing the same authenticated descriptor changes ciphertext, not the operation.
            assertQueued(
                f.koin
                    .get<CollaborationNotificationReplies>()
                    .submit(f.seal(action), "Offline reply")
            )
            assertEquals(original, f.message(action.operationId))
            assertEquals(
                1,
                assertIs<AppResult.Success<List<CollaborationMessageRecord>>>(
                        f.koin
                            .get<CollaborationMessageRepository>()
                            .observe(f.access(), null)
                            .first()
                    )
                    .value
                    .size,
            )
            assertTrue(f.inputs().isEmpty())
            assertTrue(f.sdk.enqueued.isEmpty())
            assertEquals(0, f.sdk.deliveries)
        }
    }

    @Test
    fun changedTextBecomesReviewAndNeverOverwritesTheFirstCommand() = runBlocking {
        withFixture { f, target ->
            val action = CollaborationNotificationAction.Reply(id(), target)
            val token = f.seal(action)
            assertQueued(f.koin.get<CollaborationNotificationReplies>().submit(token, "First text"))
            val result =
                assertIs<AppResult.Success<CollaborationNotificationReplyResult>>(
                        f.koin.get<CollaborationNotificationReplies>().submit(token, "Second text")
                    )
                    .value
            val reviewed = assertIs<CollaborationNotificationReplyResult.NeedsReview>(result)
            f.reopen()
            f.withoutIncomingSelection()
            val held = f.inputs().single()
            assertEquals(reviewed.inputId, held.id)
            assertEquals(CollaborationNotificationReplyReview.MessageChanged, held.review)
            assertEquals("Second text", held.candidate.text)
            assertEquals(token, held.candidate.token)
            assertEquals("First text", f.message(action.operationId)!!.intent.text)
            assertEquals(
                AppResult.Success(result),
                f.koin.get<CollaborationNotificationReplies>().resume(held.id),
            )
            assertEquals(held, f.inputs().single())
        }
    }

    @Test
    fun missingOriginalIsReviewedAndStaysHeldWhenTheContextReturns() = runBlocking {
        withFixture { f, target ->
            val event = f.sdk.incoming(f.first, "Later acquired original")
            val action =
                CollaborationNotificationAction.Reply(id(), target.copy(eventId = event.eventId))
            val result =
                assertIs<AppResult.Success<CollaborationNotificationReplyResult>>(
                        f.koin
                            .get<CollaborationNotificationReplies>()
                            .submit(f.seal(action), "Keep for review")
                    )
                    .value
            assertIs<CollaborationNotificationReplyResult.NeedsReview>(result)
            val held = f.inputs().single()
            assertEquals(CollaborationNotificationReplyReview.ContextUnavailable, held.review)
            assertEquals(
                SyncResult.Complete,
                f.koin.get<CollaborationIncomingRecovery>()(f.incomingGrant()),
            )
            f.reopen()
            f.withoutIncomingSelection()
            assertEquals(
                AppResult.Success(result),
                f.koin.get<CollaborationNotificationReplies>().resume(held.id),
            )
            assertEquals(held, f.inputs().single())
            assertNull(f.message(action.operationId))
        }
    }

    @Test
    fun deniedCompanyStillRetainsCallbackInputButNeverReturnsItsContentOrQueues() = runBlocking {
        withFixture { f, target ->
            val action = CollaborationNotificationAction.Reply(id(), target)
            val token = f.seal(action)
            val denied = Failure(FailureKind.AccessDenied, "Restricted", code = "permission_denied")
            assertIs<AppResult.Success<*>>(
                f.koin.get<CollaborationListRepository>().restrict(f.access(), denied)
            )
            val failed =
                assertIs<AppResult.Failed>(
                    f.koin
                        .get<CollaborationNotificationReplies>()
                        .submit(token, "Retained during denial")
                )
            assertEquals(denied.kind, failed.failure.kind)
            assertEquals(denied.code, failed.failure.code)
            val input = f.inputs().single()
            assertNull(input.review)
            assertEquals("Retained during denial", input.candidate.text)
            f.reopen()
            f.withoutIncomingSelection()
            assertEquals(
                denied.kind,
                assertIs<AppResult.Failed>(
                        f.koin.get<CollaborationNotificationReplies>().resume(input.id)
                    )
                    .failure
                    .kind,
            )
            assertEquals(input, f.inputs().single())
            assertNull(f.message(action.operationId))
        }
    }

    @Test
    fun retainedInputResumesAfterDatabaseReopenWithoutWorkspaceSelection() = runBlocking {
        withFixture { f, target ->
            val action = CollaborationNotificationAction.Reply(id(), target)
            val input = f.retain(f.seal(action), "Before process container closed")
            f.reopen()
            f.withoutIncomingSelection()
            assertQueued(f.koin.get<CollaborationNotificationReplies>().resume(input.id))
            assertTrue(f.inputs().isEmpty())
            assertEquals(input.candidate.text, f.message(action.operationId)!!.intent.text)
            assertEquals(
                AppResult.Success(CollaborationNotificationReplyResult.Absent),
                f.koin.get<CollaborationNotificationReplies>().resume(input.id),
            )
        }
    }

    @Test
    fun replacementFamilyReviewsUnsentInputButCompletesAnExistingCommand() = runBlocking {
        for (queued in listOf(false, true)) withFixture { f, target ->
            val action = CollaborationNotificationAction.Reply(id(), target)
            val input = f.retain(f.seal(action), "Previous family input")
            if (queued)
                assertIs<AppResult.Success<*>>(
                    f.koin.get<CollaborationNotificationCommands>()(
                        input.candidate.token,
                        input.candidate.text,
                    )
                )
            val old =
                assertIs<AppResult.Success<dev.fajar.starter.identity.domain.entities.Session?>>(
                        f.koin.get<SessionRepository>().current()
                    )
                    .value!!
            val next = old.copy(id = id())
            assertEquals(
                AppResult.Success(true),
                f.koin.get<SessionRepository>().compareAndSet(old, next),
            )
            f.session = next
            f.reopen()
            f.withoutIncomingSelection()
            val result = f.koin.get<CollaborationNotificationReplies>().resume(input.id)
            if (queued) {
                assertQueued(result)
                assertTrue(f.inputs().isEmpty())
            } else {
                assertIs<CollaborationNotificationReplyResult.NeedsReview>(
                    assertIs<AppResult.Success<CollaborationNotificationReplyResult>>(result).value
                )
                assertEquals(
                    CollaborationNotificationReplyReview.SessionChanged,
                    f.inputs().single().review,
                )
                assertNull(f.message(action.operationId))
            }
        }
    }

    @Test
    fun callbackFirstReceivedAfterFamilyReplacementIsRetainedForReviewWithoutSending() =
        runBlocking {
            for (native in listOf(false, true)) withFixture { f, target ->
                val action = CollaborationNotificationAction.Reply(id(), target)
                val token = f.seal(action)
                assertTrue(token.encoded.startsWith("v2."))
                val old =
                    assertNotNull(
                        assertIs<
                                AppResult.Success<
                                    dev.fajar.starter.identity.domain.entities.Session?
                                >
                            >(
                                f.koin.get<SessionRepository>().current()
                            )
                            .value
                    )
                val next =
                    if (native) old.copy(binding = old.binding.copy(serverId = id()))
                    else old.copy(id = id())
                assertEquals(
                    AppResult.Success(true),
                    f.koin.get<SessionRepository>().compareAndSet(old, next),
                )
                f.session = next
                f.server.collaboration.nativeSessionId = next.binding.serverId
                f.reopen()
                f.withoutIncomingSelection()
                val result =
                    replyValue(
                        f.koin
                            .get<CollaborationNotificationReplies>()
                            .submit(token, "Late notification reply")
                    )
                assertIs<CollaborationNotificationReplyResult.NeedsReview>(result)
                val input = f.inputs().single()
                assertEquals(CollaborationNotificationReplyReview.SessionChanged, input.review)
                assertEquals(old.id, input.candidate.parent.sessionId)
                assertEquals(old.binding.serverId, input.candidate.parent.nativeSessionId)
                assertEquals("Late notification reply", input.candidate.text)
                assertNull(f.message(action.operationId))
                assertIs<AppResult.Failed>(
                    f.koin.get<CollaborationNotificationCommands>()(
                        token,
                        "Late notification reply",
                    )
                )
                f.reopen()
                f.withoutIncomingSelection()
                assertEquals(
                    AppResult.Success(result),
                    f.koin.get<CollaborationNotificationReplies>().resume(input.id),
                )
                assertEquals(input, f.inputs().single())
                assertEquals(
                    AppResult.Success(result),
                    f.koin
                        .get<CollaborationNotificationReplies>()
                        .submit(token, "Late notification reply"),
                )
                assertEquals(1, f.inputs().size)
                assertNull(f.message(action.operationId))
                assertTrue(f.sdk.enqueued.isEmpty())
                assertEquals(0, f.sdk.deliveries)
            }
        }

    @Test
    fun lateCallbackAfterNativeReplacementRecognizesAnExistingCommandWithoutSendingAgain() =
        runBlocking {
            withFixture { f, target ->
                val action = CollaborationNotificationAction.Reply(id(), target)
                val token = f.seal(action)
                assertIs<AppResult.Success<*>>(
                    f.koin.get<CollaborationNotificationCommands>()(
                        token,
                        "Existing original command",
                    )
                )
                val command = assertNotNull(f.message(action.operationId))
                val old =
                    assertNotNull(
                        assertIs<
                                AppResult.Success<
                                    dev.fajar.starter.identity.domain.entities.Session?
                                >
                            >(
                                f.koin.get<SessionRepository>().current()
                            )
                            .value
                    )
                val next = old.copy(id = id(), binding = old.binding.copy(serverId = id()))
                assertEquals(
                    AppResult.Success(true),
                    f.koin.get<SessionRepository>().compareAndSet(old, next),
                )
                f.session = next
                f.server.collaboration.nativeSessionId = next.binding.serverId
                f.reopen()
                f.withoutIncomingSelection()
                assertQueued(
                    f.koin
                        .get<CollaborationNotificationReplies>()
                        .submit(token, "Existing original command")
                )
                assertEquals(command, f.message(action.operationId))
                assertTrue(f.inputs().isEmpty())
                assertTrue(f.sdk.enqueued.isEmpty())
                assertEquals(0, f.sdk.deliveries)
            }
        }

    @Test
    fun cancelledQueueSchedulingPreservesBothInputAndCommandForCompletionOnlyRecovery() =
        runBlocking {
            withFixture { f, target ->
                val action = CollaborationNotificationAction.Reply(id(), target)
                val token = f.seal(action)
                val entered = CompletableDeferred<Unit>()
                var stopped = false
                val queue =
                    QueueCollaborationText(
                        f.koin.get(),
                        f.koin.get(),
                        f.koin.get(),
                        f.koin.get(),
                        f.koin.get(),
                        f.koin.get(),
                        Clock.System,
                        object : SyncScheduleRepository {
                            override suspend fun request(key: String): AppResult<Unit> =
                                try {
                                    assertEquals(QueueCollaborationText.DELIVERY_KEY, key)
                                    entered.complete(Unit)
                                    awaitCancellation()
                                } finally {
                                    stopped = true
                                }
                        },
                    )
                val commands =
                    CollaborationNotificationCommands(
                        f.koin.get(),
                        queue,
                        f.koin.get(),
                        f.koin.get(),
                        f.koin.get(),
                    )
                val flow = f.flow(commands = commands)
                var acknowledged = false
                val job = launch {
                    flow.submit(token, "Uncertain dispatch")
                    acknowledged = true
                }
                entered.await()
                job.cancelAndJoin()
                assertTrue(stopped)
                assertFalse(acknowledged)
                val input = f.inputs().single()
                val command = f.message(action.operationId)!!
                f.reopen()
                f.withoutIncomingSelection()
                assertQueued(f.koin.get<CollaborationNotificationReplies>().resume(input.id))
                assertEquals(command, f.message(action.operationId))
                assertTrue(f.inputs().isEmpty())
                assertTrue(f.sdk.enqueued.isEmpty())
            }
        }

    @Test
    fun callbackWaitsForOwnedRetentionAndCancellationDoesNotDropTheNextInput() = runBlocking {
        withFixture { f, target ->
            val actual = f.koin.get<CollaborationNotificationActionRepository>()
            val entered = CompletableDeferred<Unit>()
            var first = true
            var stopped = false
            val actions =
                object : CollaborationNotificationActionRepository by actual {
                    override suspend fun recover(
                        parent: CollaborationNativeParent,
                        token: CollaborationNotificationActionToken,
                    ): AppResult<CollaborationAuthenticatedNotificationAction> {
                        if (first) {
                            first = false
                            try {
                                entered.complete(Unit)
                                awaitCancellation()
                            } finally {
                                stopped = true
                            }
                        }
                        return actual.recover(parent, token)
                    }
                }
            val flow = f.flow(actions = actions)
            val token = f.seal(CollaborationNotificationAction.Reply(id(), target))
            val firstJob = launch { flow.submit(token, "Cancelled before retention") }
            select<Unit> {
                entered.onAwait {}
                firstJob.onJoin { fail("Callback returned before its retention barrier") }
            }
            val next =
                async(start = CoroutineStart.UNDISPATCHED) { flow.submit(token, "Next callback") }
            assertFalse(next.isCompleted)
            assertEquals(
                FailureKind.Unavailable,
                assertIs<AppResult.Failed>(flow.resume("n-" + "0".repeat(64))).failure.kind,
            )
            firstJob.cancelAndJoin()
            assertTrue(stopped)
            assertQueued(next.await())
            assertTrue(f.inputs().isEmpty())
        }
    }

    @Test
    fun callbackAdmissionIsBoundedAndCancelledWaitersReleaseTheirSlots() = runBlocking {
        withFixture { f, target ->
            val actual = f.koin.get<CollaborationNotificationActionRepository>()
            val entered = CompletableDeferred<Unit>()
            var pending = true
            val actions =
                object : CollaborationNotificationActionRepository by actual {
                    override suspend fun recover(
                        parent: CollaborationNativeParent,
                        token: CollaborationNotificationActionToken,
                    ): AppResult<CollaborationAuthenticatedNotificationAction> {
                        if (pending) {
                            entered.complete(Unit)
                            awaitCancellation()
                        }
                        return actual.recover(parent, token)
                    }
                }
            val flow = f.flow(actions = actions)
            val token = f.seal(CollaborationNotificationAction.Reply(id(), target))
            val jobs = mutableListOf<Job>()
            jobs += launch { flow.submit(token, "Blocked callback") }
            select<Unit> {
                entered.onAwait {}
                jobs.first().onJoin { fail("Callback returned before its retention barrier") }
            }
            repeat(7) {
                jobs +=
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        flow.submit(token, "Waiting callback $it")
                    }
            }
            assertEquals(
                FailureKind.Unavailable,
                assertIs<AppResult.Failed>(flow.submit(token, "Beyond bound")).failure.kind,
            )
            jobs.forEach { it.cancel() }
            jobs.joinAll()
            pending = false
            assertQueued(flow.submit(token, "After cancellation"))
            assertTrue(f.inputs().isEmpty())
        }
    }

    @Test
    fun wholeCallbackDeadlineJoinsPendingRetentionAndAllowsTheNextAttempt() = runBlocking {
        withFixture { f, target ->
            val actual = f.koin.get<CollaborationNotificationActionRepository>()
            var pending = true
            var stopped = false
            val actions =
                object : CollaborationNotificationActionRepository by actual {
                    override suspend fun recover(
                        parent: CollaborationNativeParent,
                        token: CollaborationNotificationActionToken,
                    ): AppResult<CollaborationAuthenticatedNotificationAction> {
                        if (pending)
                            try {
                                awaitCancellation()
                            } finally {
                                stopped = true
                            }
                        return actual.recover(parent, token)
                    }
                }
            val flow = f.flow(actions = actions)
            val token = f.seal(CollaborationNotificationAction.Reply(id(), target))
            assertEquals(
                FailureKind.Timeout,
                assertIs<AppResult.Failed>(flow.submit(token, "Timed out before storage"))
                    .failure
                    .kind,
            )
            assertTrue(stopped)
            assertTrue(f.inputs().isEmpty())
            pending = false
            assertQueued(flow.submit(token, "Retry after timeout"))
        }
    }

    @Test
    fun nonReplyTokenOrInvalidTextNeverCreatesInputOrCommand() = runBlocking {
        withFixture { f, target ->
            val action = CollaborationNotificationAction.Dismiss(id(), target)
            val flow = f.koin.get<CollaborationNotificationReplies>()
            assertIs<AppResult.Failed>(flow.submit(f.seal(action), "Wrong action"))
            val reply = CollaborationNotificationAction.Reply(id(), target)
            assertIs<AppResult.Failed>(flow.submit(f.seal(reply), " "))
            assertTrue(f.inputs().isEmpty())
            assertNull(f.message(action.operationId))
            assertNull(f.message(reply.operationId))
        }
    }

    @Test
    fun transientDispatchFailureKeepsUnreviewedInputForExactRecovery() = runBlocking {
        withFixture { f, target ->
            val action = CollaborationNotificationAction.Reply(id(), target)
            val token = f.seal(action)
            val actual = f.koin.get<CollaborationNotificationActionRepository>()
            val unavailable = Failure(FailureKind.Storage, "Temporary storage failure")
            val actions =
                object : CollaborationNotificationActionRepository by actual {
                    override suspend fun open(
                        parent: CollaborationNativeParent,
                        token: CollaborationNotificationActionToken,
                    ): AppResult<CollaborationNotificationAction> = AppResult.Failed(unavailable)
                }
            val commands =
                CollaborationNotificationCommands(
                    OpenCollaborationNotificationAction(
                        actions,
                        f.koin.get(),
                        f.koin.get(),
                        f.koin.get(),
                        f.koin.get(),
                        f.koin.get(),
                        f.koin.get(),
                        Clock.System,
                    ),
                    f.koin.get(),
                    f.koin.get(),
                    f.koin.get(),
                    f.koin.get(),
                )
            assertEquals(
                AppResult.Failed(unavailable),
                f.flow(commands = commands).submit(token, "Waiting for retry"),
            )
            val input = f.inputs().single()
            assertNull(input.review)
            assertNull(f.message(action.operationId))
            f.reopen()
            f.withoutIncomingSelection()
            assertQueued(f.koin.get<CollaborationNotificationReplies>().resume(input.id))
            assertEquals(input.candidate.text, f.message(action.operationId)!!.intent.text)
        }
    }

    @Test
    fun failedRetirementDoesNotQueueAgainAndReopenCanFinishExactRemoval() = runBlocking {
        withFixture { f, target ->
            val action = CollaborationNotificationAction.Reply(id(), target)
            val token = f.seal(action)
            val actual = f.koin.get<CollaborationNotificationReplyRepository>()
            val unavailable = Failure(FailureKind.Storage, "Temporary removal failure")
            val inputs =
                object : CollaborationNotificationReplyRepository by actual {
                    override suspend fun remove(
                        access: CollaborationRecoveryAccess,
                        expected: CollaborationNotificationReplyInput,
                    ): AppResult<Boolean> = AppResult.Failed(unavailable)
                }
            val completion =
                CompleteCollaborationNotificationReply(
                    inputs,
                    f.koin.get(),
                    f.koin.get(),
                    f.koin.get(),
                    f.koin.get(),
                    f.koin.get(),
                    f.koin.get(),
                    Clock.System,
                )
            val flow = f.flow(completion = completion)
            assertEquals(AppResult.Failed(unavailable), flow.submit(token, "Retire after retry"))
            val input = f.inputs().single()
            val command = f.message(action.operationId)!!
            val beforeSchedules = f.scheduled.size
            assertEquals(AppResult.Failed(unavailable), flow.resume(input.id))
            assertEquals(beforeSchedules, f.scheduled.size)
            assertNull(f.inputs().single().review)
            f.reopen()
            f.withoutIncomingSelection()
            assertQueued(f.koin.get<CollaborationNotificationReplies>().resume(input.id))
            assertEquals(command, f.message(action.operationId))
            assertTrue(f.inputs().isEmpty())
        }
    }

    @Test
    fun changedInputVersionBeforeDispatchPreservesHoldAndNeverQueues() = runBlocking {
        withFixture { f, target ->
            val action = CollaborationNotificationAction.Reply(id(), target)
            val token = f.seal(action)
            val actual = f.koin.get<CollaborationNotificationReplyRepository>()
            val inputs =
                object : CollaborationNotificationReplyRepository by actual {
                    override suspend fun get(
                        access: CollaborationRecoveryAccess,
                        id: String,
                    ): AppResult<CollaborationNotificationReplyInput?> {
                        val input =
                            assertIs<AppResult.Success<CollaborationNotificationReplyInput?>>(
                                    actual.get(access, id)
                                )
                                .value!!
                        assertEquals(
                            AppResult.Success(true),
                            actual.hold(
                                access,
                                input,
                                CollaborationNotificationReplyReview.ContextUnavailable,
                            ),
                        )
                        return actual.get(access, id)
                    }
                }
            val read =
                ReadCollaborationNotificationReply(
                    inputs,
                    f.koin.get(),
                    f.koin.get(),
                    f.koin.get(),
                    f.koin.get(),
                    f.koin.get(),
                    Clock.System,
                )
            assertEquals(
                FailureKind.Conflict,
                assertIs<AppResult.Failed>(f.flow(read = read).submit(token, "Concurrent hold"))
                    .failure
                    .kind,
            )
            val held = f.inputs().single()
            assertEquals(CollaborationNotificationReplyReview.ContextUnavailable, held.review)
            assertEquals("Concurrent hold", held.candidate.text)
            assertNull(f.message(action.operationId))
        }
    }

    @Test
    fun transientFirstReplySurvivesHealthyLaterPageAndReopenUntilFullRetryCycle() = runBlocking {
        withFixture { f, target ->
            val inputs =
                (1..6).map { index ->
                    f.retain(
                        f.seal(CollaborationNotificationAction.Reply(id(), target)),
                        "Cycle reply $index",
                    )
                }
            val actual = f.koin.get<CollaborationNotificationActionRepository>()
            val actions =
                object : CollaborationNotificationActionRepository by actual {
                    override suspend fun open(
                        parent: CollaborationNativeParent,
                        token: CollaborationNotificationActionToken,
                    ): AppResult<CollaborationNotificationAction> =
                        if (token == inputs.first().candidate.token)
                            AppResult.Failed(
                                Failure(FailureKind.Storage, "Temporary decoding failure")
                            )
                        else actual.open(parent, token)
                }
            val commands =
                CollaborationNotificationCommands(
                    OpenCollaborationNotificationAction(
                        actions,
                        f.koin.get(),
                        f.koin.get(),
                        f.koin.get(),
                        f.koin.get(),
                        f.koin.get(),
                        f.koin.get(),
                        Clock.System,
                    ),
                    f.koin.get(),
                    f.koin.get(),
                    f.koin.get(),
                    f.koin.get(),
                )
            val failingFirst = f.flow(commands = commands)
            var state =
                assertIs<AppResult.Success<CollaborationNotificationReplyRecoverySnapshot>>(
                        f.koin.get<BeginCollaborationNotificationReplyRecoveryCycle>()()
                    )
                    .value
            repeat(2) { page ->
                val work =
                    assertIs<AppResult.Success<CollaborationNotificationReplyRecoveryWork?>>(
                            f.koin.get<ReadCollaborationNotificationReplyRecovery>()()
                        )
                        .value!!
                for (entry in work.entries) {
                    state =
                        state.copy(
                            cursor =
                                assertIs<
                                        AppResult.Success<
                                            CollaborationNotificationReplyRecoveryCursor
                                        >
                                    >(
                                        f.koin.get<AdvanceCollaborationNotificationReplyRecovery>()(
                                            work,
                                            entry,
                                            state.cursor,
                                        )
                                    )
                                    .value
                        )
                    val result =
                        if (page == 0 && entry.id == inputs.first().id)
                            failingFirst.resume(entry.id)
                        else f.koin.get<CollaborationNotificationReplies>().resume(entry.id)
                    val retry = result is AppResult.Failed
                    if (retry) assertEquals(FailureKind.Storage, result.failure.kind)
                    else assertQueued(result)
                    state =
                        assertIs<AppResult.Success<CollaborationNotificationReplyRecoverySnapshot>>(
                                f.koin.get<SettleCollaborationNotificationReplyRecoveryCycle>()(
                                    state,
                                    retry,
                                )
                            )
                            .value
                }
                assertTrue(state.cursor.cycle!!.retryRequired)
                if (page == 0) {
                    f.reopen()
                    f.withoutIncomingSelection()
                    state =
                        assertIs<AppResult.Success<CollaborationNotificationReplyRecoverySnapshot>>(
                                f.koin.get<BeginCollaborationNotificationReplyRecoveryCycle>()()
                            )
                            .value
                    assertTrue(state.cursor.cycle!!.retryRequired)
                }
            }
            val finished =
                assertIs<AppResult.Success<CollaborationNotificationReplyRecoverySnapshot>>(
                        f.koin.get<FinishCollaborationNotificationReplyRecoveryCycle>()(state)
                    )
                    .value
            assertTrue(finished.cursor.cycle!!.retryRequired)
            assertEquals(inputs.first(), f.inputs().single())
            assertNull(f.message(inputs.first().candidate.action.operationId))
            val appended =
                f.retain(
                    f.seal(CollaborationNotificationAction.Reply(id(), target)),
                    "Newer tail input",
                )
            state =
                assertIs<AppResult.Success<CollaborationNotificationReplyRecoverySnapshot>>(
                        f.koin.get<BeginCollaborationNotificationReplyRecoveryCycle>()()
                    )
                    .value
            val work =
                assertIs<AppResult.Success<CollaborationNotificationReplyRecoveryWork?>>(
                        f.koin.get<ReadCollaborationNotificationReplyRecovery>()()
                    )
                    .value!!
            assertEquals(listOf(inputs.first().id, appended.id), work.entries.map { it.id })
            for (entry in work.entries) {
                state =
                    state.copy(
                        cursor =
                            assertIs<
                                    AppResult.Success<CollaborationNotificationReplyRecoveryCursor>
                                >(
                                    f.koin.get<AdvanceCollaborationNotificationReplyRecovery>()(
                                        work,
                                        entry,
                                        state.cursor,
                                    )
                                )
                                .value
                    )
                assertQueued(f.koin.get<CollaborationNotificationReplies>().resume(entry.id))
                state =
                    assertIs<AppResult.Success<CollaborationNotificationReplyRecoverySnapshot>>(
                            f.koin.get<SettleCollaborationNotificationReplyRecoveryCycle>()(
                                state,
                                false,
                            )
                        )
                        .value
            }
            val healthy =
                assertIs<AppResult.Success<CollaborationNotificationReplyRecoverySnapshot>>(
                        f.koin.get<FinishCollaborationNotificationReplyRecoveryCycle>()(state)
                    )
                    .value
            assertFalse(healthy.cursor.cycle!!.retryRequired)
            assertTrue(f.inputs().isEmpty())
            assertEquals(
                "Cycle reply 1",
                f.message(inputs.first().candidate.action.operationId)!!.intent.text,
            )
            assertTrue(f.sdk.enqueued.isEmpty())
        }
    }

    @Test
    fun interruptedAttemptAndDiscardedFinishResultReopenBeforeNewTailWithoutLosingOldInput() =
        runBlocking {
            for (queued in listOf(false, true)) withFixture { f, target ->
                val input =
                    f.retain(
                        f.seal(CollaborationNotificationAction.Reply(id(), target)),
                        "Interrupted cycle input",
                    )
                var state =
                    assertIs<AppResult.Success<CollaborationNotificationReplyRecoverySnapshot>>(
                            f.koin.get<BeginCollaborationNotificationReplyRecoveryCycle>()()
                        )
                        .value
                val firstWork =
                    assertIs<AppResult.Success<CollaborationNotificationReplyRecoveryWork?>>(
                            f.koin.get<ReadCollaborationNotificationReplyRecovery>()()
                        )
                        .value!!
                state =
                    state.copy(
                        cursor =
                            assertIs<
                                    AppResult.Success<CollaborationNotificationReplyRecoveryCursor>
                                >(
                                    f.koin.get<AdvanceCollaborationNotificationReplyRecovery>()(
                                        firstWork,
                                        firstWork.entries.single(),
                                        state.cursor,
                                    )
                                )
                                .value
                    )
                if (queued)
                    assertQueued(f.koin.get<CollaborationNotificationReplies>().resume(input.id))
                val existing = f.message(input.candidate.action.operationId)
                // No outcome settlement: reopen with the last attempted sequence still in flight.
                f.reopen()
                f.withoutIncomingSelection()
                state =
                    assertIs<AppResult.Success<CollaborationNotificationReplyRecoverySnapshot>>(
                            f.koin.get<BeginCollaborationNotificationReplyRecoveryCycle>()()
                        )
                        .value
                assertTrue(state.cursor.cycle!!.inFlight)
                assertNull(
                    assertIs<AppResult.Success<CollaborationNotificationReplyRecoveryWork?>>(
                            f.koin.get<ReadCollaborationNotificationReplyRecovery>()()
                        )
                        .value
                )
                assertTrue(
                    assertIs<AppResult.Success<CollaborationNotificationReplyRecoverySnapshot>>(
                            f.koin.get<FinishCollaborationNotificationReplyRecoveryCycle>()(state)
                        )
                        .value
                        .cursor
                        .cycle!!
                        .retryRequired
                )
                // Discard the finished result, as if the caller stopped before returning to its
                // worker.
                f.reopen()
                f.withoutIncomingSelection()
                val tail =
                    f.retain(
                        f.seal(CollaborationNotificationAction.Reply(id(), target)),
                        "Appended after interruption",
                    )
                state =
                    assertIs<AppResult.Success<CollaborationNotificationReplyRecoverySnapshot>>(
                            f.koin.get<BeginCollaborationNotificationReplyRecoveryCycle>()()
                        )
                        .value
                val work =
                    assertIs<AppResult.Success<CollaborationNotificationReplyRecoveryWork?>>(
                            f.koin.get<ReadCollaborationNotificationReplyRecovery>()()
                        )
                        .value!!
                assertEquals(
                    if (queued) listOf(tail.id) else listOf(input.id, tail.id),
                    work.entries.map { it.id },
                )
                for (entry in work.entries) {
                    state =
                        state.copy(
                            cursor =
                                assertIs<
                                        AppResult.Success<
                                            CollaborationNotificationReplyRecoveryCursor
                                        >
                                    >(
                                        f.koin.get<AdvanceCollaborationNotificationReplyRecovery>()(
                                            work,
                                            entry,
                                            state.cursor,
                                        )
                                    )
                                    .value
                        )
                    assertQueued(f.koin.get<CollaborationNotificationReplies>().resume(entry.id))
                    state =
                        assertIs<AppResult.Success<CollaborationNotificationReplyRecoverySnapshot>>(
                                f.koin.get<SettleCollaborationNotificationReplyRecoveryCycle>()(
                                    state,
                                    false,
                                )
                            )
                            .value
                }
                assertFalse(
                    assertIs<AppResult.Success<CollaborationNotificationReplyRecoverySnapshot>>(
                            f.koin.get<FinishCollaborationNotificationReplyRecoveryCycle>()(state)
                        )
                        .value
                        .cursor
                        .cycle!!
                        .retryRequired
                )
                assertTrue(f.inputs().isEmpty())
                if (queued) assertEquals(existing, f.message(input.candidate.action.operationId))
                assertEquals(
                    input.candidate.text,
                    f.message(input.candidate.action.operationId)!!.intent.text,
                )
            }
        }

    @Test
    fun accountWithoutRetainedRepliesNeedsNoHistoryKeyOrRecoveryCursor() = runBlocking {
        val f = CollaborationWorkerFixture()
        try {
            f.initialize()
            f.withoutIncomingSelection()
            assertEquals(
                AppResult.Success(null),
                f.koin.get<BeginCollaborationNotificationReplyRecoveryCycle>()(),
            )
            val access = f.access()
            assertEquals(
                AppResult.Success(null),
                f.koin
                    .get<CollaborationNotificationReplyRecoveryCursorRepository>()
                    .current(
                        CollaborationRecoveryAccess(
                            access.sessionId,
                            access.audience,
                            access.accountId,
                        )
                    ),
            )
            assertTrue(f.sdk.enqueued.isEmpty())
        } finally {
            f.close()
        }
    }

    private fun CollaborationWorkerFixture.flow(
        actions: CollaborationNotificationActionRepository = koin.get(),
        commands: CollaborationNotificationCommands = koin.get(),
        read: ReadCollaborationNotificationReply = koin.get(),
        completion: CompleteCollaborationNotificationReply = koin.get(),
    ) =
        CollaborationNotificationReplies(
            RetainCollaborationNotificationReply(
                actions,
                koin.get(),
                koin.get(),
                koin.get(),
                koin.get(),
            ),
            read,
            completion,
            koin.get(),
            commands,
            koin.get(),
        )

    private fun replyValue(result: AppResult<CollaborationNotificationReplyResult>) =
        assertIs<AppResult.Success<CollaborationNotificationReplyResult>>(
                result,
                (result as? AppResult.Failed)?.failure?.let { "${it.kind}: ${it.code}" },
            )
            .value

    private fun assertQueued(result: AppResult<CollaborationNotificationReplyResult>) {
        assertEquals(
            CollaborationMessageState.Queued,
            assertIs<CollaborationNotificationReplyResult.CommandRetained>(replyValue(result)).state,
        )
    }

    private fun CollaborationWorkerFixture.access() = incomingGrant().owner.localListAccess()

    private suspend fun CollaborationWorkerFixture.inputs():
        List<CollaborationNotificationReplyInput> {
        val a = access()
        return assertIs<AppResult.Success<CollaborationNotificationReplyPage>>(
                koin
                    .get<CollaborationNotificationReplyRepository>()
                    .page(
                        CollaborationRecoveryAccess(a.sessionId, a.audience, a.accountId),
                        pendingOnly = false,
                    )
            )
            .value
            .inputs
    }

    private suspend fun CollaborationWorkerFixture.message(id: String) =
        assertIs<AppResult.Success<CollaborationMessageRecord?>>(
                koin.get<CollaborationMessageRepository>().get(access(), id)
            )
            .value

    private suspend fun CollaborationWorkerFixture.retain(
        token: CollaborationNotificationActionToken,
        text: String,
    ) =
        assertIs<AppResult.Success<CollaborationNotificationReplyInput>>(
                koin.get<RetainCollaborationNotificationReply>()(token, text)
            )
            .value

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

    private suspend fun withFixture(
        block: suspend (CollaborationWorkerFixture, CollaborationNotificationActionTarget) -> Unit
    ) {
        val f = CollaborationWorkerFixture()
        try {
            f.initialize()
            f.server.collaboration.listedIds = listOf(f.first)
            f.sdk.backgroundEnabled = true
            f.withoutIncomingSelection()
            val original = f.sdk.incoming(f.first, "Fictional notification original")
            assertEquals(
                SyncResult.Complete,
                f.koin.get<CollaborationIncomingRecovery>()(f.incomingGrant()),
            )
            block(
                f,
                CollaborationNotificationActionTarget(
                    f.access().companyId,
                    f.first,
                    f.server.collaboration.roomId(f.first),
                    original.eventId,
                    id(),
                    id(),
                ),
            )
        } finally {
            f.close()
        }
    }

    private fun id() = UUID.randomUUID().toString()
}
