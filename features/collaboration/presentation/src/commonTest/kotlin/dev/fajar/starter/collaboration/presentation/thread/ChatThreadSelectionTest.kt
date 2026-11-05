@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.presentation.thread

import androidx.lifecycle.ViewModelStore
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.presentation.inbox.chatId
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*

class ChatThreadSelectionTest {
    @Test
    fun sentCommandRemainsReadableAndDeletableOfflineBeforeHistoryIsImported() = runTest {
        withThread { f, vm ->
            f.retainedHistoryRoom = false
            val id = chatId(850)
            val owner = f.setup.owner
            val command =
                CollaborationMessageRecord(
                    CollaborationMessageIntent(
                        id,
                        CollaborationMessageOwner(owner.audience, owner.accountId, owner.companyId),
                        f.conversationId,
                        f.base.clock.now(),
                        "Sent before history import",
                    ),
                    2,
                    CollaborationMessageState.Sent(
                        f.source,
                        "tx-offline",
                        "\$offline",
                        CollaborationMessageOrigin(f.self.matrixUserId, "a".repeat(64)),
                    ),
                )
            f.records.value = listOf(command)
            vm.onEvent(ChatThreadEvent.Entered)
            runCurrent()
            assertEquals("Sent before history import", vm.state.value.rows.single().text)
            assertEquals(0, f.timelineOpens)
            selectAll(vm)
            vm.onEvent(ChatThreadEvent.DeleteSelected)
            vm.onEvent(ChatThreadEvent.DeletionConfirmed)
            runCurrent()
            assertTrue(vm.state.value.rows.isEmpty())
            assertEquals(listOf(command), f.records.value)
            assertNull(vm.state.value.deletionDialog)
        }
    }

    @Test
    fun hiddenLiveEchoNeverReappearsBeforeOrAfterHistoryImport() = runTest {
        withThread { f, vm ->
            f.online()
            val original = f.original()
            val reference = CollaborationMessageReference(original.sender, original.primaryEventId)
            f.hiddenMessages.value = setOf(reference)
            val item =
                CollaborationTimelineItem(
                    "live",
                    original.primaryEventId,
                    null,
                    null,
                    original.sender,
                    original.timestamp,
                    false,
                    CollaborationMessageKind.TEXT,
                    original.text,
                    false,
                    CollaborationMessageDelivery.SENT,
                )
            f.updates.value = AppResult.Success(listOf(item))
            vm.onEvent(ChatThreadEvent.Entered)
            runCurrent()
            assertTrue(vm.state.value.rows.isEmpty())
            assertNull(vm.state.value.localFailure)
            f.historyRows.value = listOf(original)
            runCurrent()
            assertTrue(vm.state.value.rows.isEmpty())
            f.updates.value =
                AppResult.Success(listOf(item.copy(body = "Edited body", edited = true)))
            runCurrent()
            assertTrue(vm.state.value.rows.isEmpty())
        }
    }

    @Test
    fun multiSelectionAndBackRetainTheDraftAndNeverDeleteWithoutConfirmation() = runTest {
        withThread { f, vm ->
            f.historyRows.value =
                listOf(
                    f.original(),
                    f.original().copy(primaryEventId = "\$second", eventIds = listOf("\$second")),
                )
            vm.onEvent(ChatThreadEvent.Entered)
            runCurrent()
            vm.onEvent(ChatThreadEvent.DraftChanged("Keep this draft"))
            advanceTimeBy(250)
            runCurrent()
            vm.state.value.rows.forEach { vm.onEvent(ChatThreadEvent.MessageSelected(it.key)) }
            runCurrent()
            assertEquals(2, vm.state.value.selectedMessages.size)
            assertFalse(vm.state.value.canCompose)
            vm.onEvent(ChatThreadEvent.DeleteSelected)
            runCurrent()
            assertEquals(2, vm.state.value.deletionDialog?.count)
            vm.onEvent(ChatThreadEvent.BackRequested)
            runCurrent()
            assertNull(vm.state.value.deletionDialog)
            assertTrue(vm.state.value.selecting)
            vm.onEvent(ChatThreadEvent.BackRequested)
            runCurrent()
            assertFalse(vm.state.value.selecting)
            assertEquals("Keep this draft", vm.state.value.draft)
            assertTrue(f.hiddenBatches.isEmpty())
        }
    }

    @Test
    fun duplicateConfirmationsDeleteOneExactBatchAndClearSelection() = runTest {
        withThread { f, vm ->
            f.historyRows.value = listOf(f.original())
            vm.onEvent(ChatThreadEvent.Entered)
            runCurrent()
            selectAll(vm)
            vm.onEvent(ChatThreadEvent.DeleteSelected)
            runCurrent()
            val release = CompletableDeferred<Unit>()
            f.beforeHide = { release.await() }
            vm.onEvent(ChatThreadEvent.DeletionConfirmed)
            vm.onEvent(ChatThreadEvent.DeletionConfirmed)
            runCurrent()
            assertTrue(vm.state.value.deletionDialog!!.busy)
            assertEquals(1, f.hiddenBatches.size)
            release.complete(Unit)
            runCurrent()
            assertTrue(vm.state.value.rows.isEmpty())
            assertFalse(vm.state.value.selecting)
            assertNull(vm.state.value.deletionDialog)
            assertEquals(1, f.historyRows.value.size, "Original evidence remains retained")
        }
    }

    @Test
    fun lostCommitResponseRetriesCapturedSelectionAfterRowsDisappear() = runTest {
        withThread { f, vm ->
            f.historyRows.value = listOf(f.original())
            f.hideFailure = Failure(FailureKind.Storage, "Lost local response")
            vm.onEvent(ChatThreadEvent.Entered)
            runCurrent()
            selectAll(vm)
            vm.onEvent(ChatThreadEvent.DeleteSelected)
            vm.onEvent(ChatThreadEvent.DeletionConfirmed)
            runCurrent()
            assertTrue(vm.state.value.rows.isEmpty())
            assertNotNull(vm.state.value.deletionDialog?.failure)
            f.hideFailure = null
            vm.onEvent(ChatThreadEvent.DeletionConfirmed)
            runCurrent()
            assertEquals(2, f.hiddenBatches.size)
            assertEquals(f.hiddenBatches[0], f.hiddenBatches[1])
            assertNull(vm.state.value.deletionDialog)
        }
    }

    @Test
    fun lateCommittedDeletionCannotPublishIntoAReplacedAccount() = runTest {
        withThread { f, vm ->
            f.historyRows.value = listOf(f.original())
            val release = CompletableDeferred<Unit>()
            f.afterHide = { withContext(NonCancellable) { release.await() } }
            vm.onEvent(ChatThreadEvent.Entered)
            runCurrent()
            selectAll(vm)
            vm.onEvent(ChatThreadEvent.DeleteSelected)
            vm.onEvent(ChatThreadEvent.DeletionConfirmed)
            runCurrent()
            f.base.session.value = null
            runCurrent()
            release.complete(Unit)
            runCurrent()
            assertTrue(vm.state.value.rows.isEmpty())
            assertTrue(vm.state.value.selectedMessages.isEmpty())
            assertNull(vm.state.value.deletionDialog)
            assertNotNull(vm.state.value.localFailure)
            assertEquals(1, f.hiddenMessages.value.size)
        }
    }

    @Test
    fun copiedTextIsChronologicalAndQueuedEffectsRequireFreshOwnership() = runTest {
        withThread { f, vm ->
            f.historyRows.value =
                listOf(
                    f.original().copy(timestamp = 2000, text = "Newer"),
                    f.original()
                        .copy(
                            primaryEventId = "\$older",
                            eventIds = listOf("\$older"),
                            timestamp = 1000,
                            text = "Older",
                        ),
                )
            vm.onEvent(ChatThreadEvent.Entered)
            runCurrent()
            selectAll(vm)
            val effect = async { vm.effects.first { it is ChatThreadEffect.CopyMessages } }
            vm.onEvent(ChatThreadEvent.CopySelected)
            runCurrent()
            val copy = assertIs<ChatThreadEffect.CopyMessages>(effect.await())
            assertEquals("Older\n\nNewer", copy.text)
            assertTrue(vm.confirmCopy(copy))
            f.base.session.value = null
            assertFalse(vm.confirmCopy(copy))
            runCurrent()
            assertTrue(vm.state.value.selectedMessages.isEmpty())
        }
    }

    @Test
    fun selectionIsBoundedAndRetainedAcrossLocalPagination() = runTest {
        withThread { f, vm ->
            f.historyRows.value =
                (1..51).map {
                    f.original()
                        .copy(primaryEventId = "\$event-$it", eventIds = listOf("\$event-$it"))
                }
            vm.onEvent(ChatThreadEvent.Entered)
            runCurrent()
            selectAll(vm)
            assertEquals(50, vm.state.value.selectedMessages.size)
            assertTrue(vm.state.value.selectionLimitReached)
            val selected = vm.state.value.selectedMessages
            f.historyRows.value = emptyList()
            runCurrent()
            assertEquals(selected, vm.state.value.selectedMessages)
            vm.onEvent(ChatThreadEvent.SelectionCleared)
            runCurrent()
            assertFalse(vm.state.value.selecting)
        }
    }

    private suspend fun TestScope.selectAll(vm: ChatThreadViewModel) {
        vm.state.value.rows.forEach { vm.onEvent(ChatThreadEvent.MessageSelected(it.key)) }
        runCurrent()
    }

    private suspend fun TestScope.withThread(
        block: suspend TestScope.(ChatThreadFixture, ChatThreadViewModel) -> Unit
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = ChatThreadFixture()
        val vm = f.model()
        val store = ViewModelStore().also { it.put("thread", vm) }
        try {
            block(f, vm)
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }
}
