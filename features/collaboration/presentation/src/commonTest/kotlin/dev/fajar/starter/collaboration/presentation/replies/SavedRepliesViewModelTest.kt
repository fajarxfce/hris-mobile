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

    @Test
    fun editedAndEmptyDraftsRestoreAfterStopWithoutCreatingCommands() = runTest {
        withModel { f, vm ->
            vm.onEvent(SavedRepliesEvent.Entered)
            runCurrent()
            for (text in listOf("Edited fictional reply", "")) {
                vm.onEvent(SavedRepliesEvent.DraftChanged(text))
                runCurrent()
                assertEquals(text, f.retained.value?.draft)
                assertFalse(vm.state.value.unsavedDraft)
                vm.onEvent(SavedRepliesEvent.Stopped)
                runCurrent()
                assertNull(vm.state.value.draft)
                vm.onEvent(SavedRepliesEvent.Entered)
                runCurrent()
                assertEquals(text, vm.state.value.draft)
            }
            assertNull(f.command)
            assertTrue(f.editedCommands.isEmpty())
        }
    }

    @Test
    fun threeRapidEditsJoinAllPredecessorsAndAnObservationCannotReplaceTyping() = runTest {
        withModel { f, vm ->
            val release = CompletableDeferred<Unit>()
            f.beforeSave = { if (it == "First") withContext(NonCancellable) { release.await() } }
            vm.onEvent(SavedRepliesEvent.Entered)
            runCurrent()
            vm.onEvent(SavedRepliesEvent.DraftChanged("First"))
            runCurrent()
            vm.onEvent(SavedRepliesEvent.DraftChanged("Second"))
            runCurrent()
            vm.onEvent(SavedRepliesEvent.DraftChanged("Latest"))
            runCurrent()
            assertTrue(f.edits.isEmpty())
            assertEquals("Latest", vm.state.value.draft)
            release.complete(Unit)
            runCurrent()
            assertEquals(listOf("First", "Latest"), f.edits)
            assertEquals("Latest", vm.state.value.draft)
            assertEquals("Latest", f.retained.value?.draft)
            assertFalse(vm.state.value.unsavedDraft)
        }
    }

    @Test
    fun failedDraftRequiresExplicitLeaveAndRetainsLastSavedCopy() = runTest {
        withModel { f, vm ->
            f.afterSave = { delay(8_001) }
            vm.onEvent(SavedRepliesEvent.Entered)
            runCurrent()
            vm.onEvent(SavedRepliesEvent.DraftChanged("Private working copy"))
            runCurrent()
            vm.onEvent(SavedRepliesEvent.BackRequested)
            runCurrent()
            assertTrue(vm.state.value.leaveConfirmation)
            vm.onEvent(SavedRepliesEvent.LeaveDismissed)
            runCurrent()
            assertFalse(vm.state.value.leaveConfirmation)
            assertEquals("Private working copy", vm.state.value.draft)
            advanceTimeBy(8_001)
            runCurrent()
            assertNotNull(vm.state.value.draftFailure)
            assertFalse(vm.state.value.savingDraft)
        }
    }

    @Test
    fun explicitSendUsesLatestTextAndClearsPrivateEditorAfterRetirement() = runTest {
        withModel { f, vm ->
            vm.onEvent(SavedRepliesEvent.Entered)
            runCurrent()
            vm.onEvent(SavedRepliesEvent.DraftChanged("Explicit edited reply"))
            runCurrent()
            vm.onEvent(SavedRepliesEvent.SendRequested)
            runCurrent()
            val result =
                assertIs<CollaborationReviewedReplyResult.CommandRetained>(
                    vm.state.value.sendOutcome
                )
            assertEquals(CollaborationMessageState.Queued, result.state)
            assertEquals("Explicit edited reply", f.editedCommands.values.single().intent.text)
            assertEquals(CollaborationMessageState.Cancelled, f.command?.state)
            assertNull(f.retained.value)
            assertNull(vm.state.value.draft)
            assertFalse(vm.state.value.busy)
        }
    }

    @Test
    fun changedNativeFamilyWithTheSameLocalSessionClearsEditorBeforeJoiningOldSave() = runTest {
        withModel { f, vm ->
            val release = CompletableDeferred<Unit>()
            var joined = false
            f.beforeSave = {
                withContext(NonCancellable) {
                    release.await()
                    joined = true
                }
            }
            vm.onEvent(SavedRepliesEvent.Entered)
            runCurrent()
            val previousAccess = vm.state.value.snapshot!!.access
            vm.onEvent(SavedRepliesEvent.DraftChanged("Private old editor"))
            runCurrent()
            val family = "00000000-0000-0000-0000-000000000777"
            f.base.session.value =
                f.base.initial.copy(binding = f.base.initial.binding.copy(serverId = family))
            f.base.workspace.value =
                f.base.workspace.value!!.let {
                    it.copy(admission = it.admission.copy(familyId = family))
                }
            runCurrent()
            assertNull(vm.state.value.draft)
            assertNull(vm.state.value.snapshot)
            assertFalse(joined)
            release.complete(Unit)
            runCurrent()
            assertTrue(joined)
            assertEquals(previousAccess, vm.state.value.snapshot?.access)
            assertEquals(family, vm.state.value.snapshot?.parent?.nativeSessionId)
            assertNull(vm.state.value.actionFailure)
            assertTrue(f.editedCommands.isEmpty())
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
        var beforeSave: suspend (String) -> Unit = {}
        var afterSave: suspend () -> Unit = {}
        val edits = mutableListOf<String>()
        val editedCommands = mutableMapOf<String, CollaborationMessageRecord>()
        val resolutions =
            object : CollaborationNotificationReplyResolutionRepository {
                override suspend fun saveDraft(
                    access: CollaborationRecoveryAccess,
                    expected: CollaborationNotificationReplyInput,
                    text: String,
                ): AppResult<Boolean> {
                    beforeSave(text)
                    if (retained.value != expected) return AppResult.Success(false)
                    edits += text
                    retained.value =
                        expected.copy(
                            version =
                                expected.version.copy(revision = expected.version.revision + 1),
                            draft = text,
                        )
                    afterSave()
                    return AppResult.Success(true)
                }

                override suspend fun confirm(
                    access: CollaborationRecoveryAccess,
                    expected: CollaborationNotificationReplyInput,
                    resolution: CollaborationNotificationReplyResolution,
                ): AppResult<Boolean> {
                    if (retained.value != expected) return AppResult.Success(false)
                    retained.value =
                        expected.copy(
                            version =
                                expected.version.copy(revision = expected.version.revision + 1),
                            draft = null,
                            review = null,
                            resolution = resolution,
                        )
                    return AppResult.Success(true)
                }
            }

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
                    AppResult.Success(command?.takeIf { it.intent.id == id } ?: editedCommands[id])

                override suspend fun insert(
                    access: CollaborationLocalAccess,
                    intent: CollaborationMessageIntent,
                ): AppResult<CollaborationMessageInsertion> {
                    if (intent.id !in editedCommands)
                        editedCommands[intent.id] =
                            CollaborationMessageRecord(intent, 0, CollaborationMessageState.Queued)
                    return AppResult.Success(CollaborationMessageInsertion.STORED)
                }

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
                SaveCollaborationReplyDraft(
                    inputs,
                    resolutions,
                    base.local,
                    base.sessions,
                    base.workspaces,
                    base.server,
                    base.build,
                    base.clock,
                ),
                ConfirmCollaborationReply(
                    inputs,
                    resolutions,
                    base.local,
                    base.sessions,
                    base.workspaces,
                    base.server,
                    base.build,
                    base.clock,
                ),
                ResumeCollaborationReviewedReply(
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
                    object : dev.fajar.starter.sync.domain.repositories.SyncScheduleRepository {
                        override suspend fun request(key: String) = AppResult.Success(Unit)
                    },
                ),
            )

        private fun id(value: Int) = "00000000-0000-0000-0000-" + value.toString().padStart(12, '0')
    }
}
