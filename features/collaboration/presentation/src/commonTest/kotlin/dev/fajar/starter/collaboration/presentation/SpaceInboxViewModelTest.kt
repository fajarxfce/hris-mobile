package dev.fajar.starter.collaboration.presentation

import androidx.lifecycle.ViewModelStore
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.presentation.spaceinbox.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SpaceInboxViewModelTest {
    @Test
    fun enteringTwiceKeepsOneVersionObserverAndStoppingReleasesVisibleContacts() = runTest {
        withModel { f, vm, _, _ ->
            val ids = f.pairs.map { it.id }
            vm.onEvent(SpaceChatInboxEvent.ViewportChanged(ids, ids))
            vm.onEvent(SpaceChatInboxEvent.Entered)
            vm.onEvent(SpaceChatInboxEvent.Entered)
            runCurrent()
            assertEquals(1, f.versionReaders)
            assertEquals(3, f.contactReaders)
            assertEquals(3, vm.state.value.names.size)
            val names = vm.state.value.names
            vm.onEvent(SpaceChatInboxEvent.Stopped)
            runCurrent()
            assertEquals(0, f.contactReaders)
            assertEquals(names, vm.state.value.names)
            vm.onEvent(SpaceChatInboxEvent.Entered)
            runCurrent()
            assertEquals(3, f.contactReaders)
            assertEquals(names, vm.state.value.names)
        }
    }

    @Test
    fun aPendingNewLabelDoesNotClearAlreadyKnownVisibleNames() = runTest {
        withModel { f, vm, _, _ ->
            val first = f.pairs[0]
            val second = f.pairs[1]
            vm.onEvent(SpaceChatInboxEvent.Entered)
            vm.onEvent(
                SpaceChatInboxEvent.ViewportChanged(listOf(first.id), listOf(first.id, second.id))
            )
            runCurrent()
            val name = vm.state.value.names[first.id]
            val release = CompletableDeferred<Unit>()
            f.beforeContact = { if (it == second.secondAccountId) release.await() }
            vm.onEvent(
                SpaceChatInboxEvent.ViewportChanged(
                    listOf(first.id, second.id),
                    listOf(first.id, second.id),
                )
            )
            runCurrent()
            assertEquals(name, vm.state.value.names[first.id])
            assertFalse(vm.state.value.names.containsKey(second.id))
            release.complete(Unit)
            runCurrent()
            assertNotNull(vm.state.value.names[second.id])
        }
    }

    @Test
    fun contactFailuresKeepOwnedNamesAndExplicitRemovalClearsOnlyThatName() = runTest {
        withModel { f, vm, _, _ ->
            val ids = f.pairs.map { it.id }
            vm.onEvent(SpaceChatInboxEvent.Entered)
            vm.onEvent(SpaceChatInboxEvent.ViewportChanged(ids, ids))
            runCurrent()
            val names = vm.state.value.names
            val contact = f.contacts.getValue(f.pairs[0].secondAccountId)
            contact.value = AppResult.Failed(f.thread.storage)
            runCurrent()
            assertEquals(names, vm.state.value.names)
            contact.value = AppResult.Success(null)
            runCurrent()
            assertTrue(vm.state.value.names.containsKey(ids[0]))
            assertNull(vm.state.value.names[ids[0]])
            assertEquals(names[ids[1]], vm.state.value.names[ids[1]])
        }
    }

    @Test
    fun restrictionRedactsNamesAndFailedAdmissionCannotReopenContacts() = runTest {
        withModel { f, vm, _, _ ->
            val ids = f.pairs.map { it.id }
            vm.onEvent(SpaceChatInboxEvent.Entered)
            vm.onEvent(SpaceChatInboxEvent.ViewportChanged(ids, ids))
            runCurrent()
            f.deny()
            runCurrent()
            assertEquals(f.thread.denial, vm.state.value.restriction)
            assertTrue(vm.state.value.names.isEmpty())
            assertEquals(0, f.contactReaders)
            f.thread.gate.value = AppResult.Failed(f.thread.storage)
            runCurrent()
            assertEquals(f.thread.denial, vm.state.value.restriction)
            assertEquals(0, f.contactReaders)
            f.open()
            runCurrent()
            assertNull(vm.state.value.restriction)
            assertEquals(3, vm.state.value.names.size)
        }
    }

    @Test
    fun selectingAVisibleConversationEmitsOnceAndOffscreenOrRestrictedSelectionsDoNothing() =
        runTest {
            withModel { f, vm, _, effects ->
                val id = f.pairs[0].id
                vm.onEvent(SpaceChatInboxEvent.Entered)
                vm.onEvent(SpaceChatInboxEvent.ViewportChanged(listOf(id), listOf(id)))
                runCurrent()
                vm.onEvent(SpaceChatInboxEvent.ConversationSelected(id))
                vm.onEvent(SpaceChatInboxEvent.ConversationSelected(id))
                runCurrent()
                assertEquals<List<SpaceChatInboxEffect>>(
                    listOf(SpaceChatInboxEffect.OpenConversation(id)),
                    effects,
                )
                vm.onEvent(SpaceChatInboxEvent.Entered)
                f.deny()
                runCurrent()
                vm.onEvent(SpaceChatInboxEvent.ConversationSelected(id))
                runCurrent()
                assertEquals(1, effects.size)
            }
        }

    @Test
    fun viewportChangesEvictOnlyUnloadedNamesAndCaptureCallerLists() = runTest {
        withModel { f, vm, _, _ ->
            val ids = f.pairs.map { it.id }
            vm.onEvent(SpaceChatInboxEvent.Entered)
            vm.onEvent(SpaceChatInboxEvent.ViewportChanged(ids, ids))
            runCurrent()
            val mutable = mutableListOf(ids[1])
            val event = SpaceChatInboxEvent.ViewportChanged(mutable, mutable)
            mutable.clear()
            vm.onEvent(event)
            runCurrent()
            assertEquals(setOf(ids[1]), vm.state.value.names.keys)
            assertEquals(1, f.contactReaders)
        }
    }

    @Test
    fun accountReplacementAndViewModelDisposalStopAllOldReaders() = runTest {
        withModel { f, vm, store, _ ->
            val ids = f.pairs.map { it.id }
            vm.onEvent(SpaceChatInboxEvent.Entered)
            vm.onEvent(SpaceChatInboxEvent.ViewportChanged(ids, ids))
            runCurrent()
            f.thread.base.session.value = null
            runCurrent()
            assertNotNull(vm.state.value.restriction)
            assertTrue(vm.state.value.names.isEmpty())
            assertEquals(0, f.contactReaders)
            store.clear()
            runCurrent()
            assertEquals(0, f.versionReaders)
        }
    }

    @Test
    fun disposalRejectsUncooperativeLateMetadataAndStartsNoContactReader() = runTest {
        withModel { f, vm, store, _ ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.beforeMetadata = {
                withContext(NonCancellable) {
                    entered.complete(Unit)
                    release.await()
                }
            }
            val id = f.pairs[0].id
            vm.onEvent(SpaceChatInboxEvent.Entered)
            vm.onEvent(SpaceChatInboxEvent.ViewportChanged(listOf(id), listOf(id)))
            runCurrent()
            entered.await()
            store.clear()
            release.complete(Unit)
            runCurrent()
            assertTrue(vm.state.value.names.isEmpty())
            assertEquals(0, f.contactReaders)
            assertEquals(0, f.versionReaders)
        }
    }

    private suspend fun TestScope.withModel(
        block:
            suspend TestScope.(
                SpaceInboxViewModelFixture,
                SpaceChatInboxViewModel,
                ViewModelStore,
                MutableList<SpaceChatInboxEffect>,
            ) -> Unit
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = SpaceInboxViewModelFixture()
        val vm = f.model()
        val store = ViewModelStore().also { it.put("inbox", vm) }
        val effects = mutableListOf<SpaceChatInboxEffect>()
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
