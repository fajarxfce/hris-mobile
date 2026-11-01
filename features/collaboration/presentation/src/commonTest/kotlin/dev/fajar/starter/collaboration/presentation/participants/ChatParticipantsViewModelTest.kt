@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.presentation.participants

import androidx.lifecycle.ViewModelStore
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*

class ChatParticipantsViewModelTest {
    @Test
    fun entryAndTypingOwnOneSubscriptionAndDebounceTheLatestBoundedQuery() = runTest {
        withModel { f, vm, owner, _ ->
            vm.onEvent(ChatParticipantsEvent.Entered)
            vm.onEvent(ChatParticipantsEvent.Entered)
            runCurrent()
            assertEquals<List<Pair<String, String?>>>(listOf("" to null), f.searches)
            vm.onEvent(ChatParticipantsEvent.QueryChanged("X".repeat(200)))
            runCurrent()
            assertEquals(80, vm.state.value.query.length)
            vm.onEvent(ChatParticipantsEvent.QueryChanged("X".repeat(79) + "🙂"))
            runCurrent()
            assertEquals("X".repeat(79), vm.state.value.query)
            vm.onEvent(ChatParticipantsEvent.QueryChanged("Nadia\nPratama"))
            runCurrent()
            advanceTimeBy(299)
            runCurrent()
            assertEquals(1, f.searches.size)
            advanceTimeBy(1)
            runCurrent()
            assertEquals("Nadia Pratama" to null, f.searches.last())
            assertEquals(1, f.base.session.subscriptionCount.value)
            assertEquals(0, f.base.observers)
            vm.onEvent(ChatParticipantsEvent.Stopped)
            runCurrent()
            vm.onEvent(ChatParticipantsEvent.QueryChanged("hidden"))
            runCurrent()
            assertEquals(2, f.searches.size)
            f.base.session.value = null
            runCurrent()
            assertNull(vm.state.value.page)
            assertNotNull(vm.state.value.localFailure)
            owner.clear()
            runCurrent()
            assertEquals(0, f.base.session.subscriptionCount.value)
        }
    }

    @Test
    fun reentryWaitsForTheInitialLocalAccessResultInsteadOfStartingDuplicateDiscovery() = runTest {
        withModel { f, vm, _, _ ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.beforeGateObservation = {
                entered.complete(Unit)
                release.await()
            }
            try {
                vm.onEvent(ChatParticipantsEvent.Entered)
                runCurrent()
                entered.await()
                vm.onEvent(ChatParticipantsEvent.Stopped)
                runCurrent()
                vm.onEvent(ChatParticipantsEvent.Entered)
                runCurrent()
                assertTrue(f.searches.isEmpty())
                release.complete(Unit)
                runCurrent()
                assertEquals(1, f.searches.size)
                assertEquals(f.peers, vm.state.value.page!!.items)
            } finally {
                release.complete(Unit)
                runCurrent()
            }
        }
    }

    @Test
    fun oldUncooperativeSearchCannotReplaceNewQueryResults() = runTest {
        withModel { f, vm, _, _ ->
            vm.onEvent(ChatParticipantsEvent.Entered)
            runCurrent()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.search = { query, _ ->
                if (query == "old")
                    withContext(NonCancellable) {
                        entered.complete(Unit)
                        release.await()
                        AppResult.Success(CollaborationPage(listOf(f.peers[0]), null))
                    }
                else AppResult.Success(CollaborationPage(listOf(f.peers[1]), null))
            }
            try {
                vm.onEvent(ChatParticipantsEvent.QueryChanged("old"))
                runCurrent()
                advanceTimeBy(300)
                runCurrent()
                entered.await()
                vm.onEvent(ChatParticipantsEvent.QueryChanged("new"))
                runCurrent()
                advanceTimeBy(300)
                runCurrent()
                assertEquals(listOf(f.peers[1]), vm.state.value.page!!.items)
                release.complete(Unit)
                runCurrent()
                assertEquals("new", vm.state.value.query)
                assertEquals(listOf(f.peers[1]), vm.state.value.page!!.items)
            } finally {
                release.complete(Unit)
                runCurrent()
            }
        }
    }

    @Test
    fun enteringAnInitiallyGatedDirectoryMakesOneAdmittedRecoveryAttempt() = runTest {
        withModel { f, vm, _, _ ->
            f.base.gate.value = CollaborationListAdmission("old-denial", f.base.denied)
            vm.onEvent(ChatParticipantsEvent.Entered)
            runCurrent()
            assertEquals(1, f.searches.size)
            assertEquals(f.peers, vm.state.value.page!!.items)
            assertNull(vm.state.value.localFailure)
            assertNull(f.base.gate.value.denial)
        }
    }

    @Test
    fun receivedDenialHidesPeopleAndFailedRetryCannotReopenThem() = runTest {
        withModel { f, vm, _, effects ->
            f.search = { _, _ -> AppResult.Failed(f.base.denied) }
            vm.onEvent(ChatParticipantsEvent.Entered)
            runCurrent()
            assertNull(vm.state.value.page)
            assertEquals(f.base.denied, vm.state.value.localFailure)
            f.search = { _, _ -> AppResult.Failed(f.base.network) }
            vm.onEvent(ChatParticipantsEvent.RetryRequested)
            runCurrent()
            assertEquals(f.base.denied, vm.state.value.localFailure)
            vm.onEvent(ChatParticipantsEvent.ParticipantSelected(f.peers[0].accountId))
            runCurrent()
            assertTrue(f.opens.isEmpty())
            assertTrue(effects.isEmpty())
            f.search = { _, _ -> AppResult.Success(CollaborationPage(f.peers, null)) }
            vm.onEvent(ChatParticipantsEvent.RetryRequested)
            runCurrent()
            assertEquals(f.peers, vm.state.value.page!!.items)
            assertNull(vm.state.value.localFailure)
        }
    }

    @Test
    fun selectedPeerOpensOnceAndCachesTheExactConversationBeforeNavigation() = runTest {
        withModel { f, vm, _, effects ->
            vm.onEvent(ChatParticipantsEvent.Entered)
            runCurrent()
            vm.onEvent(ChatParticipantsEvent.ParticipantSelected("unknown"))
            runCurrent()
            assertTrue(f.opens.isEmpty())
            vm.onEvent(ChatParticipantsEvent.ParticipantSelected(f.peers[0].accountId))
            vm.onEvent(ChatParticipantsEvent.ParticipantSelected(f.peers[0].accountId))
            runCurrent()
            val id = f.base.item(1).conversation.id
            assertEquals(1, f.opens.size)
            assertEquals(listOf(id), f.points)
            assertEquals(id, vm.state.value.openedConversationId)
            assertEquals<List<ChatParticipantsEffect>>(
                listOf(ChatParticipantsEffect.OpenConversation(id)),
                effects,
            )
            assertEquals(listOf(id), f.base.records.value.map { it.conversation.id })
            assertTrue(f.retained.isEmpty())
            assertEquals(0, f.base.observers)
        }
    }

    @Test
    fun retryAfterLostOpenResponseReusesTheRetainedOperation() = runTest {
        withModel { f, vm, _, effects ->
            f.sendOpen = { command ->
                val success = f.commitOpen(command)
                if (f.opens.size == 1) AppResult.Failed(f.base.network) else success
            }
            vm.onEvent(ChatParticipantsEvent.Entered)
            runCurrent()
            vm.onEvent(ChatParticipantsEvent.ParticipantSelected(f.peers[0].accountId))
            runCurrent()
            assertEquals(f.base.network, vm.state.value.openFailure)
            assertTrue(effects.isEmpty())
            assertEquals(1, f.retained.size)
            vm.onEvent(ChatParticipantsEvent.RetryOpenRequested)
            runCurrent()
            assertEquals(2, f.opens.size)
            assertEquals(1, f.opens.map { it.operationId }.distinct().size)
            assertEquals(1, f.remoteRecords.size)
            assertEquals(1, f.base.records.value.size)
            assertEquals(1, effects.size)
            assertTrue(f.retained.isEmpty())
        }
    }

    @Test
    fun retryAfterSummaryFailureDoesNotResendAnAlreadyConfirmedOpen() = runTest {
        withModel { f, vm, _, effects ->
            f.point = { id ->
                if (f.points.size == 1) AppResult.Failed(f.base.network)
                else AppResult.Success(checkNotNull(f.remoteRecords[id]))
            }
            vm.onEvent(ChatParticipantsEvent.Entered)
            runCurrent()
            vm.onEvent(ChatParticipantsEvent.ParticipantSelected(f.peers[0].accountId))
            runCurrent()
            assertEquals(f.base.network, vm.state.value.openFailure)
            assertIs<CollaborationConversationOutcome.Confirmed>(f.retained.values.single().outcome)
            vm.onEvent(ChatParticipantsEvent.RetryOpenRequested)
            runCurrent()
            assertEquals(1, f.opens.size)
            assertEquals(2, f.points.size)
            assertEquals(1, effects.size)
            assertTrue(f.retained.isEmpty())
        }
    }

    @Test
    fun stoppedOpenCannotNavigateLateAndExplicitResumeKeepsItsOperation() = runTest {
        withModel { f, vm, _, effects ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.sendOpen = { command ->
                val success = f.commitOpen(command)
                if (f.opens.size == 1)
                    withContext(NonCancellable) {
                        entered.complete(Unit)
                        release.await()
                    }
                success
            }
            try {
                vm.onEvent(ChatParticipantsEvent.Entered)
                runCurrent()
                vm.onEvent(ChatParticipantsEvent.ParticipantSelected(f.peers[0].accountId))
                runCurrent()
                entered.await()
                vm.onEvent(ChatParticipantsEvent.Stopped)
                runCurrent()
                release.complete(Unit)
                runCurrent()
                assertTrue(effects.isEmpty())
                assertFalse(vm.state.value.opening)
                vm.onEvent(ChatParticipantsEvent.Entered)
                runCurrent()
                vm.onEvent(ChatParticipantsEvent.RetryOpenRequested)
                runCurrent()
                assertEquals(2, f.opens.size)
                assertEquals(1, f.opens.map { it.operationId }.distinct().size)
                assertEquals(1, effects.size)
            } finally {
                release.complete(Unit)
                runCurrent()
            }
        }
    }

    @Test
    fun replacedAccountClearsSelectionAndRejectsLateOpenPublication() = runTest {
        withModel { f, vm, _, effects ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.sendOpen = { command ->
                withContext(NonCancellable) {
                    entered.complete(Unit)
                    release.await()
                    f.commitOpen(command)
                }
            }
            try {
                vm.onEvent(ChatParticipantsEvent.Entered)
                runCurrent()
                vm.onEvent(ChatParticipantsEvent.ParticipantSelected(f.peers[0].accountId))
                runCurrent()
                entered.await()
                f.base.session.value = null
                runCurrent()
                assertNull(vm.state.value.selected)
                assertNull(vm.state.value.page)
                release.complete(Unit)
                runCurrent()
                assertNull(vm.state.value.openedConversationId)
                assertTrue(effects.isEmpty())
                assertTrue(f.base.records.value.isEmpty())
            } finally {
                release.complete(Unit)
                runCurrent()
            }
        }
    }

    @Test
    fun nextPageRetryKeepsItsQueryAndCursorAndPreviousStillWorks() = runTest {
        withModel { f, vm, _, _ ->
            var failed = false
            f.search = { _, after ->
                when {
                    after == null ->
                        AppResult.Success(CollaborationPage(listOf(f.peers[0]), "next"))
                    !failed -> {
                        failed = true
                        AppResult.Failed(f.base.network)
                    }
                    else -> AppResult.Success(CollaborationPage(listOf(f.peers[1]), null))
                }
            }
            vm.onEvent(ChatParticipantsEvent.Entered)
            runCurrent()
            vm.onEvent(ChatParticipantsEvent.NextRequested)
            runCurrent()
            assertEquals("next", vm.state.value.after)
            assertEquals(f.base.network, vm.state.value.failure)
            vm.onEvent(ChatParticipantsEvent.RetryRequested)
            runCurrent()
            assertEquals(listOf(f.peers[1]), vm.state.value.page!!.items)
            assertEquals(listOf("" to null, "" to "next", "" to "next"), f.searches)
            vm.onEvent(ChatParticipantsEvent.PreviousRequested)
            runCurrent()
            assertNull(vm.state.value.after)
            assertTrue(vm.state.value.previous.isEmpty())
            assertEquals(listOf(f.peers[0]), vm.state.value.page!!.items)
        }
    }

    @Test
    fun unverifiedReceiptTargetCannotNavigateEvenWhenSummaryWasAcquired() = runTest {
        withModel { f, vm, _, effects ->
            f.direct = { id ->
                AppResult.Success(
                    checkNotNull(f.remoteRecords[id])
                        .conversation
                        .copy(secondAccountId = f.peers[1].accountId)
                )
            }
            vm.onEvent(ChatParticipantsEvent.Entered)
            runCurrent()
            vm.onEvent(ChatParticipantsEvent.ParticipantSelected(f.peers[0].accountId))
            runCurrent()
            assertNotNull(vm.state.value.openFailure)
            assertNull(vm.state.value.openedConversationId)
            assertTrue(effects.isEmpty())
            assertEquals(1, f.retained.size)
        }
    }

    @Test
    fun explicitSuccessReopensCompletedLocalAdmissionObservation() = runTest {
        withModel { f, vm, _, _ ->
            f.completedGateObservation = true
            vm.onEvent(ChatParticipantsEvent.Entered)
            runCurrent()
            assertEquals(f.base.network, vm.state.value.localFailure)
            f.completedGateObservation = false
            vm.onEvent(ChatParticipantsEvent.RetryRequested)
            runCurrent()
            assertNull(vm.state.value.localFailure)
            assertEquals(f.peers, vm.state.value.page!!.items)
            f.base.session.value = null
            runCurrent()
            assertNull(vm.state.value.page)
        }
    }

    private suspend fun TestScope.withModel(
        block:
            suspend TestScope.(
                ChatParticipantsFixture,
                ChatParticipantsViewModel,
                ViewModelStore,
                MutableList<ChatParticipantsEffect>,
            ) -> Unit
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val fixture = ChatParticipantsFixture()
            val model = fixture.model()
            owner.put("participants", model)
            val effects = mutableListOf<ChatParticipantsEffect>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                model.effects.collect(effects::add)
            }
            block(fixture, model, owner, effects)
        } finally {
            owner.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }
}
