@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.presentation.thread

import androidx.lifecycle.ViewModelStore
import dev.fajar.starter.collaboration.presentation.navigation.ChatThreadViewModelStores
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*

class ChatThreadRetainedStateTest {
    @Test
    fun reopenedRouteHasRowsImmediatelyWithoutReopeningHistoryOrReplayingOldEffects() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val graph = ViewModelStore()
        val route = ViewModelStore()
        val stores = ChatThreadViewModelStores().also { graph.put("rooms", it) }
        try {
            val f = ChatThreadFixture()
            f.historyRows.value = listOf(f.original())
            val owner = stores.open(f.conversationId).also { route.put("owner", it) }
            val vm =
                f.model().also {
                    owner.viewModelStore.put("thread", it)
                    owner.bind(it)
                }
            vm.onEvent(ChatThreadEvent.Entered())
            runCurrent()
            val saved = vm.state.value.rows
            assertEquals("Saved conversation", saved.single().text)
            val reads = f.requestedWindows.toList()
            vm.onEvent(ChatThreadEvent.MessageSelected(saved.single().key))
            vm.onEvent(ChatThreadEvent.CopySelected)
            runCurrent()
            assertFalse(vm.state.value.selecting)
            vm.onEvent(ChatThreadEvent.MessageSelected(saved.single().key))
            runCurrent()
            assertTrue(vm.state.value.selecting)
            route.clear()
            runCurrent()
            assertFalse(vm.state.value.selecting)
            assertEquals(saved, vm.state.value.rows)
            assertEquals(1, f.activeHistory)

            val reopened = stores.open(f.conversationId).also { route.put("owner", it) }
            assertSame(vm, reopened.viewModelStore["thread"])
            val nextEffect = backgroundScope.async { vm.effects.first() }
            vm.onEvent(ChatThreadEvent.Entered())
            assertEquals(saved, vm.state.value.rows)
            runCurrent()
            assertEquals(reads, f.requestedWindows)
            assertFalse(nextEffect.isCompleted)
            vm.onEvent(ChatThreadEvent.BackRequested)
            runCurrent()
            assertEquals(ChatThreadEffect.Back, nextEffect.await())

            route.clear()
            runCurrent()
            f.base.session.value = null
            runCurrent()
            assertTrue(vm.state.value.rows.isEmpty())
            assertNull(vm.state.value.title)
            graph.clear()
            runCurrent()
            assertEquals(0, f.activeHistory)
        } finally {
            route.clear()
            graph.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun aRoomThatHasNotEstablishedLocalContextIsDisposedOnDeparture() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val graph = ViewModelStore()
        val route = ViewModelStore()
        val stores = ChatThreadViewModelStores().also { graph.put("rooms", it) }
        try {
            val f = ChatThreadFixture()
            f.beforePage = { _, _ -> awaitCancellation() }
            val owner = stores.open(f.conversationId).also { route.put("owner", it) }
            val vm =
                f.model().also {
                    owner.viewModelStore.put("thread", it)
                    owner.bind(it)
                }
            vm.onEvent(ChatThreadEvent.Entered())
            runCurrent()
            assertEquals(1, f.activeHistory)
            route.clear()
            runCurrent()
            assertEquals(0, f.activeHistory)
            val reopened = stores.open(f.conversationId).also { route.put("owner", it) }
            assertNull(reopened.viewModelStore["thread"])
        } finally {
            route.clear()
            graph.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun releasingARouteCancelsItsPendingDepartureWhileTheDraftWriteCanFinish() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val graph = ViewModelStore()
        val route = ViewModelStore()
        val stores = ChatThreadViewModelStores().also { graph.put("rooms", it) }
        try {
            val f = ChatThreadFixture()
            f.historyRows.value = listOf(f.original())
            val saved = CompletableDeferred<Unit>()
            f.afterDraftWrite = { saved.await() }
            val owner = stores.open(f.conversationId).also { route.put("owner", it) }
            val vm =
                f.model().also {
                    owner.viewModelStore.put("thread", it)
                    owner.bind(it)
                }
            vm.onEvent(ChatThreadEvent.Entered())
            runCurrent()
            vm.onEvent(ChatThreadEvent.DraftChanged("Keep this draft"))
            vm.onEvent(ChatThreadEvent.BackRequested)
            runCurrent()
            assertTrue(vm.state.value.leaving)
            assertEquals("Keep this draft", f.draftWrites.single().text)
            route.clear()
            runCurrent()
            assertFalse(vm.state.value.leaving)

            val reopened = stores.open(f.conversationId).also { route.put("owner", it) }
            assertSame(vm, reopened.viewModelStore["thread"])
            val nextEffect = backgroundScope.async { vm.effects.first() }
            vm.onEvent(ChatThreadEvent.Entered())
            runCurrent()
            saved.complete(Unit)
            runCurrent()
            assertFalse(nextEffect.isCompleted)
            assertFalse(vm.state.value.leaving)
            assertNull(vm.state.value.draftDialog)
            assertEquals("Keep this draft", vm.state.value.draft)
            assertEquals("Keep this draft", f.draftSlot.value?.text)
            vm.onEvent(ChatThreadEvent.BackRequested)
            runCurrent()
            assertEquals(ChatThreadEffect.Back, nextEffect.await())
        } finally {
            route.clear()
            graph.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }
}
