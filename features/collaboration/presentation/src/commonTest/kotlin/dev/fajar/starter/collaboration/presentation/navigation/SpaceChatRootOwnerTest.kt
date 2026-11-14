package dev.fajar.starter.collaboration.presentation.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.presentation.SpaceRootPresentationFixture
import dev.fajar.starter.collaboration.presentation.inbox.chatId
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SpaceChatRootOwnerTest {
    @Test
    fun companyChangesAndExpiredCredentialsKeepTheSameRootWithoutAStateCollector() = runTest {
        withOwner { f, owner, _ ->
            runCurrent()
            val root = assertNotNull(owner.store)
            val probe = Probe().also { root.viewModelStore.put("probe", it) }
            f.thread.base.workspace.value = null
            val session = f.thread.base.initial
            f.thread.base.session.value =
                session.copy(tokens = session.tokens.copy(expiresAtEpochMillis = 0))
            runCurrent()
            assertSame(root, owner.store)
            assertEquals(f.access, owner.state.value.access)
            assertEquals(0, probe.cleared)
            assertEquals(1, f.rootReaders)
            assertEquals(1, f.gateReaders)
        }
    }

    @Test
    fun transientFailureRetainsTheRootButDenialClosesItAndFailedRetryCannotReopenIt() = runTest {
        withOwner { f, owner, _ ->
            runCurrent()
            val root = assertNotNull(owner.store)
            val probe = Probe().also { root.viewModelStore.put("probe", it) }
            f.thread.gate.value = AppResult.Failed(f.thread.storage)
            runCurrent()
            assertSame(root, owner.store)
            f.thread.gate.value =
                AppResult.Success(
                    CollaborationSpaceDirectoryAdmission(chatId(901), f.thread.denial)
                )
            runCurrent()
            assertNull(owner.store)
            assertNull(owner.state.value.access)
            assertEquals(1, probe.cleared)
            assertFailsWith<IllegalStateException> { root.viewModelStore }
            f.thread.gate.value = AppResult.Failed(f.thread.storage)
            runCurrent()
            assertNull(owner.store)
            assertEquals(f.thread.denial, owner.state.value.failure)
            f.thread.gate.value =
                AppResult.Success(CollaborationSpaceDirectoryAdmission(null, null))
            runCurrent()
            assertNotSame(root, owner.store)
            assertEquals(f.access, owner.state.value.access)
        }
    }

    @Test
    fun groupReplacementClosesBothCachedAndOpenThreadModelsBeforeCreatingAnotherRoot() = runTest {
        withOwner { f, owner, _ ->
            runCurrent()
            val root = assertNotNull(owner.store)
            val graph =
                SpaceChatNavigationOwner(f.access).also { root.viewModelStore.put("threads", it) }
            val route = ViewModelStore()
            try {
                val live = graph.open("open").also { route.put("lease", it) }
                val openProbe = Probe().also { live.viewModelStore.put("probe", it) }
                val prepared = graph.prepare(listOf("cached")).getValue("cached")
                val cachedProbe = Probe().also { prepared.models.put("probe", it) }
                f.roots.value = AppResult.Success(CollaborationSpaceRoot(chatId(902)))
                runCurrent()
                assertTrue(root.closed)
                assertNotSame(root, owner.store)
                assertEquals(chatId(902), owner.state.value.access?.spaceId)
                assertEquals(1, openProbe.cleared)
                assertEquals(1, cachedProbe.cleared)
                assertFailsWith<IllegalStateException> { live.viewModelStore }
                assertFailsWith<IllegalStateException> { graph.prepare(listOf("late")) }
            } finally {
                route.clear()
            }
        }
    }

    @Test
    fun accountReplacementClosesOldRootWhileTheNewRootReadIsStillPending() = runTest {
        withOwner { f, owner, _ ->
            runCurrent()
            val root = assertNotNull(owner.store)
            val release = CompletableDeferred<Unit>()
            f.rootSource = {
                flow {
                    release.await()
                    emitAll(f.roots)
                }
            }
            val old = f.thread.base.initial
            f.thread.base.session.value =
                old.copy(id = "replacement", user = old.user.copy(id = chatId(903)))
            runCurrent()
            assertTrue(root.closed)
            assertNull(owner.store)
            assertNull(owner.state.value.access)
            release.complete(Unit)
            runCurrent()
            assertEquals(chatId(903), owner.state.value.access?.accountId)
        }
    }

    @Test
    fun aSlowStateConsumerDistinguishesTheReopenedStoreForTheSameAccountAndGroup() = runTest {
        withOwner { f, owner, _ ->
            runCurrent()
            val original = owner.state.value
            val oldStore = assertNotNull(owner.store)
            val resume = CompletableDeferred<Unit>()
            val received = mutableListOf<SpaceChatRootState>()
            val collector =
                backgroundScope.launch {
                    owner.state.collect {
                        received += it
                        if (received.size == 1) resume.await()
                    }
                }
            runCurrent()
            f.thread.gate.value =
                AppResult.Success(
                    CollaborationSpaceDirectoryAdmission(chatId(904), f.thread.denial)
                )
            runCurrent()
            f.thread.gate.value =
                AppResult.Success(CollaborationSpaceDirectoryAdmission(null, null))
            runCurrent()
            resume.complete(Unit)
            runCurrent()
            assertEquals(2, received.size)
            assertEquals(original.access, received.last().access)
            assertTrue(received.last().lifetime > original.lifetime)
            assertTrue(oldStore.closed)
            assertNotSame(oldStore, owner.store)
            collector.cancel()
        }
    }

    @Test
    fun logoutAndWindowDisposalCloseStoresWithoutAnActiveUiCollector() = runTest {
        withOwner { f, owner, window ->
            runCurrent()
            val first = assertNotNull(owner.store)
            f.thread.base.session.value = null
            runCurrent()
            assertTrue(first.closed)
            assertNull(owner.store)
            assertEquals(0, f.rootReaders)
            assertEquals(0, f.gateReaders)
            f.thread.base.session.value = f.thread.base.initial
            runCurrent()
            val second = assertNotNull(owner.store)
            window.clear()
            runCurrent()
            assertTrue(second.closed)
            assertNull(owner.store)
            assertEquals(0, f.rootReaders)
            assertEquals(0, f.gateReaders)
        }
    }

    @Test
    fun disposedOwnerCannotPublishAnUncooperativeLateRead() = runTest {
        withOwner { f, owner, window ->
            val release = CompletableDeferred<Unit>()
            f.beforeRootRead = { withContext(NonCancellable) { release.await() } }
            runCurrent()
            window.clear()
            release.complete(Unit)
            runCurrent()
            assertNull(owner.store)
            assertNull(owner.state.value.access)
            assertEquals(0, f.rootReaders)
            assertEquals(0, f.gateReaders)
        }
    }

    private suspend fun TestScope.withOwner(
        block:
            suspend TestScope.(
                SpaceRootPresentationFixture, SpaceChatRootOwner, ViewModelStore,
            ) -> Unit
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = SpaceRootPresentationFixture()
        val owner = f.model()
        val window = ViewModelStore().also { it.put("root", owner) }
        try {
            block(f, owner, window)
        } finally {
            window.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    private class Probe : ViewModel() {
        var cleared = 0

        override fun onCleared() {
            cleared++
        }
    }
}
