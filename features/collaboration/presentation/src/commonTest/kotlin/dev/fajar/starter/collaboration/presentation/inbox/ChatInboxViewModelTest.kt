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
    fun reopeningAndUnchangedRefreshNeverPublishBlankPreviewsWhileLocalReadsArePending() = runTest {
        withModel { f, vm, _ ->
            val id = f.item(1).conversation.id
            val draft = CollaborationDraft(id, chatId(9000), "Retained draft", f.now)
            f.records.value = listOf(f.item(1))
            f.content.slots.value = mapOf(id to draft)
            vm.onEvent(ChatInboxEvent.Entered)
            runCurrent()
            val expected = vm.state.value.previews[id]
            assertEquals(draft, expected!!.draft)
            val published = mutableListOf<CollaborationInboxPreview?>()
            val observation =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    vm.state.collect { published.add(it.previews[id]) }
                }
            f.content.historyReadGate = CompletableDeferred()
            vm.onEvent(ChatInboxEvent.Stopped)
            runCurrent()
            vm.onEvent(ChatInboxEvent.Entered)
            runCurrent()
            assertEquals(expected, vm.state.value.previews[id])
            vm.onEvent(ChatInboxEvent.RefreshRequested)
            runCurrent()
            assertEquals(expected, vm.state.value.previews[id])
            f.content.historyReadGate!!.complete(Unit)
            runCurrent()
            assertTrue(published.isNotEmpty() && published.all { it == expected })
            f.content.slots.value = emptyMap()
            runCurrent()
            assertNull(vm.state.value.previews[id]!!.draft)
            observation.cancel()
        }
    }

    @Test
    fun denialDuringPendingPreviewReopenClearsRetainedPrivateContentImmediately() = runTest {
        withModel { f, vm, _ ->
            val id = f.item(1).conversation.id
            f.records.value = listOf(f.item(1))
            f.content.slots.value =
                mapOf(id to CollaborationDraft(id, chatId(9000), "Private", f.now))
            vm.onEvent(ChatInboxEvent.Entered)
            runCurrent()
            assertNotNull(vm.state.value.previews[id])
            f.content.historyReadGate = CompletableDeferred()
            vm.onEvent(ChatInboxEvent.Stopped)
            runCurrent()
            vm.onEvent(ChatInboxEvent.Entered)
            runCurrent()
            f.gate.value = CollaborationListAdmission("denied", f.denied)
            runCurrent()
            assertTrue(vm.state.value.previews.isEmpty())
            assertNull(vm.state.value.page)
            f.content.historyReadGate!!.complete(Unit)
            runCurrent()
            assertTrue(vm.state.value.previews.isEmpty())
        }
    }

    @Test
    fun explicitRetryReopensFailedLocalContentEvenWhenTheNetworkRemainsOffline() = runTest {
        withModel { f, vm, _ ->
            val id = f.item(1).conversation.id
            f.records.value = listOf(f.item(1))
            f.sync.failure = f.network
            f.content.historyFailureOnOpen = Failure(FailureKind.Storage, "Fixture unavailable")
            vm.onEvent(ChatInboxEvent.Entered)
            runCurrent()
            assertNotNull(vm.state.value.previewFailure)
            assertTrue(vm.state.value.previews.isEmpty())
            f.content.historyFailureOnOpen = null
            vm.onEvent(ChatInboxEvent.RefreshRequested)
            runCurrent()
            assertNull(vm.state.value.previewFailure)
            assertTrue(id in vm.state.value.previews)
            assertEquals(f.network, vm.state.value.refreshFailure)
            assertEquals(2, f.calls.size)
        }
    }

    @Test
    fun savedPreviewsUpdateOfflineAndStopWhenHiddenWhileAdmissionRemainsObserved() = runTest {
        withModel { f, vm, owner ->
            val item = f.item(1)
            val id = item.conversation.id
            val message =
                CollaborationHistoryMessage(
                    "@peer:example.test",
                    null,
                    "\$latest",
                    1,
                    "a".repeat(64),
                    "Saved local message",
                    listOf("\$latest"),
                    false,
                )
            val draft = CollaborationDraft(id, chatId(9000), "Unsent reply", f.now)
            f.records.value = listOf(item)
            f.content.messages.value = mapOf(id to message)
            f.content.slots.value = mapOf(id to draft)
            f.now += 365.days
            vm.onEvent(ChatInboxEvent.Entered)
            runCurrent()
            assertEquals(CollaborationInboxPreview(message, draft), vm.state.value.previews[id])
            assertEquals(0, f.calls.size)
            assertEquals(1, f.content.historyObservers)
            assertEquals(1, f.content.draftObservers)
            f.content.slots.value = emptyMap()
            runCurrent()
            assertNull(vm.state.value.previews[id]!!.draft)
            vm.onEvent(ChatInboxEvent.Stopped)
            runCurrent()
            assertEquals(0, f.content.historyObservers)
            assertEquals(0, f.content.draftObservers)
            assertEquals(1, f.observers)
            vm.onEvent(ChatInboxEvent.Entered)
            runCurrent()
            assertEquals(message, vm.state.value.previews[id]!!.message)
            owner.clear()
            runCurrent()
            assertEquals(0, f.content.historyObservers)
            assertEquals(0, f.content.draftObservers)
        }
    }

    @Test
    fun detailObserversBelongOnlyToTheCurrentPageAndCloseOnConfirmedDenial() = runTest {
        withModel { f, vm, _ ->
            f.records.value = (1..45).map(f::item)
            vm.onEvent(ChatInboxEvent.Entered)
            runCurrent()
            assertEquals(30, f.content.historyObservers)
            assertEquals(30, f.content.draftObservers)
            vm.onEvent(ChatInboxEvent.NextRequested)
            runCurrent()
            assertEquals(15, f.content.historyObservers)
            assertEquals(15, f.content.draftObservers)
            assertEquals(
                (31..45).map { f.item(it).conversation.id }.toSet(),
                vm.state.value.previews.keys,
            )
            f.gate.value = CollaborationListAdmission("denied", f.denied)
            runCurrent()
            assertNull(vm.state.value.page)
            assertTrue(vm.state.value.previews.isEmpty())
            assertEquals(0, f.content.historyObservers)
            assertEquals(0, f.content.draftObservers)
        }
    }

    @Test
    fun duplicateEntryLoadsOnceAndStoppingKeepsOwnedLocalObservation() = runTest {
        withModel { f, vm, owner ->
            f.records.value = listOf(f.item(1), f.item(2))
            f.sync.failure = f.network
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
            f.sync.failure = f.network
            vm.onEvent(ChatInboxEvent.RefreshRequested)
            runCurrent()
            assertEquals(listOf(f.item(1)), vm.state.value.page!!.items)
            assertFalse(vm.state.value.loading)
            assertFalse(vm.state.value.refreshing)
            assertEquals(f.network, vm.state.value.refreshFailure)
            vm.onEvent(ChatInboxEvent.RefreshRequested)
            runCurrent()
            assertEquals(4, f.calls.size)
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
            f.sync.bootstrapRead = {
                withContext(NonCancellable) {
                    entered.complete(Unit)
                    release.await()
                    AppResult.Success(
                        CollaborationSummaryBootstrap(
                            listOf(
                                CollaborationSummaryFact(f.item(2).conversation.id, 1, f.item(2))
                            ),
                            null,
                            "changes-late",
                            false,
                            f.now,
                        )
                    )
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
            assertFalse(vm.state.value.hasMore)
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
    fun noLocalNextNeverAcquiresOrAdvancesWhenUnrelatedRowsArriveLater() = runTest {
        withModel { f, vm, _ ->
            f.records.value = (1..30).map(f::item)
            vm.onEvent(ChatInboxEvent.Entered)
            runCurrent()
            val original = f.checkpoint.value
            val calls = f.calls.toList()
            vm.onEvent(ChatInboxEvent.NextRequested)
            runCurrent()
            assertEquals(original, f.checkpoint.value)
            assertFalse(vm.state.value.hasMore)
            assertNull(vm.state.value.after)
            f.records.value += (31..60).map(f::item)
            f.checkpoint.value = original!!.copy(revision = original.revision + 1, count = 60)
            runCurrent()
            assertNull(vm.state.value.after)
            assertEquals((1..30).map(f::item), vm.state.value.page!!.items)
            vm.onEvent(ChatInboxEvent.NextRequested)
            runCurrent()
            assertEquals((31..60).map(f::item), vm.state.value.page!!.items)
            assertEquals(calls, f.calls)
        }
    }

    @Test
    fun localPagingRemainsAvailableDuringPendingNetworkAndNeverUsesRemoteCursor() = runTest {
        withModel { f, vm, _ ->
            f.records.value = (1..60).map(f::item)
            f.sync.bootstrapRead = { awaitCancellation() }
            vm.onEvent(ChatInboxEvent.Entered)
            runCurrent()
            assertTrue(vm.state.value.refreshing)
            assertTrue(vm.state.value.hasMore)
            vm.onEvent(ChatInboxEvent.NextRequested)
            runCurrent()
            assertEquals((31..60).map(f::item), vm.state.value.page!!.items)
            assertEquals("30", vm.state.value.after)
            vm.onEvent(ChatInboxEvent.PreviousRequested)
            runCurrent()
            assertNull(vm.state.value.after)
            assertEquals((1..30).map(f::item), vm.state.value.page!!.items)
            assertEquals(listOf<String?>(null), f.calls)
        }
    }

    @Test
    fun unchangedDeltaKeepsDetailStreamsAndReentryUsesRetainedCursor() = runTest {
        withModel { f, vm, _ ->
            f.records.value = listOf(f.item(1))
            vm.onEvent(ChatInboxEvent.Entered)
            runCurrent()
            assertEquals(listOf(null, "changes-0"), f.calls)
            val starts = f.content.historyStarts
            val previews = vm.state.value.previews
            advanceTimeBy(30_000)
            runCurrent()
            assertEquals(listOf(null, "changes-0", "changes-0"), f.calls)
            assertEquals(starts, f.content.historyStarts)
            assertEquals(previews, vm.state.value.previews)
            vm.onEvent(ChatInboxEvent.Stopped)
            runCurrent()
            advanceTimeBy(120_000)
            runCurrent()
            assertEquals(3, f.calls.size)
            vm.onEvent(ChatInboxEvent.Entered)
            runCurrent()
            assertEquals(listOf(null, "changes-0", "changes-0", "changes-0"), f.calls)
            assertEquals(listOf(f.item(1)), vm.state.value.page!!.items)
        }
    }

    @Test
    fun pendingPublicationContinuesAfterDelayAndHideCancelsThatContinuation() = runTest {
        withModel { f, vm, _ ->
            f.records.value = listOf(f.item(1))
            f.sync.pending = true
            vm.onEvent(ChatInboxEvent.Entered)
            runCurrent()
            assertEquals(2, f.calls.size)
            assertNull(f.checkpoint.value)
            assertEquals(listOf(f.item(1)), vm.state.value.page!!.items)
            advanceTimeBy(4_999)
            runCurrent()
            assertEquals(2, f.calls.size)
            f.sync.pending = false
            advanceTimeBy(1)
            runCurrent()
            assertNotNull(f.checkpoint.value)
            assertEquals(listOf(null, "changes-0", "changes-0"), f.calls)
            vm.onEvent(ChatInboxEvent.Stopped)
            runCurrent()
            advanceTimeBy(120_000)
            runCurrent()
            assertEquals(3, f.calls.size)
        }
    }

    @Test
    fun interactivePreemptionRetriesQuietlyAndDisposalCancelsFurtherWork() = runTest {
        withModel { f, vm, owner ->
            f.records.value = listOf(f.item(1))
            f.sync.commitWins = false
            vm.onEvent(ChatInboxEvent.Entered)
            runCurrent()
            assertEquals(1, f.calls.size)
            assertNull(vm.state.value.refreshFailure)
            assertFalse(vm.state.value.loading)
            assertEquals(listOf(f.item(1)), vm.state.value.page!!.items)
            f.sync.commitWins = true
            advanceTimeBy(2_000)
            runCurrent()
            assertNotNull(f.checkpoint.value)
            assertEquals(3, f.calls.size)
            owner.clear()
            runCurrent()
            advanceTimeBy(120_000)
            runCurrent()
            assertEquals(3, f.calls.size)
            assertEquals(0, f.observers)
        }
    }

    @Test
    fun receivedCompanyDenialClearsTheListAndBlocksNavigation() = runTest {
        withModel { f, vm, _ ->
            f.records.value = listOf(f.item(1))
            f.sync.failure = f.denied
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
            f.sync.failure = f.network
            vm.onEvent(ChatInboxEvent.Entered)
            runCurrent()
            assertEquals(f.network, vm.state.value.failure)
            assertEquals(0, f.observers)
            f.completeObservation = false
            f.sync.failure = null
            f.records.value = listOf(f.item(1))
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

    @Test
    fun cachedInboxReconnectsWithoutManualRefreshAndStopsWithTheScreen() = runTest {
        withModel { f, vm, _ ->
            f.records.value = listOf(f.item(1))
            f.sync.failure = f.network
            vm.onEvent(ChatInboxEvent.Entered)
            runCurrent()
            assertEquals(listOf(f.item(1)), vm.state.value.page!!.items)
            assertEquals(1, f.calls.size)
            advanceTimeBy(1_999)
            runCurrent()
            assertEquals(1, f.calls.size)
            f.sync.failure = null
            f.records.value = listOf(f.item(1))
            advanceTimeBy(1)
            runCurrent()
            assertEquals(3, f.calls.size)
            assertNull(vm.state.value.refreshFailure)
            vm.onEvent(ChatInboxEvent.Stopped)
            runCurrent()
            advanceTimeBy(120_000)
            runCurrent()
            assertEquals(3, f.calls.size)
        }
    }

    @Test
    fun heldRepliesAreCountedByConversationAndOnlyOrphansNeedASeparateEntry() = runTest {
        withModel { f, vm, store ->
            f.records.value = listOf(f.item(1))
            val known = f.replies.input(f)
            val orphan = f.replies.input(f, 2, f.item(2).conversation.id)
            val queued = f.replies.input(f, 3).copy(review = null)
            f.replies.inputs.value = listOf(known, orphan, queued)
            vm.onEvent(ChatInboxEvent.Entered)
            runCurrent()
            assertEquals(1, vm.state.value.unsentCounts[f.item(1).conversation.id])
            assertEquals(1, vm.state.value.orphanedUnsentCount)
            f.replies.inputs.value = listOf(queued)
            runCurrent()
            assertTrue(vm.state.value.unsentCounts.isEmpty())
            assertEquals(0, vm.state.value.orphanedUnsentCount)
            store.clear()
            runCurrent()
            assertEquals(0, f.replies.inputs.subscriptionCount.value)
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
