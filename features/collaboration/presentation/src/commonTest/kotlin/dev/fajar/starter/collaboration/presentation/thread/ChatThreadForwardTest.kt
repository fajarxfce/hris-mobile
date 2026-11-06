@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.presentation.thread

import androidx.lifecycle.ViewModelStore
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class ChatThreadForwardTest {
    @Test
    fun longPressForwardPublishesMultipleMessagesToSelectedChatsOfflineAndPreservesDraft() =
        runTest {
            withThread { f, vm ->
                f.base.now += 365.days
                vm.onEvent(ChatThreadEvent.Entered())
                runCurrent()
                vm.onEvent(ChatThreadEvent.DraftChanged("Keep this draft"))
                advanceTimeBy(250)
                runCurrent()
                val draft = f.draftSlot.value
                open(vm)
                val picker = assertNotNull(vm.state.value.forwarding)
                assertEquals(3, picker.targets.size)
                vm.onEvent(ChatThreadEvent.ForwardSearchChanged("Colleague 2"))
                vm.onEvent(ChatThreadEvent.ForwardTargetSelected(f.base.item(2).conversation.id))
                vm.onEvent(ChatThreadEvent.ForwardTargetSelected(f.base.item(3).conversation.id))
                vm.onEvent(ChatThreadEvent.ForwardConfirmed)
                runCurrent()
                assertNull(vm.state.value.forwarding)
                assertFalse(vm.state.value.selecting)
                assertEquals(4, f.insertions.size)
                assertEquals(4, f.insertions.map { it.id }.distinct().size)
                assertTrue(f.insertions.all { it.forwarded && it.replyToEventId == null })
                assertEquals(draft, f.draftSlot.value)
                assertEquals("Keep this draft", vm.state.value.draft)
                assertEquals(0, f.timelineOpens)
            }
        }

    @Test
    fun backClosesOnlyTheRecipientPickerAndNeverPublishes() = runTest {
        withThread { f, vm ->
            vm.onEvent(ChatThreadEvent.Entered())
            runCurrent()
            open(vm)
            vm.onEvent(ChatThreadEvent.ForwardTargetSelected(f.base.item(2).conversation.id))
            vm.onEvent(ChatThreadEvent.BackRequested)
            runCurrent()
            assertNull(vm.state.value.forwarding)
            assertTrue(vm.state.value.selecting)
            assertTrue(f.insertions.isEmpty())
        }
    }

    @Test
    fun retryAfterLostLocalReplyReusesThePreparedIdsWithoutAnotherInsert() = runTest {
        withThread { f, vm ->
            vm.onEvent(ChatThreadEvent.Entered())
            runCurrent()
            open(vm)
            f.insertResult = AppResult.Failed(Failure(FailureKind.Storage, "Lost local response"))
            vm.onEvent(ChatThreadEvent.ForwardTargetSelected(f.base.item(2).conversation.id))
            vm.onEvent(ChatThreadEvent.ForwardConfirmed)
            runCurrent()
            val retained = f.insertions.toList()
            assertEquals(2, retained.size)
            assertTrue(vm.state.value.forwarding!!.locked)
            assertNotNull(vm.state.value.forwarding!!.failure)
            vm.onEvent(ChatThreadEvent.ForwardTargetSelected(f.base.item(3).conversation.id))
            assertEquals(
                listOf(f.base.item(2).conversation.id),
                vm.state.value.forwarding!!.selectedIds,
            )
            vm.onEvent(ChatThreadEvent.ForwardConfirmed)
            runCurrent()
            assertNull(vm.state.value.forwarding)
            assertEquals(retained, f.insertions)
        }
    }

    @Test
    fun backgroundCancellationRetainsAnUncertainBatchForTheSameRetry() = runTest {
        withThread { f, vm ->
            vm.onEvent(ChatThreadEvent.Entered())
            runCurrent()
            open(vm)
            val entered = CompletableDeferred<Unit>()
            f.afterInsert = {
                entered.complete(Unit)
                awaitCancellation()
            }
            vm.onEvent(ChatThreadEvent.ForwardTargetSelected(f.base.item(2).conversation.id))
            vm.onEvent(ChatThreadEvent.ForwardConfirmed)
            runCurrent()
            assertTrue(entered.isCompleted)
            val retained = f.insertions.toList()
            vm.onEvent(ChatThreadEvent.Stopped)
            runCurrent()
            assertFalse(vm.state.value.forwarding!!.busy)
            f.afterInsert = {}
            vm.onEvent(ChatThreadEvent.Entered())
            vm.onEvent(ChatThreadEvent.ForwardConfirmed)
            runCurrent()
            assertNull(vm.state.value.forwarding)
            assertEquals(retained, f.insertions)
        }
    }

    @Test
    fun accountLossCancelsPendingPreparationAndClearsRecipientNames() = runTest {
        withThread { f, vm ->
            vm.onEvent(ChatThreadEvent.Entered())
            runCurrent()
            open(vm)
            f.beforeQuote = { awaitCancellation() }
            vm.onEvent(ChatThreadEvent.ForwardTargetSelected(f.base.item(2).conversation.id))
            vm.onEvent(ChatThreadEvent.ForwardConfirmed)
            runCurrent()
            f.base.session.value = null
            runCurrent()
            assertNull(vm.state.value.forwarding)
            assertTrue(f.insertions.isEmpty())
        }
    }

    private suspend fun TestScope.open(vm: ChatThreadViewModel) {
        vm.state.value.rows.forEach { vm.onEvent(ChatThreadEvent.MessageSelected(it.key)) }
        runCurrent()
        assertTrue(vm.state.value.canForwardSelection)
        vm.onEvent(ChatThreadEvent.ForwardSelected)
        runCurrent()
        assertNotNull(vm.state.value.forwarding)
        assertFalse(vm.state.value.forwarding!!.loading)
    }

    private suspend fun TestScope.withThread(
        block: suspend TestScope.(ChatThreadFixture, ChatThreadViewModel) -> Unit
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = ChatThreadFixture()
        f.base.records.value = (1..3).map(f.base::item)
        f.historyRows.value =
            listOf(
                f.original(),
                f.original()
                    .copy(
                        primaryEventId = "\$second",
                        eventIds = listOf("\$second"),
                        timestamp = f.original().timestamp + 1,
                        text = "Second message",
                    ),
            )
        val vm = f.model()
        val store = ViewModelStore().also { it.put("thread", vm) }
        val effects = backgroundScope.launch { vm.effects.collect {} }
        try {
            block(f, vm)
        } finally {
            store.clear()
            effects.cancel()
            runCurrent()
            assertEquals(0, f.activeQuotes)
            assertEquals(0, f.base.observers)
            Dispatchers.resetMain()
        }
    }
}
