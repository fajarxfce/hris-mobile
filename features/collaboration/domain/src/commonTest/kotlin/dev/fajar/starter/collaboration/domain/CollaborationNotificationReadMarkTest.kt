package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.test.runTest

class CollaborationNotificationReadMarkTest {
    @Test
    fun repeatedNativeReadDoesNotRequeueAfterAnotherAcknowledgmentReplacesItsReceipt() = runTest {
        val f = CollaborationReadStateFixture()
        assertIs<AppResult.Success<*>>(f.mark())
        f.state =
            f.state.copy(
                pending = emptyList(),
                acknowledged = f.intent.copy(id = CollaborationReadStateFixture.id(900)),
            )
        val retained = f.state
        assertEquals(
            AppResult.Success(retained),
            f.marker(f.scope(), f.conversationId, f.roomId, f.ids, f.intent, onlyIfUnread = true),
        )
        assertEquals(1, f.writes)
    }

    @Test
    fun knownOperationWithChangedVisibilityStillConflictsEvenWhenAlreadyRead() = runTest {
        val f = CollaborationReadStateFixture()
        assertIs<AppResult.Success<*>>(f.mark())
        assertEquals(
            FailureKind.Conflict,
            assertIs<AppResult.Failed>(
                    f.marker(
                        f.scope(),
                        f.conversationId,
                        f.roomId,
                        f.ids,
                        f.intent.copy(visibility = CollaborationReadReceiptVisibility.Public),
                        onlyIfUnread = true,
                    )
                )
                .failure
                .kind,
        )
        assertEquals(1, f.writes)
    }

    @Test
    fun unreadOnlyStillMarksExactMissingCoverageAndPreservesExistingIntent() = runTest {
        val f = CollaborationReadStateFixture()
        assertIs<AppResult.Success<*>>(f.mark(selected = f.ids.take(1)))
        val next = f.intent.copy(id = CollaborationReadStateFixture.id(901), eventId = f.ids.last())
        val marked =
            assertIs<AppResult.Success<CollaborationReadState>>(
                    f.marker(
                        f.scope(),
                        f.conversationId,
                        f.roomId,
                        f.ids,
                        next,
                        onlyIfUnread = true,
                    )
                )
                .value
        assertEquals(f.ids.toSet(), marked.readEventIds)
        assertEquals(listOf(f.intent, next), marked.pending)
        assertEquals(2, f.writes)
    }
}
