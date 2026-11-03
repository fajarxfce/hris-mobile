package dev.fajar.starter

import dev.fajar.starter.app.notifications.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationListRepository
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
                    parametersOf(f.owner, CollaborationNotificationCopy("HRIS", "New message"))
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
