@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.presentation.recipient

import androidx.lifecycle.ViewModelStore
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.presentation.inbox.chatId
import dev.fajar.starter.collaboration.presentation.spacethread.SpaceChatMessageIdentity
import dev.fajar.starter.collaboration.presentation.spacethread.SpaceChatMessageSource
import dev.fajar.starter.common.result.AppResult
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class RecipientChatSelectionTest {
    @Test
    fun longPressSelectionTogglesAndBackClearsItBeforeLeaving() = runTest {
        withModel { _, model, effects, _ ->
            send(model, "One")
            val identity = model.state.value.rows.single().identity
            model.onEvent(RecipientChatThreadEvent.MessageSelected(identity))
            runCurrent()
            assertTrue(model.state.value.selecting)
            model.onEvent(RecipientChatThreadEvent.MessageSelected(identity))
            runCurrent()
            assertFalse(model.state.value.selecting)
            model.onEvent(RecipientChatThreadEvent.MessageSelected(identity))
            model.onEvent(RecipientChatThreadEvent.BackRequested)
            runCurrent()
            assertFalse(model.state.value.selecting)
            assertFalse(model.state.value.leaving)
            assertTrue(effects.isEmpty())
            model.onEvent(RecipientChatThreadEvent.BackRequested)
            runCurrent()
            assertTrue(
                model.confirmBack(assertIs<RecipientChatThreadEffect.Back>(effects.single()))
            )
        }
    }

    @Test
    fun pendingMessagesCopyInConversationOrderBeforeCanonicalAssociation() = runTest {
        withModel { f, model, effects, _ ->
            f.metadataAbsent = true
            send(model, "First")
            send(model, "Second")
            model.state.value.rows.forEach {
                model.onEvent(RecipientChatThreadEvent.MessageSelected(it.identity))
            }
            model.onEvent(RecipientChatThreadEvent.CopySelected)
            runCurrent()
            val effect = assertIs<RecipientChatThreadEffect.CopyMessages>(effects.single())
            assertEquals("First\n\nSecond", effect.text)
            assertFalse(model.state.value.selecting)
            assertTrue(model.confirmCopy(effect))
            assertFalse(model.confirmCopy(effect))
            assertEquals(1, f.metadataReads)
            assertEquals(0, f.thread.schedules)
        }
    }

    @Test
    fun canonicalAssociationDuringConfirmationPreservesTheActualSelectedOriginal() = runTest {
        withModel { f, model, effects, _ ->
            send(model, "Same original")
            val identity = model.state.value.rows.single().identity
            model.onEvent(RecipientChatThreadEvent.MessageSelected(identity))
            model.onEvent(RecipientChatThreadEvent.CopySelected)
            runCurrent()
            val effect = assertIs<RecipientChatThreadEffect.CopyMessages>(effects.single())
            f.associate()
            model.state.first { it.rows.singleOrNull()?.canonicalIdentity != null }
            assertEquals(identity, model.state.value.rows.single().identity)
            assertTrue(model.confirmCopy(effect))
            assertEquals(0, f.thread.schedules)
        }
    }

    @Test
    fun stoppedOrReleasedRouteCannotExecuteAnAlreadyDeliveredCopyEffect() = runTest {
        for (event in
            listOf(RecipientChatThreadEvent.Stopped, RecipientChatThreadEvent.RouteReleased)) {
            withModel { f, model, effects, _ ->
                send(model, "Private")
                selectCopy(model)
                runCurrent()
                val effect = assertIs<RecipientChatThreadEffect.CopyMessages>(effects.single())
                model.onEvent(event)
                model.onEvent(RecipientChatThreadEvent.Entered)
                runCurrent()
                assertFalse(model.confirmCopy(effect))
                assertEquals(0, f.metadataReads)
            }
        }
    }

    @Test
    fun stopWhileCheckingContextRejectsTheLateResult() = runTest {
        withModel { f, model, effects, _ ->
            send(model, "Private")
            selectCopy(model)
            runCurrent()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.beforeMetadata = {
                entered.complete(Unit)
                release.await()
            }
            val result = async {
                model.confirmCopy(
                    assertIs<RecipientChatThreadEffect.CopyMessages>(effects.single())
                )
            }
            try {
                entered.await()
                model.onEvent(RecipientChatThreadEvent.Stopped)
                model.onEvent(RecipientChatThreadEvent.Entered)
                release.complete(Unit)
                assertFalse(result.await())
            } finally {
                release.complete(Unit)
            }
        }
    }

    @Test
    fun denialDuringCopyClearsPrivateStateAndRejectsTheEffect() = runTest {
        withModel { f, model, effects, _ ->
            send(model, "Private")
            selectCopy(model)
            runCurrent()
            f.beforeMetadata = { f.thread.deny() }
            assertFalse(
                model.confirmCopy(
                    assertIs<RecipientChatThreadEffect.CopyMessages>(effects.single())
                )
            )
            model.state.first { it.restriction != null }
            assertTrue(model.state.value.rows.isEmpty())
            assertFalse(model.state.value.selecting)
        }
    }

    @Test
    fun disposalDuringContextReadRejectsTheResultAndReleasesPrivateState() = runTest {
        withModel { f, model, effects, store ->
            send(model, "Private")
            selectCopy(model)
            runCurrent()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.beforeMetadata = {
                entered.complete(Unit)
                release.await()
            }
            val result = async {
                model.confirmCopy(
                    assertIs<RecipientChatThreadEffect.CopyMessages>(effects.single())
                )
            }
            try {
                entered.await()
                store.clear()
                release.complete(Unit)
                assertFalse(result.await())
                assertTrue(model.state.value.rows.isEmpty())
                assertFalse(model.state.value.selecting)
            } finally {
                release.complete(Unit)
            }
        }
    }

    @Test
    fun selectingAnotherMessageSupersedesTheOldCopyRequest() = runTest {
        withModel { f, model, effects, _ ->
            send(model, "Private")
            selectCopy(model)
            runCurrent()
            val effect = assertIs<RecipientChatThreadEffect.CopyMessages>(effects.single())
            model.onEvent(
                RecipientChatThreadEvent.MessageSelected(model.state.value.rows.single().identity)
            )
            runCurrent()
            assertFalse(model.confirmCopy(effect))
            assertEquals(0, f.metadataReads)
        }
    }

    @Test
    fun selectionIsBoundedAndCannotIncludeARowFromAnotherRecipient() = runTest {
        withModel { _, model, _, _ ->
            repeat(51) { send(model, "Message $it") }
            val rows = model.state.value.rows
            rows.forEach { model.onEvent(RecipientChatThreadEvent.MessageSelected(it.identity)) }
            runCurrent()
            assertEquals(50, model.state.value.selection.size)
            assertTrue(model.state.value.selectionLimitReached)
            model.onEvent(RecipientChatThreadEvent.SelectionCleared)
            model.onEvent(
                RecipientChatThreadEvent.MessageSelected(
                    rows.first().identity.copy(peerAccountId = chatId(998))
                )
            )
            runCurrent()
            assertFalse(model.state.value.selecting)
            assertFalse(model.state.value.selectionLimitReached)
        }
    }

    @Test
    fun absentContextCannotAuthorizeAProviderRowOrAnUnrelatedOriginal() = runTest {
        withModel { f, model, _, _ ->
            send(model, "Original")
            val state = model.state.value
            val row = state.rows.single()
            assertTrue(recipientChatCopyMatchesContext(f.access, f.peer, null, row, state.messages))
            assertFalse(
                recipientChatCopyMatchesContext(
                    f.access,
                    f.peer,
                    null,
                    row,
                    RecipientChatPresentation(),
                )
            )
            val canonical =
                SpaceChatMessageIdentity(
                    row.identity.owner,
                    f.metadataValue.id,
                    row.identity.source,
                )
            assertFalse(
                recipientChatCopyMatchesContext(
                    f.access,
                    f.peer,
                    null,
                    row.copy(canonicalIdentity = canonical),
                    state.messages,
                )
            )
            assertFalse(
                recipientChatCopyMatchesContext(
                    f.access,
                    f.peer,
                    f.metadataValue.copy(id = chatId(991)),
                    row.copy(canonicalIdentity = canonical),
                    state.messages,
                )
            )
            val source =
                SpaceChatMessageSource.History("!room:example.test", "@peer:example.test", "event")
            val identity = row.identity.copy(source = source)
            val history =
                row.copy(identity = identity, bubble = row.bubble.copy(key = identity.stableKey))
            assertFalse(
                recipientChatCopyMatchesContext(f.access, f.peer, null, history, state.messages)
            )
            val otherPeer = chatId(997)
            assertNotEquals(f.peer, otherPeer)
            assertFalse(
                recipientChatCopyMatchesContext(f.access, otherPeer, null, row, state.messages)
            )
        }
    }

    @Test
    fun redactionWhileCopyWaitsCannotExportPreviouslySelectedText() = runTest {
        withModel { f, model, effects, _ ->
            send(model, "Remove this text")
            f.associate()
            f.bindRoom()
            val record = f.sent(f.editor.handed.single())
            f.thread.messages.value = listOf(record)
            f.publishHistory(record)
            model.state.first {
                (it.thread as? CollaborationSpaceThreadSnapshot.Bound)
                    ?.history
                    ?.page
                    ?.messages
                    ?.isNotEmpty() == true
            }
            selectCopy(model)
            runCurrent()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.beforeMetadata = {
                entered.complete(Unit)
                release.await()
            }
            val result = async {
                model.confirmCopy(
                    assertIs<RecipientChatThreadEffect.CopyMessages>(effects.single())
                )
            }
            try {
                entered.await()
                val page = (f.historyFacts.value as AppResult.Success).value
                f.historyFacts.value =
                    AppResult.Success(
                        page.copy(
                            messages = page.messages.map { it.copy(redacted = true, text = null) }
                        )
                    )
                model.state.first {
                    it.rows.singleOrNull()?.bubble?.kind == CollaborationMessageKind.REDACTED
                }
                release.complete(Unit)
                assertFalse(result.await())
                assertNull(model.state.value.rows.single().bubble.text)
            } finally {
                release.complete(Unit)
            }
        }
    }

    @Test
    fun excessiveClipboardTextDoesNotProduceAnEffectOrReadContext() = runTest {
        withModel { f, model, effects, _ ->
            repeat(9) { send(model, "${it}:" + "x".repeat(8_000)) }
            model.state.value.rows.forEach {
                model.onEvent(RecipientChatThreadEvent.MessageSelected(it.identity))
            }
            runCurrent()
            assertEquals(9, model.state.value.selection.size)
            assertFalse(model.state.value.canCopySelection)
            model.onEvent(RecipientChatThreadEvent.CopySelected)
            runCurrent()
            assertTrue(effects.isEmpty())
            assertEquals(0, f.metadataReads)
        }
    }

    private suspend fun send(model: RecipientChatThreadViewModel, text: String) {
        model.onEvent(RecipientChatThreadEvent.TextChanged(text))
        model.onEvent(RecipientChatThreadEvent.SendRequested)
        model.state.first {
            it.editor.submissions.all { item -> item.durable } &&
                it.rows.any { row -> row.bubble.text == text }
        }
    }

    private fun selectCopy(model: RecipientChatThreadViewModel) {
        model.onEvent(
            RecipientChatThreadEvent.MessageSelected(model.state.value.rows.single().identity)
        )
        model.onEvent(RecipientChatThreadEvent.CopySelected)
    }

    private suspend fun TestScope.withModel(
        block:
            suspend TestScope.(
                RecipientChatThreadViewModelFixture,
                RecipientChatThreadViewModel,
                MutableList<RecipientChatThreadEffect>,
                ViewModelStore,
            ) -> Unit
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = RecipientChatThreadViewModelFixture()
        val model = f.model()
        val store = ViewModelStore().also { it.put("recipient-thread", model) }
        val effects = mutableListOf<RecipientChatThreadEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            model.effects.collect { effects += it }
        }
        try {
            model.onEvent(RecipientChatThreadEvent.Entered)
            model.state.first { it.editor.ready }
            block(f, model, effects, store)
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }
}
