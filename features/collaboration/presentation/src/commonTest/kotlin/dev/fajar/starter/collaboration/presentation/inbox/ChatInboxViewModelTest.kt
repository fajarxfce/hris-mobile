@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.presentation.inbox

import androidx.lifecycle.ViewModelStore
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*

class ChatInboxViewModelTest {
    @Test
    fun duplicateEntryLoadsOnceAndStoppingKeepsOwnedLocalObservation() = runTest {
        withModel { f, vm, owner ->
            f.records.value = listOf(f.item(1), f.item(2))
            f.readSummaries = { AppResult.Failed(f.network) }
            vm.onEvent(ChatInboxEvent.Entered)
            vm.onEvent(ChatInboxEvent.Entered)
            runCurrent()
            assertEquals(1, f.calls.size)
            assertEquals(1, f.observers)
            assertEquals(2, vm.state.value.page!!.items.size)
            assertEquals(f.network, vm.state.value.refreshFailure)
            vm.onEvent(ChatInboxEvent.Stopped)
            runCurrent()
            assertEquals(1, f.observers)
            f.session.value = null
            runCurrent()
            assertNull(vm.state.value.page)
            assertTrue(vm.state.value.restricted)
            owner.clear()
            runCurrent()
            assertEquals(0, f.observers)
        }
    }

    @Test
    fun failedManualRefreshOnFirstPageKeepsSavedRowsAndRetryAvailable() = runTest {
        withModel { f, vm, _ ->
            f.records.value = listOf(f.item(1))
            vm.onEvent(ChatInboxEvent.Entered)
            runCurrent()
            f.readSummaries = { AppResult.Failed(f.network) }
            vm.onEvent(ChatInboxEvent.RefreshRequested)
            runCurrent()
            assertEquals(listOf(f.item(1)), vm.state.value.page!!.items)
            assertFalse(vm.state.value.loading)
            assertFalse(vm.state.value.refreshing)
            assertEquals(f.network, vm.state.value.refreshFailure)
            vm.onEvent(ChatInboxEvent.RefreshRequested)
            runCurrent()
            assertEquals(3, f.calls.size)
        }
    }

    @Test
    fun expiredAuthorityStillShowsSavedConversationsWithoutNetwork() = runTest {
        withModel { f, vm, _ ->
            f.records.value = listOf(f.item(1))
            f.now += 365.days
            vm.onEvent(ChatInboxEvent.Entered)
            runCurrent()
            assertEquals(listOf(f.item(1)), vm.state.value.page!!.items)
            assertNotNull(vm.state.value.refreshFailure)
            assertEquals(0, f.calls.size)
        }
    }

    @Test
    fun stoppingCancelsRefreshAndLateUncooperativeResultCannotPublish() = runTest {
        withModel { f, vm, _ ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.records.value = listOf(f.item(1))
            f.readSummaries = {
                withContext(NonCancellable) {
                    entered.complete(Unit)
                    release.await()
                    AppResult.Success(CollaborationPage(listOf(f.item(2)), "late"))
                }
            }
            vm.onEvent(ChatInboxEvent.Entered)
            runCurrent()
            entered.await()
            vm.onEvent(ChatInboxEvent.Stopped)
            runCurrent()
            assertFalse(vm.state.value.refreshing)
            release.complete(Unit)
            runCurrent()
            assertNull(vm.state.value.remoteNext)
            assertEquals(listOf(f.item(1)), vm.state.value.page!!.items)
        }
    }

    @Test
    fun localPagingAndSelectionUseOnlyTheCurrentOwnedPage() = runTest {
        withModel { f, vm, _ ->
            f.records.value = (1..45).map(f::item)
            val effects = mutableListOf<ChatInboxEffect>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                vm.effects.collect(effects::add)
            }
            vm.onEvent(ChatInboxEvent.Entered)
            runCurrent()
            vm.onEvent(ChatInboxEvent.NextRequested)
            runCurrent()
            assertEquals("30", vm.state.value.after)
            assertEquals(15, vm.state.value.page!!.items.size)
            vm.onEvent(ChatInboxEvent.ConversationSelected(f.item(1).conversation.id))
            runCurrent()
            assertEquals(1, effects.size)
            vm.onEvent(ChatInboxEvent.PreviousRequested)
            runCurrent()
            assertNull(vm.state.value.after)
            assertEquals(30, vm.state.value.page!!.items.size)
            vm.onEvent(ChatInboxEvent.ConversationSelected(f.item(1).conversation.id))
            runCurrent()
            assertEquals(
                ChatInboxEffect.OpenConversation(f.item(1).conversation.id),
                effects.last(),
            )
        }
    }

    @Test
    fun nextAcquisitionAdvancesAfterItsLocalPublicationWithoutAnotherClick() = runTest {
        withModel { f, vm, _ ->
            f.readSummaries = { after ->
                if (after == null)
                    AppResult.Success(CollaborationPage((1..30).map(f::item), "remote-next"))
                else AppResult.Success(CollaborationPage((31..60).map(f::item), null))
            }
            vm.onEvent(ChatInboxEvent.Entered)
            runCurrent()
            assertEquals("remote-next", vm.state.value.remoteNext)
            assertNull(vm.state.value.page!!.nextCursor)
            vm.onEvent(ChatInboxEvent.NextRequested)
            runCurrent()
            assertEquals(listOf(null, "remote-next"), f.calls)
            assertEquals((31..60).map(f::item), vm.state.value.page!!.items)
            assertEquals("30", vm.state.value.after)
            assertFalse(vm.state.value.hasMore)
            vm.onEvent(ChatInboxEvent.NextRequested)
            runCurrent()
            assertEquals(2, f.calls.size)
        }
    }

    @Test
    fun receivedCompanyDenialClearsTheListAndBlocksNavigation() = runTest {
        withModel { f, vm, _ ->
            f.records.value = listOf(f.item(1))
            f.readSummaries = { AppResult.Failed(f.denied) }
            val effects = mutableListOf<ChatInboxEffect>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                vm.effects.collect(effects::add)
            }
            vm.onEvent(ChatInboxEvent.Entered)
            runCurrent()
            assertNull(vm.state.value.page)
            assertEquals(f.denied, vm.state.value.failure)
            vm.onEvent(ChatInboxEvent.NewChatRequested)
            vm.onEvent(ChatInboxEvent.ConversationSelected(f.item(1).conversation.id))
            runCurrent()
            assertTrue(effects.isEmpty())
        }
    }

    @Test
    fun admittedRefreshReopensCompletedStorageSubscription() = runTest {
        withModel { f, vm, _ ->
            f.completeObservation = true
            f.readSummaries = { AppResult.Failed(f.network) }
            vm.onEvent(ChatInboxEvent.Entered)
            runCurrent()
            assertEquals(f.network, vm.state.value.failure)
            assertEquals(0, f.observers)
            f.completeObservation = false
            f.readSummaries = { AppResult.Success(CollaborationPage(listOf(f.item(1)), null)) }
            vm.onEvent(ChatInboxEvent.RefreshRequested)
            runCurrent()
            assertNull(vm.state.value.failure)
            assertEquals(listOf(f.item(1)), vm.state.value.page!!.items)
            assertEquals(1, f.observers)
            f.records.value += f.item(2)
            runCurrent()
            assertEquals(2, vm.state.value.page!!.items.size)
        }
    }

    private suspend fun TestScope.withModel(
        block: suspend TestScope.(ChatInboxFixture, ChatInboxViewModel, ViewModelStore) -> Unit
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val fixture = ChatInboxFixture()
            val model = fixture.model()
            owner.put("chat", model)
            block(fixture, model, owner)
        } finally {
            owner.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }
}
