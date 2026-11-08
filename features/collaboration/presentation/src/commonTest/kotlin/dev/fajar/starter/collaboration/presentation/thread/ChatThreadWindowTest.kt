@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.presentation.thread

import androidx.lifecycle.ViewModelStore
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*

class ChatThreadWindowTest {
    @Test
    fun sixHundredOriginalsRemainReachableInBothDirectionsWithOneOwnedObserver() = runTest {
        withWindow { f, vm, _ ->
            vm.onEvent(ChatThreadEvent.Entered())
            runCurrent()
            val visited = vm.state.value.rows.mapNotNull { it.eventId }.toMutableSet()
            vm.onEvent(ChatThreadEvent.DraftChanged("Retain my draft"))
            repeat(5) {
                vm.onEvent(ChatThreadEvent.OlderRequested)
                vm.onEvent(ChatThreadEvent.OlderRequested)
                runCurrent()
                assertTrue(vm.state.value.rows.size <= 150)
                assertTrue(vm.state.value.browsingHistory)
                visited += vm.state.value.rows.mapNotNull { it.eventId }
            }
            assertEquals(603, visited.size)
            assertFalse(vm.state.value.hasOlder)
            assertEquals("Original 1", vm.state.value.rows.last().text)
            repeat(5) {
                vm.onEvent(ChatThreadEvent.NewerRequested)
                runCurrent()
            }
            assertFalse(vm.state.value.browsingHistory)
            assertFalse(vm.state.value.hasNewer)
            assertEquals("Original 603", vm.state.value.rows.first().text)
            assertEquals("Retain my draft", vm.state.value.draft)
            assertEquals(1, f.maxActiveHistory)
            assertEquals(1, f.activeHistory)
            assertEquals(0, f.timelineOpens)
            assertTrue(f.past.requestedPositions.isEmpty())
        }
    }

    @Test
    fun returnToLatestAndSendingFromOldHistoryPublishOnlyAfterTheHeadArrives() = runTest {
        withWindow { f, vm, effects ->
            vm.onEvent(ChatThreadEvent.Entered())
            runCurrent()
            vm.onEvent(ChatThreadEvent.OlderRequested)
            runCurrent()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.beforePage = { after, before ->
                if (after == null && before == null) {
                    entered.complete(Unit)
                    release.await()
                }
            }
            vm.onEvent(ChatThreadEvent.DraftChanged("Send from history"))
            vm.onEvent(ChatThreadEvent.SendRequested)
            runCurrent()
            entered.await()
            assertEquals(1, f.composerWrites.size)
            assertTrue(vm.state.value.browsingHistory)
            assertFalse(vm.state.value.rows.any { it.text == "Send from history" })
            assertTrue(effects.isEmpty())
            release.complete(Unit)
            runCurrent()
            assertFalse(vm.state.value.browsingHistory)
            assertTrue(vm.state.value.rows.any { it.text == "Send from history" })
            assertEquals(listOf<ChatThreadEffect>(ChatThreadEffect.ScrollToNewest), effects)
            assertEquals(1, f.maxActiveHistory)
        }
    }

    @Test
    fun latestSupersedesAnUncooperativeOlderReadWithoutPublishingItsRows() = runTest {
        withWindow { f, vm, effects ->
            vm.onEvent(ChatThreadEvent.Entered())
            runCurrent()
            val release = CompletableDeferred<Unit>()
            f.beforePage = { after, _ ->
                if (after != null) withContext(NonCancellable) { release.await() }
            }
            vm.onEvent(ChatThreadEvent.OlderRequested)
            runCurrent()
            assertTrue(vm.state.value.loading)
            vm.onEvent(ChatThreadEvent.LatestRequested)
            runCurrent()
            release.complete(Unit)
            runCurrent()
            assertFalse(vm.state.value.loading)
            assertFalse(vm.state.value.browsingHistory)
            assertEquals("Original 603", vm.state.value.rows.first().text)
            assertEquals(listOf<ChatThreadEffect>(ChatThreadEffect.ScrollToNewest), effects)
            assertEquals(1, f.maxActiveHistory)
        }
    }

    @Test
    fun denialClosesPendingReverseReadAndClearsDraftDespiteLateStorage() = runTest {
        withWindow { f, vm, _ ->
            vm.onEvent(ChatThreadEvent.Entered())
            runCurrent()
            vm.onEvent(ChatThreadEvent.OlderRequested)
            runCurrent()
            vm.onEvent(ChatThreadEvent.DraftChanged("Private draft"))
            val release = CompletableDeferred<Unit>()
            f.beforePage = { _, before ->
                if (before != null) withContext(NonCancellable) { release.await() }
            }
            vm.onEvent(ChatThreadEvent.NewerRequested)
            runCurrent()
            f.base.gate.value = CollaborationListAdmission("denied", f.base.denied)
            runCurrent()
            release.complete(Unit)
            runCurrent()
            assertEquals(f.base.denied, vm.state.value.localFailure)
            assertTrue(vm.state.value.rows.isEmpty())
            assertEquals("", vm.state.value.draft)
            assertEquals(0, f.activeHistory)
        }
    }

    @Test
    fun failedOrReplacedOlderWindowCannotKeepHiddenStaleRows() = runTest {
        for (failed in listOf(false, true)) withWindow { f, vm, _ ->
            vm.onEvent(ChatThreadEvent.Entered())
            runCurrent()
            f.historyPages[anchor(1) to null] =
                if (failed) AppResult.Failed(f.base.network)
                else AppResult.Success(CollaborationHistoryPage(emptyList(), null))
            vm.onEvent(ChatThreadEvent.OlderRequested)
            runCurrent()
            if (failed) {
                assertEquals(f.base.network, vm.state.value.localFailure)
                assertTrue(vm.state.value.rows.isEmpty())
            } else {
                assertFalse(vm.state.value.browsingHistory)
                assertEquals("Original 603", vm.state.value.rows.first().text)
            }
        }
    }

    private suspend fun TestScope.withWindow(
        block:
            suspend TestScope.(
                ChatThreadFixture, ChatThreadViewModel, MutableList<ChatThreadEffect>,
            ) -> Unit
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = ChatThreadFixture()
        f.base.now += 30.days
        val messages =
            (603 downTo 1).map { index ->
                f.original()
                    .copy(
                        primaryEventId = "event-$index",
                        timestamp = index.toLong(),
                        text = "Original $index",
                        eventIds = listOf("event-$index"),
                    )
            }
        val pages =
            (0..5).map { step ->
                CollaborationHistoryPage(
                    messages.drop(step * 100).take(150),
                    if (step < 5) anchor(step + 1) else null,
                    if (step > 0) anchor(step) else null,
                )
            }
        f.historyPages[null to null] = AppResult.Success(pages.first())
        for (step in 1..5) {
            f.historyPages[anchor(step) to null] = AppResult.Success(pages[step])
            f.historyPages[null to anchor(step)] = AppResult.Success(pages[step - 1])
        }
        val vm = f.model()
        val store = ViewModelStore().also { it.put("window", vm) }
        val effects = mutableListOf<ChatThreadEffect>()
        val collection = backgroundScope.launch { vm.effects.collect { effects += it } }
        try {
            block(f, vm, effects)
        } finally {
            store.clear()
            collection.cancel()
            runCurrent()
            assertEquals(0, f.activeHistory)
            Dispatchers.resetMain()
        }
    }

    private fun anchor(index: Int) = index.toString().padStart(16, '0') + "/m-" + "a".repeat(64)
}
