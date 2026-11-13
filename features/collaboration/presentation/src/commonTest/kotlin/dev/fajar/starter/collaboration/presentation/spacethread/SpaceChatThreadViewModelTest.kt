@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.presentation.spacethread

import androidx.lifecycle.ViewModelStore
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*

class SpaceChatThreadViewModelTest {
    @Test
    fun historyPublishesWhileDraftReadIsStillPending() = runTest {
        withModel { f, vm, _, _ ->
            val release = CompletableDeferred<Unit>()
            f.beforeDraftObservation = { release.await() }
            vm.onEvent(SpaceChatThreadEvent.Entered)
            runCurrent()
            assertIs<CollaborationSpaceThreadSnapshot.Unresolved>(vm.state.value.thread)
            assertFalse(vm.state.value.loading)
            assertFalse(vm.state.value.draftReady)
            assertEquals(0, f.schedules)
            release.complete(Unit)
            runCurrent()
            assertTrue(vm.state.value.canCompose)
        }
    }

    @Test
    fun localEditorDoesNotWaitForThreadMetadataOrNativeKeys() = runTest {
        withModel { f, vm, _, _ ->
            val release = CompletableDeferred<Unit>()
            f.beforeMetadata = { release.await() }
            vm.onEvent(SpaceChatThreadEvent.Entered)
            runCurrent()
            assertNull(vm.state.value.thread)
            assertTrue(vm.state.value.loading)
            assertTrue(vm.state.value.canCompose)
            vm.onEvent(SpaceChatThreadEvent.DraftChanged("Kept while history opens"))
            runCurrent()
            assertEquals("Kept while history opens", vm.state.value.draft)
            assertEquals(0, f.schedules)
            release.complete(Unit)
            runCurrent()
        }
    }

    @Test
    fun preparationAndReentryKeepOneLocalSubscriptionWithoutAcquisition() = runTest {
        withModel { f, vm, _, _ ->
            vm.onEvent(SpaceChatThreadEvent.Prepared)
            runCurrent()
            val prepared = vm.state.value.thread
            assertNotNull(prepared)
            vm.onEvent(SpaceChatThreadEvent.Entered)
            vm.onEvent(SpaceChatThreadEvent.Entered)
            vm.onEvent(SpaceChatThreadEvent.Stopped)
            vm.onEvent(SpaceChatThreadEvent.Entered)
            runCurrent()
            assertSame(prepared, vm.state.value.thread)
            assertEquals(1, f.content.bindingReaders)
            assertEquals(1, f.draftStarts)
            assertEquals(1, f.outboxReaders)
            assertEquals(0, f.schedules)
        }
    }

    @Test
    fun consecutiveSendsShowImmediatelyAndKeepNewerTypingDuringLocalWrites() = runTest {
        withModel { f, vm, _, _ ->
            val release = CompletableDeferred<Unit>()
            f.beforeHandoff = { release.await() }
            vm.onEvent(SpaceChatThreadEvent.Entered)
            runCurrent()
            vm.onEvent(SpaceChatThreadEvent.DraftChanged("A"))
            vm.onEvent(SpaceChatThreadEvent.SendRequested)
            runCurrent()
            assertEquals("", vm.state.value.draft)
            val first = vm.state.value.provisional.single().original
            assertEquals("A", first.original.text)
            assertTrue(vm.state.value.canCompose)
            assertTrue(f.handoffs.isEmpty())
            vm.onEvent(SpaceChatThreadEvent.DraftChanged("B"))
            vm.onEvent(SpaceChatThreadEvent.SendRequested)
            vm.onEvent(SpaceChatThreadEvent.DraftChanged("Keep C"))
            runCurrent()
            val originals = vm.state.value.provisional.map { it.original }
            assertEquals(listOf("A", "B"), originals.map { it.original.text })
            assertTrue(originals[0].submittedAt < originals[1].submittedAt)
            assertEquals("Keep C", vm.state.value.draft)
            release.complete(Unit)
            advanceUntilIdle()
            assertEquals(originals, f.handoffs)
            assertEquals("Keep C", f.draft.value?.text)
            assertTrue(vm.state.value.provisional.isEmpty())
            assertEquals(originals, vm.state.value.messages.pending.map { it.original })
            assertTrue(vm.state.value.messages.pending.all { it.durable })
            assertEquals(0, f.original.deliveries)
        }
    }

    @Test
    fun companySelectionAndExpiredCredentialsDoNotReplaceTheGroupEditor() = runTest {
        withModel { f, vm, _, _ ->
            val company = f.base.workspace.value
            f.base.workspace.value = null
            f.base.session.value =
                f.base.initial.copy(tokens = f.base.initial.tokens.copy(expiresAtEpochMillis = 0))
            vm.onEvent(SpaceChatThreadEvent.Entered)
            runCurrent()
            assertTrue(vm.state.value.canCompose)
            vm.onEvent(SpaceChatThreadEvent.DraftChanged("Organization draft"))
            runCurrent()
            val before = vm.state.value
            f.base.workspace.value = company
            f.base.session.value =
                f.base.initial.copy(tokens = f.base.initial.tokens.copy(accessToken = "rotated"))
            runCurrent()
            assertEquals(before, vm.state.value)
            assertEquals(1, f.draftStarts)
        }
    }

    @Test
    fun aRetainedStoppedRouteStillRedactsWhenTheAccountLogsOut() = runTest {
        withModel { f, vm, _, _ ->
            vm.onEvent(SpaceChatThreadEvent.Entered)
            runCurrent()
            vm.onEvent(SpaceChatThreadEvent.DraftChanged("Private local text"))
            runCurrent()
            vm.onEvent(SpaceChatThreadEvent.Stopped)
            runCurrent()
            f.base.session.value = null
            runCurrent()
            assertEquals(FailureKind.Unauthorized, vm.state.value.restriction?.kind)
            assertNull(vm.state.value.thread)
            assertNull(vm.state.value.composer)
            assertEquals("", vm.state.value.draft)
            assertEquals(0, f.draftReaders + f.outboxReaders + f.content.bindingReaders)
        }
    }

    @Test
    fun lateThreadMetadataCannotRestoreContentAfterGroupDenial() = runTest {
        withModel { f, vm, _, _ ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.beforeMetadata = {
                withContext(NonCancellable) {
                    entered.complete(Unit)
                    release.await()
                }
            }
            vm.onEvent(SpaceChatThreadEvent.Entered)
            runCurrent()
            entered.await()
            assertTrue(vm.state.value.canCompose)
            f.deny()
            runCurrent()
            assertEquals(f.denial, vm.state.value.restriction)
            release.complete(Unit)
            runCurrent()
            assertNull(vm.state.value.thread)
            assertFalse(vm.state.value.canCompose)
            assertEquals(0, f.content.bindingReaders)
        }
    }

    @Test
    fun failedAdmissionKeepsTheRestrictionUntilExplicitAdmittedReentry() = runTest {
        withModel { f, vm, _, _ ->
            vm.onEvent(SpaceChatThreadEvent.Entered)
            runCurrent()
            f.deny()
            runCurrent()
            f.gate.value = AppResult.Failed(f.storage)
            vm.onEvent(SpaceChatThreadEvent.Stopped)
            vm.onEvent(SpaceChatThreadEvent.Entered)
            runCurrent()
            assertEquals(f.denial, vm.state.value.restriction)
            assertFalse(vm.state.value.canCompose)
            f.gate.value = AppResult.Success(CollaborationSpaceDirectoryAdmission(null, null))
            // An admitted observation is allowed to publish; arbitrary storage failure is not.
            runCurrent()
            assertNull(vm.state.value.restriction)
            assertTrue(vm.state.value.canCompose)
        }
    }

    @Test
    fun clearingDuringLostHandoffReplyKeepsOneOriginalForTheReplacementModel() = runTest {
        withModel { f, vm, store, _ ->
            val committed = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.afterHandoff = {
                withContext(NonCancellable) {
                    committed.complete(Unit)
                    release.await()
                }
            }
            vm.onEvent(SpaceChatThreadEvent.Entered)
            runCurrent()
            vm.onEvent(SpaceChatThreadEvent.DraftChanged("Original"))
            vm.onEvent(SpaceChatThreadEvent.SendRequested)
            runCurrent()
            committed.await()
            val captured = f.handoffs.single()
            val last = vm.state.value
            store.clear()
            runCurrent()
            release.complete(Unit)
            runCurrent()
            assertEquals(last, vm.state.value)
            assertEquals(
                0,
                f.draftReaders + f.outboxReaders + f.recordReaders + f.content.bindingReaders,
            )
            val next = f.model()
            store.put("next", next)
            next.onEvent(SpaceChatThreadEvent.Entered)
            runCurrent()
            assertEquals(captured, next.state.value.composer?.submissions?.single()?.submission)
            assertTrue(next.state.value.canCompose)
            assertEquals(1, f.handoffs.size)
        }
    }

    @Test
    fun backWaitsForLocalDraftAndAWriteFailureKeepsTheEditorOpen() = runTest {
        withModel { f, vm, _, effects ->
            vm.onEvent(SpaceChatThreadEvent.Entered)
            runCurrent()
            f.editFailure = f.storage
            vm.onEvent(SpaceChatThreadEvent.DraftChanged("Unsaved text"))
            vm.onEvent(SpaceChatThreadEvent.BackRequested)
            advanceUntilIdle()
            runCurrent()
            assertTrue(effects.isEmpty())
            assertEquals("Unsaved text", vm.state.value.draft)
            assertFalse(vm.state.value.leaving)
            f.editFailure = null
            vm.onEvent(SpaceChatThreadEvent.DraftRetryRequested)
            vm.onEvent(SpaceChatThreadEvent.BackRequested)
            advanceUntilIdle()
            runCurrent()
            assertTrue(vm.state.value.leaving)
            assertNull(vm.state.value.draftFailure)
            assertEquals(listOf<SpaceChatThreadEffect>(SpaceChatThreadEffect.Back), effects)
            assertEquals("Unsaved text", f.draft.value?.text)
            vm.onEvent(SpaceChatThreadEvent.Stopped)
            vm.onEvent(SpaceChatThreadEvent.Entered)
            runCurrent()
            assertFalse(vm.state.value.leaving)
            assertTrue(vm.state.value.canCompose)
            assertEquals("Unsaved text", vm.state.value.draft)
        }
    }

    @Test
    fun anOutboxLoadedAtEntryRemainsVisibleAcrossItsRemovalBeforeTheCommandAppears() = runTest {
        withModel { f, vm, _, _ ->
            val original = f.submission()
            f.entries.value = listOf(CollaborationComposerOutboxEntry(original, false))
            vm.onEvent(SpaceChatThreadEvent.Entered)
            runCurrent()
            assertEquals(original, vm.state.value.messages.pending.single().original)
            f.entries.value = emptyList()
            runCurrent()
            assertEquals(original, vm.state.value.messages.pending.single().original)
            assertNull(vm.state.value.messages.pending.single().record)
            f.archived.value = listOf(f.sent(original))
            runCurrent()
            assertEquals(f.sent(original), vm.state.value.messages.pending.single().record)
            assertTrue(vm.state.value.provisional.isEmpty())
            assertEquals(1, f.recordReaders)
            assertEquals(0, f.schedules)
        }
    }

    @Test
    fun aPendingProgressReadDoesNotDelayHistoryEditorOrTheNextGesture() = runTest {
        withModel { f, vm, _, _ ->
            val first = f.submission()
            f.entries.value = listOf(CollaborationComposerOutboxEntry(first, false))
            val release = CompletableDeferred<Unit>()
            f.beforeRecord = { release.await() }
            vm.onEvent(SpaceChatThreadEvent.Entered)
            runCurrent()
            assertNotNull(vm.state.value.thread)
            assertTrue(vm.state.value.canCompose)
            assertEquals(first, vm.state.value.messages.pending.single().original)
            vm.onEvent(SpaceChatThreadEvent.DraftChanged("Second"))
            vm.onEvent(SpaceChatThreadEvent.SendRequested)
            runCurrent()
            assertEquals(
                setOf("Retained original 1", "Second"),
                vm.state.value.messages.pending.map { it.original.original.text }.toSet(),
            )
            assertEquals("", vm.state.value.draft)
            release.complete(Unit)
            runCurrent()
            assertEquals(2, vm.state.value.messages.pending.size)
            assertEquals(0, f.original.deliveries)
        }
    }

    @Test
    fun archivedCommandEvidenceStillPublishesWhileThreadAcquisitionIsPending() = runTest {
        withModel { f, vm, _, _ ->
            val original = f.submission()
            f.entries.value = listOf(CollaborationComposerOutboxEntry(original, false))
            f.archived.value = listOf(f.sent(original))
            val release = CompletableDeferred<Unit>()
            f.beforeMetadata = { release.await() }
            vm.onEvent(SpaceChatThreadEvent.Entered)
            runCurrent()
            assertNull(vm.state.value.thread)
            assertEquals(f.sent(original), vm.state.value.messages.pending.single().record)
            assertTrue(vm.state.value.canCompose)
            assertEquals(0, f.schedules)
            release.complete(Unit)
            runCurrent()
        }
    }

    @Test
    fun companyChangesKeepTheSameGroupProgressReaderAndRetainedOriginal() = runTest {
        withModel { f, vm, _, _ ->
            val original = f.submission()
            f.entries.value = listOf(CollaborationComposerOutboxEntry(original, false))
            vm.onEvent(SpaceChatThreadEvent.Entered)
            runCurrent()
            val before = vm.state.value.messages
            f.base.workspace.value = null
            f.base.session.value =
                f.base.initial.copy(tokens = f.base.initial.tokens.copy(expiresAtEpochMillis = 0))
            runCurrent()
            assertEquals(before, vm.state.value.messages)
            assertEquals(1, f.recordReaders)
            assertTrue(vm.state.value.canCompose)
            assertEquals(0, f.schedules)
        }
    }

    @Test
    fun logoutDuringALateProgressReadCannotRestoreTheOriginalOrItsStatus() = runTest {
        withModel { f, vm, _, _ ->
            val original = f.submission()
            f.entries.value = listOf(CollaborationComposerOutboxEntry(original, false))
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.beforeRecord = {
                withContext(NonCancellable) {
                    entered.complete(Unit)
                    release.await()
                }
            }
            vm.onEvent(SpaceChatThreadEvent.Entered)
            runCurrent()
            entered.await()
            f.base.session.value = null
            runCurrent()
            assertEquals(FailureKind.Unauthorized, vm.state.value.restriction?.kind)
            assertTrue(vm.state.value.messages.pending.isEmpty())
            f.archived.value = listOf(f.sent(original))
            release.complete(Unit)
            runCurrent()
            assertTrue(vm.state.value.messages.pending.isEmpty())
            assertTrue(vm.state.value.messages.represented.isEmpty())
            assertEquals(0, f.recordReaders)
            assertNull(vm.state.value.thread)
        }
    }

    @Test
    fun failedProgressReadsKeepTheOriginalAndRetryObservesTheSameStoredCommand() = runTest {
        withModel { f, vm, _, _ ->
            val original = f.submission()
            f.entries.value = listOf(CollaborationComposerOutboxEntry(original, false))
            f.recordFailure = f.storage
            vm.onEvent(SpaceChatThreadEvent.Entered)
            runCurrent()
            assertEquals(f.storage, vm.state.value.progressFailure)
            assertEquals(original, vm.state.value.messages.pending.single().original)
            assertTrue(vm.state.value.canCompose)
            f.recordFailure = null
            f.archived.value = listOf(f.sent(original))
            vm.onEvent(SpaceChatThreadEvent.DraftRetryRequested)
            runCurrent()
            assertNull(vm.state.value.progressFailure)
            assertEquals(f.sent(original), vm.state.value.messages.pending.single().record)
            assertEquals(1, f.recordReaders)
            assertEquals(0, f.schedules)
        }
    }

    private suspend fun TestScope.withModel(
        block:
            suspend TestScope.(
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
