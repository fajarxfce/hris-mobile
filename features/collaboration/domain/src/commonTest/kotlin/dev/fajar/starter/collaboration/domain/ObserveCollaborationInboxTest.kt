@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.ObserveCollaborationInbox
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*

class ObserveCollaborationInboxTest {
    @Test
    fun savedNamesPublishBeforeSlowLocalDetailsWithExpiredCredentialsAndNoNetwork() = runTest {
        val f = InboxPreviewFixture()
        f.owner.setup.now += 365.days
        val release = CompletableDeferred<Unit>()
        f.beforeHistory = { release.await() }
        val values = mutableListOf<AppResult<CollaborationInboxPage>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe().collect(values::add)
            }
        runCurrent()
        assertEquals(
            f.rows.value,
            assertIs<AppResult.Success<CollaborationInboxPage>>(values.last()).value.list.items,
        )
        assertTrue((values.last() as AppResult.Success).value.previews.isEmpty())
        release.complete(Unit)
        runCurrent()
        assertEquals(f.message, (values.last() as AppResult.Success).value.previews[f.id]!!.message)
        assertEquals(0, f.owner.setup.calls)
        assertTrue(f.owner.scheduledKeys.isEmpty())
        job.cancelAndJoin()
        assertEquals(0, f.activeHistory)
        assertEquals(0, f.draft.observers)
    }

    @Test
    fun hiddenSelectionAndConfirmedDenialCloseChildrenAndFreshAdmissionReopens() = runTest {
        val f = InboxPreviewFixture()
        val values = mutableListOf<AppResult<CollaborationInboxPage>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe().collect(values::add)
            }
        runCurrent()
        assertEquals(1, f.activeHistory)
        f.selection.value = CollaborationInboxSelection(includePreviews = false)
        runCurrent()
        assertEquals(0, f.activeHistory)
        assertEquals(0, f.draft.observers)
        f.selection.value = CollaborationInboxSelection()
        runCurrent()
        assertEquals(1, f.activeHistory)
        f.owner.gate.value = CollaborationListAdmission("denied", f.owner.denied)
        runCurrent()
        assertEquals(AppResult.Failed(f.owner.denied), values.last())
        assertEquals(0, f.activeHistory)
        assertEquals(0, f.draft.observers)
        f.owner.gate.value = CollaborationListAdmission("admitted", null)
        runCurrent()
        assertEquals(f.message, (values.last() as AppResult.Success).value.previews[f.id]!!.message)
        job.cancelAndJoin()
        assertEquals(0, f.activeHistory)
    }

    @Test
    fun accountReplacementDuringLocalReadCannotPublishThePreviousAccountsPreview() = runTest {
        val f = InboxPreviewFixture()
        f.beforeHistory = {
            f.owner.setup.session = f.owner.setup.session!!.copy(id = "replacement")
        }
        val last = f.observe().first { it is AppResult.Failed }
        assertEquals("client.session_changed", assertIs<AppResult.Failed>(last).failure.code)
        assertEquals(0, f.activeHistory)
        assertEquals(0, f.draft.observers)
    }

    @Test
    fun corruptOptionalContentKeepsNamesAndCanRecoverLocally() = runTest {
        val f = InboxPreviewFixture()
        f.historyPage.value = AppResult.Failed(f.owner.storage)
        val values = mutableListOf<AppResult<CollaborationInboxPage>>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.observe().collect(values::add)
            }
        runCurrent()
        val failed = assertIs<AppResult.Success<CollaborationInboxPage>>(values.last()).value
        assertEquals(f.rows.value, failed.list.items)
        assertEquals(f.owner.storage, failed.previewFailure)
        assertTrue(failed.previews.isEmpty())
        f.historyPage.value = AppResult.Success(f.saved)
        runCurrent()
        assertEquals(f.message, (values.last() as AppResult.Success).value.previews[f.id]!!.message)
        assertEquals(0, f.owner.setup.calls)
        job.cancelAndJoin()
    }

    @Test
    fun foreignDuplicateAndOversizedPagesCannotOpenContentObservers() = runTest {
        for (mode in 0..2) {
            val f = InboxPreviewFixture()
            val original = f.rows.value.single()
            f.rows.value =
                when (mode) {
                    0 ->
                        listOf(
                            original.copy(
                                conversation = original.conversation.copy(companyId = "foreign")
                            )
                        )
                    1 -> listOf(original, original)
                    else -> List(51) { original }
                }
            assertIs<AppResult.Failed>(f.observe().first())
            assertEquals(0, f.historyStarts)
            assertEquals(0, f.draft.observers)
        }
    }

    @Test
    fun wrongDraftAndUnboundedHistoryCannotAppearInThePreview() = runTest {
        for (wrongDraft in listOf(false, true)) {
            val f = InboxPreviewFixture()
            if (wrongDraft)
                f.draft.slot.value =
                    CollaborationDraft("foreign", "revision", "Private text", f.owner.setup.now)
            else
                f.historyPage.value =
                    AppResult.Success(f.saved.copy(messages = listOf(f.message, f.message)))
            val last =
                f.observe().first { (it as? AppResult.Success)?.value?.previewFailure != null }
            assertTrue((last as AppResult.Success).value.previews.isEmpty())
        }
    }

    @Test
    fun cancellationWaitsForUncooperativeReadAndCannotPublishItsLateContent() = runTest {
        val f = InboxPreviewFixture()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.beforeHistory = {
            withContext(NonCancellable) {
                started.complete(Unit)
                release.await()
            }
        }
        val values = mutableListOf<AppResult<CollaborationInboxPage>>()
        val job = launch { f.observe().collect(values::add) }
        started.await()
        job.cancel()
        runCurrent()
        assertEquals(1, f.activeHistory)
        release.complete(Unit)
        job.join()
        assertEquals(0, f.activeHistory)
        assertEquals(0, f.draft.observers)
        assertTrue(values.all { (it as AppResult.Success).value.previews.isEmpty() })
    }
}

private class InboxPreviewFixture {
    val draft = DraftIntentFixture()
    val owner = draft.f
    val id = owner.summary!!.conversation.id
    val rows = MutableStateFlow(listOf(owner.summary!!))
    val selection = MutableStateFlow(CollaborationInboxSelection())
    val message =
        CollaborationHistoryMessage(
            "@peer:example.test",
            null,
            "\$original",
            1,
            "a".repeat(64),
            "Saved message",
            listOf("\$original"),
            false,
        )
    val saved = CollaborationHistoryPage(listOf(message), null, roomId = "!room:example.test")
    val historyPage =
        MutableStateFlow<AppResult<CollaborationHistoryPage>>(AppResult.Success(saved))
    var activeHistory = 0
    var historyStarts = 0
    var beforeHistory: suspend () -> Unit = {}
    val lists =
        object : CollaborationListRepository by owner.lists {
            override fun observe(
                access: CollaborationLocalAccess,
                selection: CollaborationListSelection,
            ) = rows.map { AppResult.Success(CollaborationLocalList(null, it, null)) }
        }
    val history =
        object : CollaborationHistoryRepository {
            override fun observe(
                access: CollaborationLocalAccess,
                conversationId: String,
                roomId: String?,
                after: String?,
                limit: Int,
                before: String?,
                overlap: Int,
            ) = flow {
                assertEquals(id, conversationId)
                assertNull(roomId)
                assertNull(after)
                assertNull(before)
                assertEquals(1, limit)
                assertEquals(0, overlap)
                historyStarts++
                activeHistory++
                try {
                    beforeHistory()
                    emitAll(historyPage)
                } finally {
                    activeHistory--
                }
            }

            override suspend fun getEventFact(
                access: CollaborationLocalAccess,
                conversationId: String,
                roomId: String,
                eventId: String,
            ): AppResult<CollaborationHistoryEventFact?> = error("No event lookup")

            override suspend fun getByEvent(
                access: CollaborationLocalAccess,
                conversationId: String,
                roomId: String,
                eventId: String,
            ): AppResult<CollaborationHistoryMessage?> = error("No event lookup")

            override suspend fun getByLogicalId(
                access: CollaborationLocalAccess,
                conversationId: String,
                roomId: String,
                sender: String,
                logicalId: String,
            ): AppResult<CollaborationHistoryMessage?> = error("No lookup")

            override suspend fun merge(
                access: CollaborationLocalAccess,
                conversationId: String,
                roomId: String,
                changes: List<CollaborationHistoryChange>,
            ): AppResult<Boolean> = error("No acquisition")
        }
    private val observer =
        ObserveCollaborationInbox(
            lists,
            history,
            draft.drafts,
            owner.setup.sessions,
            owner.setup.workspaces,
            owner.server,
            owner.build,
            owner.clock,
        )

    fun observe() = observer("selection", selection)
}
