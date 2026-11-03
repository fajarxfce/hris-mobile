@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.presentation.replies

import androidx.lifecycle.ViewModelStore
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.collaboration.presentation.inbox.ChatInboxFixture
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class SavedRepliesViewModelTest {
    @Test
    fun duplicateEntryOwnsOneObservationAndStopClearsPrivateState() = runTest {
        withModel { f, vm ->
            vm.onEvent(SavedRepliesEvent.Entered)
            vm.onEvent(SavedRepliesEvent.Entered)
            runCurrent()
            assertEquals(1, f.active)
            assertNotNull(vm.state.value.selected)
            vm.onEvent(SavedRepliesEvent.Stopped)
            runCurrent()
            assertEquals(0, f.active)
            assertNull(vm.state.value.snapshot)
            vm.onEvent(SavedRepliesEvent.Entered)
            runCurrent()
            assertEquals(1, f.active)
            assertNotNull(vm.state.value.selected)
        }
    }

    @Test
    fun confirmationIsRequiredAndSuccessfulRemovalSurvivesObservationBeforeResponse() = runTest {
        withModel { f, vm ->
            vm.onEvent(SavedRepliesEvent.Entered)
            runCurrent()
            vm.onEvent(SavedRepliesEvent.DiscardConfirmed)
            runCurrent()
            assertEquals(0, f.preventions)
            vm.onEvent(SavedRepliesEvent.DiscardRequested)
            runCurrent()
            vm.onEvent(SavedRepliesEvent.DiscardConfirmed)
            runCurrent()
            assertEquals(1, f.preventions)
            assertNull(f.retained.value)
            assertEquals(CollaborationReplyDiscard.Discarded, vm.state.value.outcome)
            assertFalse(vm.state.value.busy)
            assertEquals(CollaborationMessageState.Cancelled, f.command?.state)
        }
    }

    @Test
    fun deniedAccessMasksTextCancelsPendingMutationAndRejectsItsLateAcknowledgement() = runTest {
        withModel { f, vm ->
            val entered = CompletableDeferred<Unit>()
            val released = CompletableDeferred<Unit>()
            var joined = false
            f.afterPrevent = {
                withContext(NonCancellable) {
                    entered.complete(Unit)
                    released.await()
                    joined = true
                }
            }
            vm.onEvent(SavedRepliesEvent.Entered)
            runCurrent()
            vm.onEvent(SavedRepliesEvent.DiscardRequested)
            runCurrent()
            vm.onEvent(SavedRepliesEvent.DiscardConfirmed)
            runCurrent()
            entered.await()
            f.base.gate.value = CollaborationListAdmission("denied", f.base.denied)
            runCurrent()
            assertNull(vm.state.value.snapshot)
            assertEquals(f.base.denied, vm.state.value.failure)
            released.complete(Unit)
            runCurrent()
            assertTrue(joined)
            assertNull(vm.state.value.outcome)
            assertNotNull(f.retained.value)
        }
    }

    @Test
    fun rapidReentryJoinsObsoleteMutationBeforeReadingReplacementState() = runTest {
        withModel { f, vm ->
            val entered = CompletableDeferred<Unit>()
            val released = CompletableDeferred<Unit>()
            f.afterPrevent = {
                withContext(NonCancellable) {
                    entered.complete(Unit)
                    released.await()
                }
            }
            vm.onEvent(SavedRepliesEvent.Entered)
            runCurrent()
            vm.onEvent(SavedRepliesEvent.DiscardRequested)
            runCurrent()
            vm.onEvent(SavedRepliesEvent.DiscardConfirmed)
            runCurrent()
            entered.await()
            vm.onEvent(SavedRepliesEvent.Stopped)
            vm.onEvent(SavedRepliesEvent.Entered)
            runCurrent()
            assertEquals(0, f.active)
            assertNull(vm.state.value.snapshot)
            released.complete(Unit)
            runCurrent()
            assertEquals(1, f.active)
            assertNotNull(vm.state.value.selected)
            assertNull(vm.state.value.outcome)
        }
    }

    private suspend fun TestScope.withModel(
        block: suspend TestScope.(Fixture, SavedRepliesViewModel) -> Unit
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val fixture = Fixture()
            val vm = fixture.model()
            store.put("saved", vm)
            block(fixture, vm)
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    private class Fixture {
        val base = ChatInboxFixture()
        val conversation = base.item(1)
        val parent =
            base.initial.let {
                CollaborationNativeParent(
                    it.id,
                    it.binding.audience,
                    it.user.id,
                    it.binding.serverId,
                )
            }
        val input =
            CollaborationNotificationReplyInput(
                "n-" + "a".repeat(64),
                CollaborationNotificationReplyRevision(id(6), 0),
                0,
                CollaborationNotificationReplyCandidate(
                    parent,
                    CollaborationNotificationAction.Reply(
                        id(7),
                        CollaborationNotificationActionTarget(
                            conversation.conversation.companyId,
                            conversation.conversation.id,
                            "!room:example.test",
                            "\$event",
                            id(8),
                            id(9),
                        ),
                    ),
                    CollaborationNotificationActionToken("v1.fixture"),
                    "Saved private reply",
                ),
                CollaborationNotificationReplyReview.SessionChanged,
            )
        val retained = MutableStateFlow<CollaborationNotificationReplyInput?>(input)
        var active = 0
        var preventions = 0
        var command: CollaborationMessageRecord? = null
        var afterPrevent: suspend () -> Unit = {}

        init {
            base.records.value = listOf(conversation)
        }

        val inputs =
            object : CollaborationNotificationReplyRepository {
                override fun observeChanges(
                    access: CollaborationRecoveryAccess
                ): Flow<AppResult<Unit>> = flow {
                    active++
                    try {
                        emitAll(retained.map { AppResult.Success(Unit) })
                    } finally {
                        active--
                    }
                }

                override suspend fun page(
                    access: CollaborationRecoveryAccess,
                    after: Long?,
                    limit: Int,
                    pendingOnly: Boolean,
                ) =
                    AppResult.Success(
                        CollaborationNotificationReplyPage(listOfNotNull(retained.value), null)
                    )

                override suspend fun get(access: CollaborationRecoveryAccess, id: String) =
                    AppResult.Success(retained.value)

                override suspend fun retain(
                    access: CollaborationRecoveryAccess,
                    candidate: CollaborationNotificationReplyCandidate,
                ): AppResult<CollaborationNotificationReplyRetention> = error("No callback")

                override suspend fun hold(
                    access: CollaborationRecoveryAccess,
                    expected: CollaborationNotificationReplyInput,
                    reason: CollaborationNotificationReplyReview,
                ): AppResult<Boolean> = error("No automatic review")

                override suspend fun remove(
                    access: CollaborationRecoveryAccess,
                    expected: CollaborationNotificationReplyInput,
                ): AppResult<Boolean> {
                    if (retained.value != expected) return AppResult.Success(false)
                    retained.value = null
                    yield()
                    return AppResult.Success(true)
                }
            }
        val messages =
            object : CollaborationMessageRepository {
                override fun observe(
                    access: CollaborationLocalAccess,
                    conversationId: String?,
                ): Flow<AppResult<List<CollaborationMessageRecord>>> = error("No thread")

                override suspend fun get(access: CollaborationLocalAccess, id: String) =
                    AppResult.Success(command)

                override suspend fun insert(
                    access: CollaborationLocalAccess,
                    intent: CollaborationMessageIntent,
                ): AppResult<CollaborationMessageInsertion> = error("No queue")

                override suspend fun update(
                    access: CollaborationLocalAccess,
                    expected: CollaborationMessageRecord,
                    state: CollaborationMessageState,
                ): AppResult<Boolean> = error("No worker")
            }
        val prevention =
            object : CollaborationMessagePreventionRepository {
                override suspend fun prevent(
                    access: CollaborationLocalAccess,
                    intent: CollaborationMessageIntent,
                ): AppResult<Boolean> {
                    preventions++
                    command =
                        CollaborationMessageRecord(intent, 0, CollaborationMessageState.Cancelled)
                    afterPrevent()
                    return AppResult.Success(true)
                }
            }
        val cancellations =
            object : CollaborationMessageCancellationRepository {
                override suspend fun cancel(
                    access: CollaborationLocalAccess,
                    expected: CollaborationMessageRecord,
                ): AppResult<Boolean> = error("No active message")
            }

        fun model() =
            SavedRepliesViewModel(
                "selection",
                input.id,
                ObserveCollaborationSavedReplies(
                    inputs,
                    base.local,
                    base.sessions,
                    base.workspaces,
                    base.server,
                    base.build,
                    base.clock,
                ),
                DiscardCollaborationNotificationReply(
                    inputs,
                    messages,
                    cancellations,
                    prevention,
                    base.local,
                    base.sessions,
                    base.workspaces,
                    base.server,
                    base.build,
                    base.clock,
                ),
            )

        private fun id(value: Int) = "00000000-0000-0000-0000-" + value.toString().padStart(12, '0')
    }
}
