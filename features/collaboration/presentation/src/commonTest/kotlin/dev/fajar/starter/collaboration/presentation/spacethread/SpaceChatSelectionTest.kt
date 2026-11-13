@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.presentation.spacethread

import androidx.lifecycle.ViewModelStore
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.collaborationSpaceComposerIntent
import dev.fajar.starter.collaboration.presentation.inbox.chatId
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*

class SpaceChatSelectionTest {
    @Test
    fun selectionAcceptsOnlyVisibleTypedRowsAndBackClearsItBeforeLeaving() = runTest {
        withModel { f, vm, _, effects ->
            val original = f.submission()
            f.entries.value = listOf(CollaborationComposerOutboxEntry(original, false))
            vm.onEvent(SpaceChatThreadEvent.Entered)
            runCurrent()
            val identity = vm.state.value.rows.single().identity
            vm.onEvent(
                SpaceChatThreadEvent.MessageSelected(identity.copy(conversationId = chatId(899)))
            )
            runCurrent()
            assertFalse(vm.state.value.selecting)
            vm.onEvent(SpaceChatThreadEvent.MessageSelected(identity))
            runCurrent()
            assertTrue(vm.state.value.selecting)
            assertFalse(vm.state.value.canCompose)
            vm.onEvent(SpaceChatThreadEvent.BackRequested)
            runCurrent()
            assertFalse(vm.state.value.selecting)
            assertTrue(vm.state.value.canCompose)
            assertTrue(effects.isEmpty())
            vm.onEvent(SpaceChatThreadEvent.Stopped)
            vm.onEvent(SpaceChatThreadEvent.MessageSelected(identity))
            runCurrent()
            assertTrue(vm.state.value.selection.isEmpty())
        }
    }

    @Test
    fun selectionIsBoundedAndDeselectingMakesSpaceForAnotherMessage() = runTest {
        withModel { f, vm, _, _ ->
            f.entries.value =
                (1..51).map { CollaborationComposerOutboxEntry(f.submission(it), false) }
            vm.onEvent(SpaceChatThreadEvent.Entered)
            runCurrent()
            val ids = vm.state.value.rows.map { it.identity }
            ids.forEach { vm.onEvent(SpaceChatThreadEvent.MessageSelected(it)) }
            runCurrent()
            assertEquals(50, vm.state.value.selection.size)
            assertTrue(vm.state.value.selectionLimitReached)
            vm.onEvent(SpaceChatThreadEvent.MessageSelected(ids.first()))
            runCurrent()
            assertFalse(vm.state.value.selectionLimitReached)
            vm.onEvent(SpaceChatThreadEvent.MessageSelected(ids.last()))
            runCurrent()
            assertEquals(50, vm.state.value.selection.size)
            assertTrue(ids.last() in vm.state.value.selection)
        }
    }

    @Test
    fun copyingUsesMessageOrderAndConsumesTheConfirmedActionOnce() = runTest {
        withModel { f, vm, _, effects ->
            f.entries.value =
                (1..3).map { CollaborationComposerOutboxEntry(f.submission(it), false) }
            vm.onEvent(SpaceChatThreadEvent.Entered)
            runCurrent()
            vm.state.value.rows.forEach {
                vm.onEvent(SpaceChatThreadEvent.MessageSelected(it.identity))
            }
            vm.onEvent(SpaceChatThreadEvent.CopySelected)
            runCurrent()
            val effect = effects.filterIsInstance<SpaceChatThreadEffect.CopyMessages>().single()
            assertEquals(
                "Retained original 1\n\nRetained original 2\n\nRetained original 3",
                effect.text,
            )
            assertFalse(vm.state.value.selecting)
            assertTrue(vm.confirmCopy(effect))
            assertFalse(vm.confirmCopy(effect))
            assertEquals(0, f.schedules)
        }
    }

    @Test
    fun deliveryStatusMayAdvanceWhileTheCopiedTextAndIdentityStayExact() = runTest {
        withModel { f, vm, _, effects ->
            val original = f.submission()
            f.entries.value = listOf(CollaborationComposerOutboxEntry(original, false))
            vm.onEvent(SpaceChatThreadEvent.Entered)
            runCurrent()
            vm.onEvent(SpaceChatThreadEvent.MessageSelected(vm.state.value.rows.single().identity))
            vm.onEvent(SpaceChatThreadEvent.CopySelected)
            runCurrent()
            val effect = effects.filterIsInstance<SpaceChatThreadEffect.CopyMessages>().single()
            f.archived.value = listOf(f.sent(original))
            runCurrent()
            assertEquals(
                CollaborationMessageDelivery.SENT,
                vm.state.value.rows.single().bubble.delivery,
            )
            assertTrue(vm.confirmCopy(effect))
        }
    }

    @Test
    fun stoppedOrDisposedRoutesCannotFinishALateClipboardConfirmation() = runTest {
        for (dispose in listOf(false, true)) withModel { f, vm, store, effects ->
            f.entries.value = listOf(CollaborationComposerOutboxEntry(f.submission(), false))
            vm.onEvent(SpaceChatThreadEvent.Entered)
            runCurrent()
            vm.onEvent(SpaceChatThreadEvent.MessageSelected(vm.state.value.rows.single().identity))
            vm.onEvent(SpaceChatThreadEvent.CopySelected)
            runCurrent()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.beforeMetadata = {
                withContext(NonCancellable) {
                    entered.complete(Unit)
                    release.await()
                }
            }
            val result = async {
                vm.confirmCopy(
                    effects.filterIsInstance<SpaceChatThreadEffect.CopyMessages>().single()
                )
            }
            entered.await()
            if (dispose) store.clear() else vm.onEvent(SpaceChatThreadEvent.Stopped)
            release.complete(Unit)
            assertFalse(result.await())
            f.beforeMetadata = {
                error("Disposed/stopped model must not begin another clipboard read")
            }
            assertFalse(
                vm.confirmCopy(
                    effects.filterIsInstance<SpaceChatThreadEffect.CopyMessages>().single()
                )
            )
        }
    }

    @Test
    fun aLateGroupDenialClearsTheScreenAndRejectsTheClipboardAction() = runTest {
        withModel { f, vm, _, effects ->
            f.entries.value = listOf(CollaborationComposerOutboxEntry(f.submission(), false))
            vm.onEvent(SpaceChatThreadEvent.Entered)
            runCurrent()
            vm.onEvent(SpaceChatThreadEvent.MessageSelected(vm.state.value.rows.single().identity))
            vm.onEvent(SpaceChatThreadEvent.CopySelected)
            runCurrent()
            f.beforeMetadata = {
                f.gate.value =
                    AppResult.Success(CollaborationSpaceDirectoryAdmission(chatId(803), f.denial))
            }
            assertFalse(
                vm.confirmCopy(
                    effects.filterIsInstance<SpaceChatThreadEffect.CopyMessages>().single()
                )
            )
            runCurrent()
            assertEquals(f.denial, vm.state.value.restriction)
            assertTrue(vm.state.value.rows.isEmpty())
            assertTrue(vm.state.value.selection.isEmpty())
        }
    }

    @Test
    fun aCancelledOriginalCannotBeCopiedFromAnOlderSelectionSnapshot() = runTest {
        withModel { f, vm, _, effects ->
            val original = f.submission()
            f.entries.value = listOf(CollaborationComposerOutboxEntry(original, false))
            vm.onEvent(SpaceChatThreadEvent.Entered)
            runCurrent()
            vm.onEvent(SpaceChatThreadEvent.MessageSelected(vm.state.value.rows.single().identity))
            vm.onEvent(SpaceChatThreadEvent.CopySelected)
            runCurrent()
            f.archived.value =
                listOf(
                    CollaborationSpaceMessageRecord(
                        collaborationSpaceComposerIntent(f.access, original),
                        0,
                        CollaborationMessageState.Cancelled,
                    )
                )
            runCurrent()
            assertTrue(vm.state.value.rows.isEmpty())
            assertFalse(
                vm.confirmCopy(
                    effects.filterIsInstance<SpaceChatThreadEffect.CopyMessages>().single()
                )
            )
        }
    }

    @Test
    fun replyingUsesAnActualSentEventAndRetainsTheQuotedDraftLocally() = runTest {
        withModel { f, vm, _, effects ->
            val original = f.submission()
            f.entries.value = listOf(CollaborationComposerOutboxEntry(original, false))
            vm.onEvent(SpaceChatThreadEvent.Entered)
            runCurrent()
            vm.onEvent(SpaceChatThreadEvent.MessageSelected(vm.state.value.rows.single().identity))
            assertFalse(vm.state.value.canReplySelection)
            f.archived.value = listOf(f.sent(original))
            runCurrent()
            assertTrue(vm.state.value.canReplySelection)
            vm.onEvent(SpaceChatThreadEvent.ReplySelected)
            runCurrent()
            assertFalse(vm.state.value.selecting)
            assertEquals("${'$'}event", vm.state.value.replyToEventId)
            assertEquals("${'$'}event", f.draft.value?.replyToEventId)
            assertTrue(SpaceChatThreadEffect.FocusComposer in effects)
            vm.onEvent(SpaceChatThreadEvent.ReplyCleared)
            runCurrent()
            assertNull(vm.state.value.replyToEventId)
            assertNull(f.draft.value?.replyToEventId)
        }
    }

    private suspend fun TestScope.withModel(
        block:
            suspend (
                SpaceChatThreadFixture,
                SpaceChatThreadViewModel,
                ViewModelStore,
                MutableList<SpaceChatThreadEffect>,
            ) -> Unit
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = SpaceChatThreadFixture()
        val vm = f.model()
        val store = ViewModelStore().also { it.put("thread", vm) }
        val effects = mutableListOf<SpaceChatThreadEffect>()
        val collector = backgroundScope.launch { vm.effects.collect { effects += it } }
        try {
            block(f, vm, store, effects)
        } finally {
            store.clear()
            collector.cancel()
            runCurrent()
            Dispatchers.resetMain()
        }
    }
}
