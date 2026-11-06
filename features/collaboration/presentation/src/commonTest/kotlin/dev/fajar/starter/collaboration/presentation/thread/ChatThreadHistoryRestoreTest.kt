@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.presentation.thread

import androidx.lifecycle.ViewModelStore
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.presentation.inbox.chatId
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class ChatThreadHistoryRestoreTest {
    @Test
    fun metadataAcrossSeveralPagesDoesNotBringBackOlderAfterRoomRecreation() = runTest {
        withRoom { f, store ->
            val events = metadata(f, 45) + f.past.event(46)
            f.past.pages.addAll(events.chunked(20))
            val first = enter(f, store)
            repeat(8) {
                first.onEvent(ChatThreadEvent.OlderRequested)
                advanceUntilIdle()
            }
            assertFalse(first.state.value.hasOlder)
            assertEquals(3, f.past.requestedPositions.size)
            assertEquals(45, f.past.references.count { !it.resolved })
            val requests = f.past.requestedPositions.toList()
            val anchors = f.past.anchors.toList()
            repeat(3) {
                store.clear()
                runCurrent()
                val reopened = enter(f, store)
                assertEquals("Older message 46", reopened.state.value.rows.single().text)
                assertFalse(reopened.state.value.hasOlder)
                assertFalse(reopened.state.value.historyIncomplete)
                reopened.onEvent(ChatThreadEvent.OlderRequested)
                advanceUntilIdle()
            }
            assertEquals(requests, f.past.requestedPositions)
            assertEquals(anchors, f.past.anchors)
            assertEquals(45, f.past.references.count { !it.resolved })
        }
    }

    @Test
    fun aRoomWithOnlyMetadataKeepsItsEndAfterReopening() = runTest {
        withRoom { f, store ->
            f.past.pages += metadata(f, 2)
            val first = enter(f, store)
            first.onEvent(ChatThreadEvent.OlderRequested)
            advanceUntilIdle()
            assertFalse(first.state.value.hasOlder)
            store.clear()
            runCurrent()
            val reopened = enter(f, store)
            assertFalse(reopened.state.value.hasOlder)
            assertTrue(reopened.state.value.rows.isEmpty())
            assertTrue(f.past.anchors.isEmpty())
            assertEquals(1, f.past.requestedPositions.size)
        }
    }

    @Test
    fun anUnresolvedEncryptedMessageBeyondMetadataRemainsAvailable() = runTest {
        withRoom { f, store ->
            retain(f, metadata(f, 41) + f.past.event(42))
            val room = enter(f, store)
            assertTrue(room.state.value.hasOlder)
            assertTrue(f.past.anchors.isEmpty())
            assertTrue(f.past.requestedPositions.isEmpty())
            repeat(3) {
                room.onEvent(ChatThreadEvent.OlderRequested)
                advanceUntilIdle()
            }
            assertEquals("Older message 42", room.state.value.rows.single().text)
            assertFalse(room.state.value.hasOlder)
            assertEquals(listOf("\$older-42"), f.past.anchors)
            assertTrue(f.past.requestedPositions.isEmpty())
        }
    }

    @Test
    fun providerGrowthDuringLocalRestoreCannotPublishThePreviousEnd() = runTest {
        withRoom { f, store ->
            retain(f, metadata(f, 41))
            var reads = 0
            f.past.beforeReferences = {
                if (++reads == 2) {
                    f.past.references += CollaborationHistoryReference(41, f.past.event(42))
                    f.past.progress = requireNotNull(f.past.progress).copy(references = 42)
                }
            }
            val room = enter(f, store)
            assertTrue(room.state.value.hasOlder)
            assertTrue(f.past.anchors.isEmpty())
            assertTrue(f.past.requestedPositions.isEmpty())
        }
    }

    @Test
    fun replacingTheTraversalDuringLocalRestoreCannotPublishItsOldEnd() = runTest {
        withRoom { f, store ->
            retain(f, metadata(f, 41))
            var reads = 0
            f.past.beforeReferences = {
                if (++reads == 2) {
                    f.past.progress = requireNotNull(f.past.progress).copy(generation = chatId(61))
                }
            }
            val room = enter(f, store)
            assertTrue(room.state.value.hasOlder)
            assertTrue(f.past.requestedPositions.isEmpty())
        }
    }

    @Test
    fun closingDuringRestoreCancelsTheReadAndClosesItsTimeline() = runTest {
        withRoom { f, store ->
            retain(f, metadata(f, 41))
            val reading = CompletableDeferred<Unit>()
            var cancelled = false
            f.past.beforeReferences = {
                reading.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            }
            val room = f.model().also { store.put("room", it) }
            room.onEvent(ChatThreadEvent.Entered())
            runCurrent()
            assertTrue(reading.isCompleted)
            store.clear()
            advanceUntilIdle()
            assertTrue(cancelled)
            assertEquals(1, f.timelineCloses)
            assertTrue(f.past.requestedPositions.isEmpty())
            assertTrue(f.past.anchors.isEmpty())
        }
    }

    private fun metadata(f: ChatThreadFixture, count: Int) =
        (1..count).map {
            f.past
                .event(it, readable = false)
                .copy(type = if (it == 1) "m.room.create" else "m.room.member")
        }

    private fun retain(f: ChatThreadFixture, events: List<CollaborationProviderHistoryEvent>) {
        f.past.references =
            events.mapIndexed { i, event -> CollaborationHistoryReference(i.toLong(), event) }
        f.past.progress = CollaborationHistoryProgress(chatId(60), 0, null, events.size.toLong())
    }

    private suspend fun TestScope.enter(
        f: ChatThreadFixture,
        store: ViewModelStore,
    ): ChatThreadViewModel {
        val room = f.model().also { store.put("room", it) }
        room.onEvent(ChatThreadEvent.Entered())
        advanceUntilIdle()
        return room
    }

    private suspend fun TestScope.withRoom(
        block: suspend TestScope.(ChatThreadFixture, ViewModelStore) -> Unit
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = ChatThreadFixture().also { it.online() }
        val store = ViewModelStore()
        try {
            block(f, store)
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }
}
