package dev.fajar.starter

import dev.fajar.starter.app.notifications.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationListRepository
import dev.fajar.starter.collaboration.domain.repositories.CollaborationNotificationInputRepository
import dev.fajar.starter.collaboration.domain.usecases.MarkCollaborationMessagesRead
import dev.fajar.starter.common.result.*
import dev.fajar.starter.notifications.data.dto.*
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import org.koin.core.parameter.parametersOf

/** Actual app DI and encrypted SQLite; acquisition and the native OS remain explicit fakes. */
class CollaborationNotificationProcessIntegrationTest {
    @Test
    fun boundReplyCallbackCapturesThenAcknowledgesThroughTheActualProcessGraph() = runBlocking {
        withProcess(seedPublication = false) { f, process, shown, _ ->
            val delivery = f.app.koin.get<CollaborationNotificationDelivery>()
            assertTrue(delivery.start(process))
            assertIs<AppResult.Success<*>>(delivery.publish(f.parent, f.candidate))
            val card = next(shown) { true }
            idle(f)
            val callbacks = f.app.koin.get<CollaborationNotificationReplyCallbacks>()
            val outcome = CompletableDeferred<CollaborationReplyCaptureOutcome>()
            callbacks.receive(
                CollaborationNotificationActionToken(
                    card.envelopes.getValue(ActionNotificationKind.Reply)
                ),
                "Fictional retained reply",
            ) {
                outcome.complete(it)
            }
            assertEquals(
                CollaborationReplyCaptureOutcome.Saved,
                withTimeout(12_000) { outcome.await() },
            )
            val inputs = f.app.koin.get<CollaborationNotificationInputRepository>()
            val reference =
                assertIs<AppResult.Success<List<CollaborationNotificationInputReference>>>(
                        inputs.page()
                    )
                    .value
                    .single()
            assertNotNull(
                assertIs<AppResult.Success<CollaborationNotificationInput?>>(inputs.get(reference))
                    .value
            )
            assertEquals(
                CollaborationNotificationPublicationState.Dismissed,
                f.publication()!!.body.state,
            )
            assertTrue(f.native.cards.isEmpty())
            assertTrue(f.app.sdk.enqueued.isEmpty())
            callbacks.closeAndJoin()
            delivery.closeAndJoin()
        }
    }

    @Test
    fun boundActionCallbackWaitsForAnExistingLeaseAndRetiresOnceWithoutSending() = runBlocking {
        withProcess(seedPublication = false) { f, process, shown, _ ->
            val delivery = f.app.koin.get<CollaborationNotificationDelivery>()
            assertTrue(delivery.start(process))
            assertIs<AppResult.Success<*>>(delivery.publish(f.parent, f.candidate))
            val card = next(shown) { true }
            idle(f)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val holding = async {
                f.owner.withOwner(f.parent) {
                    entered.complete(Unit)
                    release.await()
                    AppResult.Success(Unit)
                }
            }
            withTimeout(8_000) { entered.await() }
            val callbacks = f.app.koin.get<CollaborationNotificationActionCallbacks>()
            val outcome = CompletableDeferred<CollaborationActionCallbackOutcome>()
            callbacks.receive(
                ActionNotificationInput(
                    card.reference,
                    ActionNotificationKind.Dismiss,
                    card.envelopes.getValue(ActionNotificationKind.Dismiss),
                )
            ) {
                outcome.complete(it)
            }
            // A callback cannot acknowledge while another writer owns the native surface.
            assertFalse(outcome.isCompleted)
            release.complete(Unit)
            assertIs<AppResult.Success<*>>(holding.await())
            assertEquals(
                CollaborationActionCallbackOutcome.Applied,
                withTimeout(12_000) { outcome.await() },
            )
            assertEquals(
                CollaborationNotificationPublicationState.Dismissed,
                f.publication()!!.body.state,
            )
            assertTrue(f.native.cards.isEmpty())
            assertTrue(f.app.sdk.enqueued.isEmpty())
            callbacks.closeAndJoin()
            delivery.closeAndJoin()
        }
    }

    @Test
    fun unboundAndClosedDeliveryCannotExecuteAuthenticatedNativeActions() = runBlocking {
        withProcess { f, process, _, _ ->
            val card = f.native.cards.values.single()
            val input =
                ActionNotificationInput(
                    card.reference,
                    ActionNotificationKind.Open,
                    card.envelopes.getValue(ActionNotificationKind.Open),
                )
            val delivery = f.app.koin.get<CollaborationNotificationDelivery>()
            assertIs<AppResult.Failed>(delivery.act(input))
            assertTrue(delivery.start(process))
            assertIs<AppResult.Success<*>>(delivery.act(input))
            delivery.closeAndJoin()
            assertIs<AppResult.Failed>(delivery.act(input))
            assertIs<AppResult.Failed>(
                delivery.acknowledgeReply(CollaborationNotificationActionToken(input.envelope))
            )
        }
    }

    @Test
    fun focusedRouteChangeRetiresAnExistingCardWithoutAnExplicitPublishAndBlurStaysQuiet() =
        runBlocking {
            withProcess(seedPublication = false) { f, process, shown, removed ->
                process.start()
                assertEquals(
                    AppResult.Success(CollaborationNotificationPublicationResult.Submitted),
                    process.publish(f.parent, f.candidate, true),
                )
                val original = next(shown) { true }
                idle(f)
                val registration =
                    assertNotNull(
                        f.app.koin
                            .get<CollaborationNotificationFocus>()
                            .register(f.app.incomingGrant().owner, f.candidate.conversationId)
                    )
                assertEquals(original.reference, withTimeout(15_000) { removed.receive() })
                idle(f)
                assertEquals(
                    CollaborationNotificationPublicationState.Cancelled,
                    f.publication()!!.body.state,
                )
                assertTrue(f.native.cards.isEmpty())
                registration.close()
                assertEquals(
                    AppResult.Success(CollaborationNotificationPublicationResult.Unchanged),
                    process.publish(f.parent, f.candidate, true),
                )
                assertEquals(1, f.native.attempts)
            }
        }

    @Test
    fun uncertainNativeResultRetainsPreparationAndTheNextCandidateRequestReplaysSilently() =
        runBlocking {
            withProcess(seedPublication = false) { f, process, shown, _ ->
                f.native.onShow = {
                    shown.send(it)
                    throw java.io.IOException("Fictional uncertain native acknowledgment")
                }
                process.start()
                assertIs<AppResult.Failed>(
                    process.publish(f.parent, f.candidate, allowAlert = true)
                )
                val uncertain = next(shown) { true }
                idle(f)
                assertEquals(
                    CollaborationNotificationPublicationState.Prepared,
                    f.publication()!!.body.state,
                )
                assertTrue(f.native.cards.isEmpty())
                assertEquals(1, f.native.attempts)
                f.native.onShow = { shown.send(it) }
                assertEquals(
                    AppResult.Success(CollaborationNotificationPublicationResult.Submitted),
                    process.publish(f.parent, f.candidate, allowAlert = true),
                )
                val replay = next(shown) { true }
                assertEquals(uncertain.reference, replay.reference)
                assertTrue(replay.silent)
                assertEquals(
                    CollaborationNotificationPublicationState.Published,
                    f.publication()!!.body.state,
                )
                assertTrue(f.app.sdk.enqueued.isEmpty())
            }
        }

    @Test
    fun newCandidateWaitsForOwnedSourcesAndOnlyItsFirstOriginalMayAlert() = runBlocking {
        withProcess(seedPublication = false) { f, process, shown, _ ->
            process.start()
            assertEquals(
                AppResult.Success(CollaborationNotificationPublicationResult.Submitted),
                process.publish(f.parent, f.candidate, allowAlert = true),
            )
            val original = next(shown) { it.body == "Fictional original for notification" }
            idle(f)
            assertFalse(original.silent)
            assertEquals(
                CollaborationNotificationPublicationState.Published,
                f.publication()!!.body.state,
            )
            assertEquals(
                AppResult.Success(CollaborationNotificationPublicationResult.Submitted),
                process.publish(f.parent, f.candidate, allowAlert = true),
            )
            val replay = next(shown) { it.reference == original.reference }
            assertTrue(replay.silent)
            assertTrue(f.app.sdk.enqueued.isEmpty())
        }
    }

    @Test
    fun initiallyMutedCandidateRetainsAnInactiveWatermarkWithoutNativeContent() = runBlocking {
        withProcess(seedPublication = false) { f, process, _, _ ->
            f.preferences(
                CollaborationNotificationPreview.Message,
                CollaborationNotificationMute.Forever,
            )
            process.start()
            assertEquals(
                AppResult.Success(CollaborationNotificationPublicationResult.SuppressionRecorded),
                process.publish(f.parent, f.candidate, allowAlert = true),
            )
            val muted = assertNotNull(f.publication())
            assertEquals(CollaborationNotificationPublicationState.Cancelled, muted.body.state)
            assertNull(muted.body.message)
            assertNull(muted.body.actions)
            assertTrue(f.native.cards.isEmpty())
            assertTrue(f.app.sdk.enqueued.isEmpty())
        }
    }

    @Test
    fun processObservesPrivacyChangesOutsideTheSelectedCompanyAndRerendersSilently() = runBlocking {
        withProcess { f, process, shown, _ ->
            process.start()
            val original = next(shown) { it.body == "Fictional original for notification" }
            idle(f)
            assertTrue(original.silent)
            f.preferences(CollaborationNotificationPreview.Hidden)
            val hidden = next(shown) { it.body == "New message" }
            idle(f)
            assertTrue(hidden.silent)
            assertEquals(original.reference.id, hidden.reference.id)
            assertNotEquals(original.reference.renderId, hidden.reference.renderId)
            val record = assertNotNull(f.publication())
            assertEquals(CollaborationNotificationPublicationState.Published, record.body.state)
            assertNull(assertNotNull(record.body.message).body)
            assertEquals(hidden.reference.renderId, record.body.target.renderId)
            assertTrue(f.app.sdk.enqueued.isEmpty())
        }
    }

    @Test
    fun muteAndPrivateReadRetireThroughTheProcessAndStayRetiredAfterReopen() = runBlocking {
        for (mute in listOf(false, true)) withProcess { f, process, shown, removed ->
            process.start()
            val card = next(shown) { it.body == "Fictional original for notification" }
            idle(f)
            while (removed.tryReceive().isSuccess) Unit
            if (mute) {
                f.preferences(
                    CollaborationNotificationPreview.Message,
                    CollaborationNotificationMute.Forever,
                )
            } else {
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
            }
            withTimeout(15_000) { removed.receiveAsFlow().first { it == card.reference } }
            idle(f)
            val retired = assertNotNull(f.publication())
            assertEquals(CollaborationNotificationPublicationState.Cancelled, retired.body.state)
            assertNull(retired.body.message)
            assertNull(retired.body.actions)
            assertTrue(f.native.cards.isEmpty())
            assertTrue(f.app.sdk.enqueued.isEmpty())
            process.closeAndJoin()
            f.reopen()
            assertEquals(retired, f.publication())
        }
    }

    @Test
    fun companyDenialRemovesAndRetiresWithoutReadingDeniedPreviewIntoTheHost() = runBlocking {
        withProcess { f, process, shown, removed ->
            process.start()
            val card = next(shown) { it.body == "Fictional original for notification" }
            idle(f)
            while (removed.tryReceive().isSuccess) Unit
            assertIs<AppResult.Success<*>>(
                f.app.koin
                    .get<CollaborationListRepository>()
                    .restrict(
                        f.scope.access,
                        Failure(FailureKind.AccessDenied, "Restricted", code = "permission_denied"),
                    )
            )
            withTimeout(15_000) { removed.receiveAsFlow().first { it == card.reference } }
            idle(f)
            val retired = assertNotNull(f.publication())
            assertEquals(CollaborationNotificationPublicationState.Cancelled, retired.body.state)
            assertNull(retired.body.message)
            assertNull(retired.body.actions)
            assertTrue(f.native.cards.isEmpty())
            assertTrue(f.app.sdk.enqueued.isEmpty())
        }
    }

    private suspend fun next(
        shown: Channel<ActionNotificationPayload>,
        matches: (ActionNotificationPayload) -> Boolean,
    ) = withTimeout(15_000) { shown.receiveAsFlow().first(matches) }

    private suspend fun idle(f: NotificationPublisherFixture) {
        withTimeout(15_000) {
            f.owner.states.first { it is CollaborationNotificationSurfaceState.Ready && it.isIdle }
        }
    }

    private suspend fun withProcess(
        seedPublication: Boolean = true,
        block:
            suspend (
                NotificationPublisherFixture,
                CollaborationNotificationProcess,
                Channel<ActionNotificationPayload>,
                Channel<ActionNotificationReference>,
            ) -> Unit,
    ) {
        val f = NotificationPublisherFixture()
        val shown = Channel<ActionNotificationPayload>(32)
        val removed = Channel<ActionNotificationReference>(32)
        var process: CollaborationNotificationProcess? = null
        try {
            f.initialize()
            f.preferences(CollaborationNotificationPreview.Message)
            if (seedPublication) assertIs<AppResult.Success<*>>(f.publish())
            f.native.onShow = { shown.send(it) }
            f.native.onRemove = { removed.send(it) }
            val current =
                f.app.koin.get<CollaborationNotificationProcess> {
                    parametersOf(
                        f.owner,
                        CollaborationNotificationCopySource {
                            CollaborationNotificationCopy("HRIS", "New message")
                        },
                    )
                }
            process = current
            block(f, current, shown, removed)
        } finally {
            process?.closeAndJoin()
            f.native.onShow = {}
            f.native.onRemove = {}
            shown.close()
            removed.close()
            f.close()
        }
    }
}
