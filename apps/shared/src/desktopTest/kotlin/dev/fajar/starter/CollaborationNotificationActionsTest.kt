package dev.fajar.starter

import dev.fajar.starter.app.notifications.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.notifications.data.dto.*
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.*
import org.koin.core.parameter.parametersOf

/** Actual Koin/SQLite and fictional acquisition; OS display is explicitly simulated here. */
class CollaborationNotificationActionsTest {
    @Test
    fun openOnlyReturnsCapturedNavigationWithoutReadingOrSending() = runBlocking {
        fixture { f ->
            val original = f.publication()
            val state = f.readState()
            val result =
                assertIs<CollaborationNotificationActionOutcome.Navigate>(
                    success(f.actions()(f.input(ActionNotificationKind.Open)))
                )
            assertEquals(f.scope, result.scope)
            assertEquals(f.candidate.conversationId, result.conversationId)
            assertEquals(state, f.readState())
            assertEquals(original, f.publication())
            assertEquals(1, f.native.cards.size)
            assertTrue(f.app.sdk.enqueued.isEmpty())
        }
    }

    @Test
    fun readMuteAndDismissRetireOnlyTheirRenderAndRemainRetiredAfterReopen() = runBlocking {
        for (kind in
            listOf(
                ActionNotificationKind.MarkRead,
                ActionNotificationKind.Mute,
                ActionNotificationKind.Dismiss,
            )) fixture { f ->
            val input = f.input(kind)
            assertEquals(
                CollaborationNotificationActionOutcome.Completed(kind),
                success(f.actions()(input)),
            )
            assertTrue(f.native.cards.isEmpty())
            assertEquals(
                if (kind == ActionNotificationKind.Dismiss)
                    CollaborationNotificationPublicationState.Dismissed
                else CollaborationNotificationPublicationState.Cancelled,
                f.publication()!!.body.state,
            )
            if (kind == ActionNotificationKind.MarkRead) {
                val read = f.readState()
                assertEquals(
                    listOf(CollaborationReadReceiptVisibility.Private),
                    read.pending.map { it.visibility },
                )
            } else assertTrue(f.readState().pending.isEmpty())
            if (kind == ActionNotificationKind.Mute) {
                val settings =
                    success(
                        f.app.koin
                            .get<CollaborationNotificationSettingsRepository>()
                            .read(f.scope.access, f.candidate.conversationId)
                    )
                assertEquals(CollaborationNotificationMute.Forever, settings.settings.mute)
            }
            f.reopen()
            assertEquals(
                CollaborationNotificationActionOutcome.Obsolete,
                success(f.actions()(input)),
            )
            assertNotEquals(
                AppResult.Success(CollaborationNotificationPublicationResult.Submitted),
                f.publish(),
            )
            assertTrue(f.native.cards.isEmpty())
            assertTrue(f.app.sdk.enqueued.isEmpty())
            assertEquals(0, f.app.sdk.deliveries)
        }
    }

    @Test
    fun outerKindReferenceAndUnexpectedReplyTextCannotRetargetASealedAction() = runBlocking {
        fixture { f ->
            val original = f.publication()
            val input = f.input(ActionNotificationKind.MarkRead)
            for (invalid in
                listOf(
                    input.copy(kind = ActionNotificationKind.Mute),
                    input.copy(kind = ActionNotificationKind.Open),
                    input.copy(kind = ActionNotificationKind.Reply, replyText = "Wrong path"),
                    input.copy(replyText = "Unexpected"),
                    input.copy(
                        reference = input.reference.copy(renderId = UUID.randomUUID().toString())
                    ),
                )) {
                assertEquals(
                    FailureKind.Validation,
                    assertIs<AppResult.Failed>(f.actions()(invalid)).failure.kind,
                )
            }
            assertEquals(original, f.publication())
            assertTrue(f.readState().pending.isEmpty())
            assertEquals(1, f.native.cards.size)
            assertTrue(f.app.sdk.enqueued.isEmpty())
        }
    }

    @Test
    fun laterNativeRenderSurvivesAnOldActionAndItsCompensation() = runBlocking {
        fixture { f ->
            val input = f.input(ActionNotificationKind.MarkRead)
            val original = f.publication()
            val oldCard = f.native.cards.values.single()
            val replacement =
                oldCard.copy(
                    reference = oldCard.reference.copy(renderId = UUID.randomUUID().toString())
                )
            f.native.cards[replacement.reference.id] = replacement
            assertIs<AppResult.Failed>(f.actions()(input))
            assertEquals(replacement, f.native.cards.values.single())
            assertEquals(original, f.publication())
            assertEquals(1, f.readState().pending.size)
        }
    }

    @Test
    fun retainedDenialBlocksActionsWithoutDeletingTheJournal() = runBlocking {
        fixture { f ->
            val input = f.input(ActionNotificationKind.Dismiss)
            val original = f.publication()
            val denied = Failure(FailureKind.AccessDenied, "Restricted", code = "permission_denied")
            assertIs<AppResult.Success<*>>(
                f.app.koin.get<CollaborationListRepository>().restrict(f.scope.access, denied)
            )
            assertEquals(
                FailureKind.AccessDenied,
                assertIs<AppResult.Failed>(f.actions()(input)).failure.kind,
            )
            assertEquals(original, f.publication())
            assertEquals(1, f.native.cards.size)
        }
    }

    @Test
    fun closingTheOwnerJoinsPendingRemovalAndPreservesUncertainJournal() = runBlocking {
        fixture { f ->
            val input = f.input(ActionNotificationKind.MarkRead)
            val original = f.publication()
            val entered = CompletableDeferred<Unit>()
            var joined = false
            f.native.onRemove = {
                entered.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    joined = true
                }
            }
            val pending = async { f.actions()(input) }
            withTimeout(8_000) { entered.await() }
            f.owner.closeAndJoin()
            assertTrue(joined)
            assertEquals(
                FailureKind.Unauthorized,
                assertIs<AppResult.Failed>(pending.await()).failure.kind,
            )
            assertEquals(original, f.publication())
            assertEquals(1, f.readState().pending.size)
        }
    }

    @Test
    fun callerCancellationJoinsRemovalAndRetryDoesNotDuplicateTheReadIntent() = runBlocking {
        fixture { f ->
            val input = f.input(ActionNotificationKind.MarkRead)
            val entered = CompletableDeferred<Unit>()
            var joined = false
            f.native.onRemove = {
                entered.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    joined = true
                }
            }
            val pending = async { f.actions()(input) }
            withTimeout(8_000) { entered.await() }
            pending.cancelAndJoin()
            assertTrue(joined)
            assertTrue(pending.isCancelled)
            assertEquals(1, f.readState().pending.size)
            f.native.onRemove = {}
            assertEquals(
                CollaborationNotificationActionOutcome.Completed(ActionNotificationKind.MarkRead),
                success(f.actions()(input)),
            )
            assertEquals(1, f.readState().pending.size)
        }
    }

    @Test
    fun interruptedDismissalReopensAsCleanupAndCannotRepostTheOriginal() = runBlocking {
        fixture { f ->
            val input = f.input(ActionNotificationKind.Dismiss)
            f.native.onRemove = { error("Simulated interrupted native removal") }
            assertIs<AppResult.Failed>(f.actions()(input))
            val pending = f.publication()!!
            assertEquals(CollaborationNotificationPublicationState.Dismissing, pending.body.state)
            assertNull(pending.body.message)
            assertNull(pending.body.actions)
            f.native.onRemove = {}
            f.reopen()
            val read =
                f.app.koin.get<
                    dev.fajar.starter.collaboration.domain.usecases.ReadCollaborationNotificationPublications
                >()
            val reference = success(read(f.parent)).references.single()
            assertEquals(CollaborationNotificationPublicationState.Dismissing, reference.state)
            assertEquals(
                AppResult.Success(CollaborationNotificationPublicationResult.Unchanged),
                f.publish(),
            )
            assertTrue(f.native.cards.isEmpty())
            assertIs<AppResult.Success<*>>(
                f.app.koin
                    .get<CollaborationListRepository>()
                    .restrict(
                        f.scope.access,
                        Failure(FailureKind.AccessDenied, "Restricted", code = "permission_denied"),
                    )
            )
            val reconciler =
                f.app.koin.get<CollaborationNotificationReconciler> { parametersOf(f.owner) }
            assertEquals(
                AppResult.Success(CollaborationNotificationReconciliationResult.Retired),
                reconciler.reconcile(
                    f.parent,
                    reference,
                    CollaborationNotificationCopy("HRIS", "New message"),
                ),
            )
            assertEquals(
                CollaborationNotificationPublicationState.Dismissed,
                f.publication()!!.body.state,
            )
            assertTrue(success(read(f.parent)).references.isEmpty())
        }
    }

    private fun NotificationPublisherFixture.actions() =
        app.koin.get<CollaborationNotificationActions> { parametersOf(owner) }

    private fun NotificationPublisherFixture.input(
        kind: ActionNotificationKind
    ): ActionNotificationInput {
        val card = native.cards.values.single()
        return ActionNotificationInput(card.reference, kind, card.envelopes.getValue(kind))
    }

    private suspend fun NotificationPublisherFixture.readState() =
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

    private suspend fun fixture(block: suspend (NotificationPublisherFixture) -> Unit) {
        val f = NotificationPublisherFixture()
        try {
            f.initialize()
            assertEquals(
                AppResult.Success(CollaborationNotificationPublicationResult.Submitted),
                f.publish(),
            )
            block(f)
        } finally {
            f.close()
        }
    }
}
