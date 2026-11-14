package dev.fajar.starter.collaboration.presentation

import androidx.lifecycle.ViewModelStore
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.presentation.inbox.chatId
import dev.fajar.starter.collaboration.presentation.spacecontacts.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SpaceContactsViewModelTest {
    @Test
    fun nameSearchAndTabReentryReuseOneLocalSubscriptionWithExpiredCredentials() = runTest {
        withModel { f, model, _ ->
            assertEquals(
                listOf("Ana", "Bima", "Zara"),
                model.state.first { !it.loading }.contacts.map { it.displayName },
            )
            model.onEvent(SpaceContactsEvent.Entered)
            model.onEvent(SpaceContactsEvent.QueryChanged(" bI "))
            assertEquals(
                listOf("Bima"),
                model.state.first { it.appliedQuery == " bI " }.contacts.map { it.displayName },
            )
            model.onEvent(SpaceContactsEvent.Stopped)
            runCurrent()
            val retained = model.state.value.contacts
            f.thread.base.workspace.value = null
            val session = f.thread.base.initial
            f.thread.base.session.value =
                session.copy(tokens = session.tokens.copy(expiresAtEpochMillis = 0))
            model.onEvent(SpaceContactsEvent.Entered)
            runCurrent()
            assertSame(retained, model.state.value.contacts)
            assertEquals(1, f.starts)
            assertEquals(1, f.readers)
            assertFalse(model.state.value.loading)
        }
    }

    @Test
    fun retainedNamesSurviveReadFailureAndAnExplicitEmptySnapshotReplacesThem() = runTest {
        withModel { f, model, _ ->
            val retained = model.state.first { !it.loading }.contacts
            f.facts.value = AppResult.Failed(f.thread.storage)
            runCurrent()
            assertSame(retained, model.state.value.contacts)
            assertEquals(f.thread.storage, model.state.value.failure)
            f.facts.value =
                AppResult.Success(CollaborationSpaceDirectorySnapshot(null, emptyList()))
            val cleared = model.state.first { it.contacts.isEmpty() }
            assertFalse(cleared.loading)
            assertNull(cleared.failure)
        }
    }

    @Test
    fun denialClearsNamesAndFailedAdmissionCannotReopenThem() = runTest {
        withModel { f, model, _ ->
            model.state.first { !it.loading }
            f.thread.gate.value =
                AppResult.Success(
                    CollaborationSpaceDirectoryAdmission(chatId(921), f.thread.denial)
                )
            runCurrent()
            assertTrue(model.state.value.contacts.isEmpty())
            assertEquals(f.thread.denial, model.state.value.restriction)
            assertEquals(0, f.readers)
            f.thread.gate.value = AppResult.Failed(f.thread.storage)
            model.onEvent(SpaceContactsEvent.QueryChanged("Bima"))
            runCurrent()
            assertTrue(model.state.value.contacts.isEmpty())
            assertEquals(f.thread.denial, model.state.value.failure)
            assertEquals(0, f.readers)
            f.thread.gate.value =
                AppResult.Success(CollaborationSpaceDirectoryAdmission(null, null))
            val reopened = model.state.first { it.contacts.isNotEmpty() }
            assertNull(reopened.restriction)
            assertEquals(listOf("Bima"), reopened.contacts.map { it.displayName })
        }
    }

    @Test
    fun accountReplacementClearsTheOldDirectoryAndDoesNotReadForTheNewAccount() = runTest {
        withModel { f, model, _ ->
            model.state.first { !it.loading }
            val session = f.thread.base.initial
            f.thread.base.session.value =
                session.copy(id = "new", user = session.user.copy(id = chatId(922)))
            runCurrent()
            assertTrue(model.state.value.contacts.isEmpty())
            assertNotNull(model.state.value.restriction)
            assertEquals(0, f.readers)
            assertEquals(1, f.starts)
        }
    }

    @Test
    fun latestQueryWinsAndInvalidInputCannotStartAnotherSelection() = runTest {
        withModel { f, model, _ ->
            model.state.first { !it.loading }
            model.onEvent(SpaceContactsEvent.QueryChanged("Ana"))
            model.onEvent(SpaceContactsEvent.QueryChanged("Zara"))
            model.onEvent(SpaceContactsEvent.QueryChanged("Bima"))
            val found = model.state.first { it.appliedQuery == "Bima" }
            assertEquals(listOf("Bima"), found.contacts.map { it.displayName })
            model.onEvent(SpaceContactsEvent.QueryChanged("x".repeat(81)))
            model.onEvent(SpaceContactsEvent.QueryChanged("Bima\n"))
            runCurrent()
            assertEquals("Bima", model.state.value.query)
            assertEquals("Bima", model.state.value.appliedQuery)
            assertEquals(1, f.starts)
        }
    }

    @Test
    fun selectionUsesOnlyVisibleCurrentResultsAndDoubleTapEmitsOneEffect() = runTest {
        withModel { f, model, _ ->
            model.state.first { !it.loading }
            val effects = mutableListOf<SpaceContactsEffect>()
            val collector = backgroundScope.launch { model.effects.collect { effects += it } }
            try {
                model.onEvent(SpaceContactsEvent.ContactSelected(chatId(911)))
                model.onEvent(SpaceContactsEvent.Entered)
                model.onEvent(SpaceContactsEvent.QueryChanged("Bima"))
                model.state.first { it.appliedQuery == "Bima" }
                model.onEvent(SpaceContactsEvent.ContactSelected(chatId(912)))
                model.onEvent(SpaceContactsEvent.ContactSelected(chatId(911)))
                model.onEvent(SpaceContactsEvent.ContactSelected(chatId(911)))
                runCurrent()
                assertEquals(
                    listOf<SpaceContactsEffect>(SpaceContactsEffect.OpenContact(chatId(911))),
                    effects,
                )
                model.onEvent(SpaceContactsEvent.Entered)
                f.thread.gate.value =
                    AppResult.Success(
                        CollaborationSpaceDirectoryAdmission(chatId(923), f.thread.denial)
                    )
                runCurrent()
                model.onEvent(SpaceContactsEvent.ContactSelected(chatId(911)))
                runCurrent()
                assertEquals(1, effects.size)
            } finally {
                collector.cancel()
            }
        }
    }

    @Test
    fun inputListsAreCopiedBeforeRetentionAndNamesAreNotReacquiredOnSearch() = runTest {
        withModel { f, model, _ ->
            model.state.first { !it.loading }
            f.contacts.clear()
            f.companies.clear()
            model.onEvent(SpaceContactsEvent.QueryChanged("Bima"))
            val result = model.state.first { it.appliedQuery == "Bima" }
            assertEquals("Bima", result.contacts.single().displayName)
            assertEquals("Example Company", result.contacts.single().companies.single().name)
            assertTrue(result.contacts.single().hasMoreCompanies)
            assertEquals(1, f.starts)
        }
    }

    @Test
    fun disposingWhileFirstReadIsPendingRejectsLateNamesAndReleasesTheReader() = runTest {
        withModel { f, model, store ->
            val release = CompletableDeferred<Unit>()
            f.beforeRead = { withContext(NonCancellable) { release.await() } }
            runCurrent()
            assertEquals(1, f.readers)
            store.clear()
            release.complete(Unit)
            runCurrent()
            assertTrue(model.state.value.contacts.isEmpty())
            assertEquals(0, f.readers)
        }
    }

    private suspend fun TestScope.withModel(
        block:
            suspend TestScope.(SpaceContactsFixture, SpaceContactsViewModel, ViewModelStore) -> Unit
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val f = SpaceContactsFixture()
        val model = f.model()
        val store = ViewModelStore().also { it.put("contacts", model) }
        try {
            block(f, model, store)
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }
}
