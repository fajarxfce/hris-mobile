@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.presentation.thread

import androidx.lifecycle.ViewModelStore
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*

class ChatLocalEntryTest {
    @Test
    fun ownedHistoryIsPublishedBeforeStartingAnyNetworkWork() = runTest {
        withModel { f, vm, _, _ ->
            f.online()
            f.historyRows.value = listOf(f.original())
            val local = CompletableDeferred<Unit>()
            val remote = CompletableDeferred<Unit>()
            f.beforePage = { _, _ -> local.await() }
            f.setup.read = {
                remote.await()
                AppResult.Success(f.setup.active)
            }
            vm.onEvent(ChatThreadEvent.Entered)
            runCurrent()
            assertEquals(0, f.setup.reads)
            local.complete(Unit)
            runCurrent()
            assertEquals("Saved conversation", vm.state.value.rows.single().text)
            assertFalse(vm.state.value.loading)
            assertTrue(vm.state.value.canCompose)
            assertEquals(1, f.setup.reads)
            remote.complete(Unit)
            runCurrent()
            assertEquals(ChatThreadConnection.CONNECTED, vm.state.value.connection)
        }
    }

    @Test
    fun retryingLocalStorageDoesNotWaitForRemoteAdmissionWhileNoDenialIsKnown() = runTest {
        withModel { f, vm, _, _ ->
            f.online()
            f.historyRows.value = listOf(f.original())
            val storage = Failure(FailureKind.Storage, "Temporary local read failure")
            f.historyPages[null to null] = AppResult.Failed(storage)
            val remote = CompletableDeferred<Unit>()
            f.setup.read = {
                remote.await()
                AppResult.Success(f.setup.active)
            }
            vm.onEvent(ChatThreadEvent.Entered)
            runCurrent()
            assertEquals(storage, vm.state.value.localFailure)
            assertEquals(0, f.setup.reads)
            f.historyPages.clear()
            vm.onEvent(ChatThreadEvent.RefreshRequested)
            runCurrent()
            assertEquals("Saved conversation", vm.state.value.rows.single().text)
            assertNull(vm.state.value.localFailure)
            assertFalse(vm.state.value.loading)
            assertEquals(1, f.setup.reads)
            remote.complete(Unit)
            runCurrent()
        }
    }

    @Test
    fun offlineReopenKeepsHistoryAndReconnectsWithoutARefreshAction() = runTest {
        withModel { f, vm, store, _ ->
            f.online()
            f.historyRows.value = listOf(f.original())
            f.setup.read = { AppResult.Failed(f.base.network) }
            vm.onEvent(ChatThreadEvent.Entered)
            runCurrent()
            assertFalse(vm.state.value.loading)
            assertEquals(1, f.setup.reads)
            advanceTimeBy(1_999)
            runCurrent()
            assertEquals(1, f.setup.reads)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(2, f.setup.reads)
            assertEquals("Saved conversation", vm.state.value.rows.single().text)
            f.setup.read = { AppResult.Success(f.setup.active) }
            advanceTimeBy(4_000)
            runCurrent()
            // The successful timeline admission also checks current device setup.
            assertEquals(4, f.setup.reads)
            assertEquals(1, f.timelineOpens)
            assertEquals(ChatThreadConnection.CONNECTED, vm.state.value.connection)
            store.clear()
            runCurrent()
            val reopened = f.model()
            store.put("reopened", reopened)
            f.setup.read = { AppResult.Failed(f.base.network) }
            reopened.onEvent(ChatThreadEvent.Entered)
            runCurrent()
            assertEquals("Saved conversation", reopened.state.value.rows.single().text)
            assertFalse(reopened.state.value.loading)
        }
    }

    @Test
    fun stoppingCancelsBackoffAndAHiddenAccountChangeClearsContent() = runTest {
        withModel { f, vm, _, _ ->
            f.online()
            f.historyRows.value = listOf(f.original())
            f.setup.read = { AppResult.Failed(f.base.network) }
            vm.onEvent(ChatThreadEvent.Entered)
            runCurrent()
            vm.onEvent(ChatThreadEvent.Stopped)
            runCurrent()
            advanceTimeBy(120_000)
            runCurrent()
            assertEquals(1, f.setup.reads)
            assertEquals("Saved conversation", vm.state.value.rows.single().text)
            f.base.session.value = null
            runCurrent()
            assertTrue(vm.state.value.rows.isEmpty())
            assertNull(vm.state.value.title)
        }
    }

    @Test
    fun knownDenialCancelsScheduledRetriesWithoutGrantingFreshAccess() = runTest {
        withModel { f, vm, _, _ ->
            f.online()
            f.historyRows.value = listOf(f.original())
            f.setup.read = { AppResult.Failed(f.base.network) }
            vm.onEvent(ChatThreadEvent.Entered)
            runCurrent()
            f.base.gate.value = CollaborationListAdmission("denied", f.base.denied)
            runCurrent()
            advanceTimeBy(120_000)
            runCurrent()
            assertEquals(1, f.setup.reads)
            assertTrue(vm.state.value.rows.isEmpty())
            assertEquals(f.base.denied, vm.state.value.localFailure)
        }
    }

    @Test
    fun timedOutEntryRetainsMessagesAndNeverPublishesAStoppedLateResult() = runTest {
        withModel { f, vm, _, _ ->
            f.online()
            f.historyRows.value = listOf(f.original())
            val release = CompletableDeferred<Unit>()
            f.setup.read = {
                withContext(NonCancellable) { release.await() }
                AppResult.Success(f.setup.active)
            }
            vm.onEvent(ChatThreadEvent.Entered)
            runCurrent()
            advanceTimeBy(30_001)
            runCurrent()
            assertEquals("Saved conversation", vm.state.value.rows.single().text)
            vm.onEvent(ChatThreadEvent.Stopped)
            runCurrent()
            release.complete(Unit)
            runCurrent()
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(1, f.setup.reads)
            assertEquals(0, f.timelineOpens)
            assertEquals(ChatThreadConnection.OFFLINE, vm.state.value.connection)
        }
    }

    @Test
    fun heldNotificationReplyAppearsOnlyInItsConversationAndOpensItsOriginalInput() = runTest {
        withModel { f, vm, _, effects ->
            val held = f.replies.input(f.base)
            val other = f.replies.input(f.base, 2, f.base.item(2).conversation.id)
            val ordinary = f.replies.input(f.base, 3).copy(review = null)
            f.replies.inputs.value = listOf(held, other, ordinary)
            vm.onEvent(ChatThreadEvent.Entered)
            runCurrent()
            assertEquals(listOf(held.id), vm.state.value.unsentReplies.map { it.id })
            assertEquals(held.candidate.text, vm.state.value.unsentReplies.single().text)
            vm.onEvent(ChatThreadEvent.UnsentReplySelected(other.id))
            runCurrent()
            assertTrue(effects.isEmpty())
            vm.onEvent(ChatThreadEvent.UnsentReplySelected(held.id))
            runCurrent()
            assertEquals(listOf<ChatThreadEffect>(ChatThreadEffect.OpenReply(held.id)), effects)
            f.base.session.value = null
            runCurrent()
            assertTrue(vm.state.value.unsentReplies.isEmpty())
        }
    }

    @Test
    fun reconnectBackoffHonorsRateHintsAndNeverRetriesExplicitDenials() {
        val network = Failure(FailureKind.Network, "offline")
        assertEquals(2_000L, chatReconnectDelay(0, network))
        assertEquals(60_000L, chatReconnectDelay(100, network))
        assertEquals(120_000L, chatReconnectDelay(0, network.copy(retryAfterSeconds = 120)))
        assertFalse(chatCanReconnect(network.copy(kind = FailureKind.AccessDenied)))
        assertFalse(chatCanReconnect(network.copy(kind = FailureKind.Unauthorized)))
        assertFalse(chatCanReconnect(network.copy(code = "company_maintenance")))
        assertFalse(
            chatCanReconnect(network.copy(code = "client.collaboration_history_focus_capacity"))
        )
    }

    private suspend fun TestScope.withModel(
        block:
            suspend TestScope.(
                ChatThreadFixture,
                ChatThreadViewModel,
                ViewModelStore,
                MutableList<ChatThreadEffect>,
            ) -> Unit
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val f = ChatThreadFixture()
            val vm = f.model()
            store.put("chat", vm)
            val effects = mutableListOf<ChatThreadEffect>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                vm.effects.collect { effects += it }
            }
            block(f, vm, store, effects)
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }
}
