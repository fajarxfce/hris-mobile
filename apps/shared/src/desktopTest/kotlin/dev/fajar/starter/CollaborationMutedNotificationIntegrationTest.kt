package dev.fajar.starter

import dev.fajar.starter.app.notifications.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.notifications.data.dto.*
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.runBlocking

class CollaborationMutedNotificationIntegrationTest {
    @Test
    fun initialMuteSurvivesDatabaseReopenAndUnmuteWithoutReplayingOriginal() = runBlocking {
        withFixture { f ->
            f.preferences(
                CollaborationNotificationPreview.Hidden,
                CollaborationNotificationMute.Forever,
            )
            assertEquals(recorded(), f.publish(allowAlert = true))
            val retained = assertNotNull(f.publication())
            assertNull(retained.body.message)
            assertNull(retained.body.actions)
            assertEquals(0, f.native.attempts)
            f.reopen()
            f.preferences(CollaborationNotificationPreview.Message)
            assertEquals(
                AppResult.Success(CollaborationNotificationPublicationResult.Unchanged),
                f.publish(allowAlert = true),
            )
            assertEquals(retained, f.publication())
            assertEquals(0, f.native.attempts)
            assertTrue(f.native.cards.isEmpty())
        }
    }

    @Test
    fun activeCardIsRemovedBeforeInactiveWatermarkIsCommitted() = runBlocking {
        withFixture { f ->
            assertIs<AppResult.Success<*>>(f.publish())
            val active = assertNotNull(f.publication())
            val reference = f.native.cards.values.single().reference
            var checked = false
            f.native.onRemove = {
                assertEquals(reference, it)
                assertEquals(active, f.publication())
                checked = true
            }
            f.preferences(
                CollaborationNotificationPreview.Hidden,
                CollaborationNotificationMute.Forever,
            )
            assertEquals(recorded(), f.publish())
            assertTrue(checked)
            assertTrue(f.native.cards.isEmpty())
            val inactive = assertNotNull(f.publication())
            assertEquals(CollaborationNotificationPublicationState.Cancelled, inactive.body.state)
            assertNull(inactive.body.actions)
            assertNull(inactive.body.message)
        }
    }

    @Test
    fun uncertainRemovalPreservesActiveJournalForRetry() = runBlocking {
        withFixture { f ->
            f.publish()
            val active = f.publication()
            f.native.onRemove = { error("Fictional lost native removal response") }
            f.preferences(
                CollaborationNotificationPreview.Hidden,
                CollaborationNotificationMute.Forever,
            )
            assertIs<AppResult.Failed>(f.publish())
            assertEquals(active, f.publication())
            assertTrue(f.native.cards.isEmpty())
            f.native.onRemove = {}
            assertEquals(recorded(), f.publish())
            assertEquals(
                CollaborationNotificationPublicationState.Cancelled,
                f.publication()!!.body.state,
            )
        }
    }

    @Test
    fun unmuteDuringNativeRemovalWithholdsCommitAndLeavesJournalRecoverable() = runBlocking {
        withFixture { f ->
            f.publish()
            val active = f.publication()
            f.native.onRemove = { f.preferences(CollaborationNotificationPreview.Hidden) }
            f.preferences(
                CollaborationNotificationPreview.Hidden,
                CollaborationNotificationMute.Forever,
            )
            assertIs<AppResult.Failed>(f.publish())
            assertEquals(active, f.publication())
            assertTrue(f.native.cards.isEmpty())
            f.native.onRemove = {}
            assertEquals(
                AppResult.Success(CollaborationNotificationPublicationResult.Submitted),
                f.publish(allowAlert = true),
            )
            assertTrue(f.native.cards.values.single().silent)
        }
    }

    @Test
    fun replacedNativeRenderIsPreservedAndCannotBeAcknowledgedAsMuted() = runBlocking {
        withFixture { f ->
            f.publish()
            val active = f.publication()
            val old = f.native.cards.values.single()
            val newer =
                old.copy(reference = old.reference.copy(renderId = UUID.randomUUID().toString()))
            f.native.cards[newer.reference.id] = newer
            f.preferences(
                CollaborationNotificationPreview.Hidden,
                CollaborationNotificationMute.Forever,
            )
            assertIs<AppResult.Failed>(f.publish())
            assertEquals(active, f.publication())
            assertEquals(newer, f.native.cards.values.single())
        }
    }

    @Test
    fun conditionalReconciliationCannotRemoveChangedJournalRender() = runBlocking {
        withFixture { f ->
            f.publish()
            val old = f.publication()!!
            val reference =
                CollaborationNotificationPublicationReference(
                    old.id,
                    old.version,
                    old.body.parent,
                    old.body.target,
                    old.body.state,
                )
            f.preferences(CollaborationNotificationPreview.Message)
            f.publish()
            val current = f.publication()!!
            assertNotEquals(old.version, current.version)
            val card = f.native.cards.values.single()
            f.preferences(
                CollaborationNotificationPreview.Hidden,
                CollaborationNotificationMute.Forever,
            )
            assertEquals(
                AppResult.Success(CollaborationNotificationPublicationResult.Unchanged),
                f.publisher.publish(
                    f.parent,
                    f.candidate,
                    CollaborationNotificationCopy("HRIS", "New message"),
                    expected = reference,
                ),
            )
            assertEquals(current, f.publication())
            assertEquals(card, f.native.cards.values.single())
            assertTrue(f.native.removed.isEmpty())
        }
    }

    private fun recorded() =
        AppResult.Success(CollaborationNotificationPublicationResult.SuppressionRecorded)

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
