@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.presentation.thread

import androidx.lifecycle.ViewModelStore
import dev.fajar.starter.collaboration.domain.entities.CollaborationHistoryPage
import dev.fajar.starter.collaboration.domain.policies.localListAccess
import dev.fajar.starter.collaboration.presentation.navigation.ChatThreadViewModelStores
import dev.fajar.starter.common.result.AppResult
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*

class ChatThreadRetainedStateTest {
    @Test
    fun localPreparationRendersRowsBeforeFirstEntryWithoutStartingTransportOrMarkingRead() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val graph = ViewModelStore()
            val route = ViewModelStore()
            val stores = ChatThreadViewModelStores().also { graph.put("rooms", it) }
            try {
                val f = ChatThreadFixture()
                f.online()
                f.historyRows.value = listOf(f.original())
                val prepared = stores.prepare(listOf(f.conversationId)).getValue(f.conversationId)
                val vm =
                    f.model().also {
                        prepared.models.put("thread", it)
                        prepared.bind(it)
                    }
                val access = f.setup.owner.localListAccess()
                vm.onEvent(ChatThreadEvent.LocalPrepared(access))
                runCurrent()
                val rows = vm.state.value.rows
                assertEquals("Saved conversation", rows.single().text)
                assertFalse(vm.state.value.loading)
                vm.onEvent(
                    ChatThreadEvent.ViewportChanged(
                        vm.state.value.readViewportToken,
                        rows.map { it.key },
                    )
                )
                vm.onEvent(ChatThreadEvent.DraftChanged("Hidden input"))
                vm.onEvent(ChatThreadEvent.SendRequested)
                runCurrent()
                assertEquals(0, f.timelineOpens)
                assertEquals(0, f.deliveries)
                assertTrue(f.roomRequests.isEmpty())
                assertTrue(f.draftWriteAttempts.isEmpty())
                assertTrue(f.insertions.isEmpty())
                assertTrue(f.scheduledKeys.isEmpty())
                assertTrue(f.readState.marks.isEmpty())
                val reads = f.requestedWindows.toList()
                val owner = stores.open(f.conversationId).also { route.put("owner", it) }
                assertSame(vm, owner.viewModelStore["thread"])
                assertEquals(rows, vm.state.value.rows)
                vm.onEvent(ChatThreadEvent.Entered(access))
                assertEquals(rows, vm.state.value.rows)
                runCurrent()
                assertEquals(reads, f.requestedWindows)
                assertEquals(1, f.timelineOpens)
            } finally {
                route.clear()
                graph.clear()
                runCurrent()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun pendingPreparationContinuesOnEntryAndUnopenedEvictionCancelsItsReader() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val graph = ViewModelStore()
        val route = ViewModelStore()
        val stores = ChatThreadViewModelStores(1).also { graph.put("rooms", it) }
        try {
            val f = ChatThreadFixture()
            val ready = CompletableDeferred<Unit>()
            f.beforePage = { _, _ -> ready.await() }
            f.historyRows.value = listOf(f.original())
            val prepared = stores.prepare(listOf(f.conversationId)).getValue(f.conversationId)
            val vm =
                f.model().also {
                    prepared.models.put("thread", it)
                    prepared.bind(it)
                }
            vm.onEvent(ChatThreadEvent.LocalPrepared(f.setup.owner.localListAccess()))
            runCurrent()
            assertEquals(1, f.activeHistory)
            val reads = f.requestedWindows.toList()
            val owner = stores.open(f.conversationId).also { route.put("owner", it) }
            assertSame(vm, owner.viewModelStore["thread"])
            vm.onEvent(ChatThreadEvent.Entered())
            runCurrent()
            assertEquals(reads, f.requestedWindows)
            ready.complete(Unit)
            runCurrent()
            assertEquals("Saved conversation", vm.state.value.rows.single().text)
            graph.clear()
            runCurrent()
            assertEquals(0, f.activeHistory)

            val pendingStores = ChatThreadViewModelStores(1).also { graph.put("rooms", it) }
            val pending = pendingStores.prepare(listOf(f.conversationId)).getValue(f.conversationId)
            f.beforePage = { _, _ -> awaitCancellation() }
            val next =
                f.model().also {
                    pending.models.put("thread", it)
                    pending.bind(it)
                }
            next.onEvent(ChatThreadEvent.LocalPrepared(f.setup.owner.localListAccess()))
            runCurrent()
            assertEquals(1, f.activeHistory)
            pendingStores.prepare(listOf("another-room"))
            runCurrent()
            assertEquals(0, f.activeHistory)
            assertTrue(pending.closed)
        } finally {
            route.clear()
            graph.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun failedLocalPreparationCanRetryOnEntryWhileRetainedDenialStillClosesRead() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val graph = ViewModelStore()
        try {
            val f = ChatThreadFixture()
            val vm = f.model().also { graph.put("thread", it) }
            val access = f.setup.owner.localListAccess()
            f.historyPages[null to null] = AppResult.Failed(f.base.network)
            vm.onEvent(ChatThreadEvent.LocalPrepared(access))
            runCurrent()
            assertNotNull(vm.state.value.localFailure)
            assertTrue(vm.state.value.rows.isEmpty())
            f.historyPages[null to null] =
                AppResult.Success(CollaborationHistoryPage(listOf(f.original()), null))
            vm.onEvent(ChatThreadEvent.Entered(access))
            runCurrent()
            assertEquals("Saved conversation", vm.state.value.rows.single().text)
            vm.onEvent(ChatThreadEvent.Stopped)
            runCurrent()
            f.base.gate.value = f.base.gate.value.copy(denial = f.base.denied)
            runCurrent()
            assertTrue(vm.state.value.rows.isEmpty())
            vm.onEvent(ChatThreadEvent.Entered(access))
            runCurrent()
            assertTrue(vm.state.value.rows.isEmpty())
            assertNull(vm.state.value.title)
            assertEquals(0, f.timelineOpens)
        } finally {
            graph.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

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
