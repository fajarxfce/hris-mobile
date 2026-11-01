package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationHistoryRepository
import dev.fajar.starter.collaboration.domain.usecases.ObserveCollaborationHistory
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest

class ObserveCollaborationHistoryTest {
    @Test
    fun expiredCredentialsOpenOwnedHistoryWithoutKnowingTheSdkRoom() = runTest {
        val f = HistoryObserverFixture()
        f.owner.setup.now += 365.days
        assertEquals(AppResult.Success(f.page), f.observe().first())
        assertEquals(1, f.started)
        assertEquals(0, f.active)
    }

    @Test
    fun knownDenialAndFailedGateReadNeverStartContentObservation() = runTest {
        val f = HistoryObserverFixture()
        f.owner.gate.value = CollaborationListAdmission("denied", f.owner.denied)
        assertEquals(AppResult.Failed(f.owner.denied), f.observe().first())
        f.owner.gateFailure = f.owner.storage
        assertEquals(AppResult.Failed(f.owner.storage), f.observe().first())
        assertEquals(0, f.started)
    }

    @Test
    fun accountReplacementDuringFinalGateReadCannotPublishAnOldPage() = runTest {
        val f = HistoryObserverFixture()
        f.owner.beforeAdmission = { f.owner.setup.session = null }
        assertIs<AppResult.Failed>(f.observe().first())
        assertEquals(1, f.started)
        assertEquals(0, f.active)
    }

    @Test
    fun lateDenialWinsOverFailedSessionAndRetainsTheOriginalPage() = runTest {
        val f = HistoryObserverFixture()
        f.beforeRead = {
            f.owner.gate.value = CollaborationListAdmission("denied", f.owner.denied)
            f.owner.setup.sessionFailure = f.owner.storage
        }
        assertEquals(AppResult.Failed(f.owner.denied), f.observe().first())
        assertEquals(0, f.active)
        assertEquals("Owned offline content", f.page.messages.single().text)
    }

    @Test
    fun denialCancelsPendingStorageAndOnlyAdmissionReopensObservation() = runTest {
        val f = HistoryObserverFixture()
        val entered = CompletableDeferred<Unit>()
        val denied = CompletableDeferred<Unit>()
        val resumed = CompletableDeferred<Unit>()
        f.beforeRead = {
            entered.complete(Unit)
            awaitCancellation()
        }
        val pending = launch {
            f.observe().collect {
                if (it is AppResult.Failed) denied.complete(Unit) else resumed.complete(Unit)
            }
        }
        entered.await()
        f.owner.gate.value = CollaborationListAdmission("denied", f.owner.denied)
        denied.await()
        assertEquals(0, f.active)
        assertFalse(resumed.isCompleted)
        f.beforeRead = {}
        f.owner.gate.value = CollaborationListAdmission("admitted", null)
        resumed.await()
        assertEquals(1, f.active)
        pending.cancelAndJoin()
        assertEquals(0, f.active)
    }

    @Test
    fun invalidSelectionNeverStartsStorage() = runTest {
        val f = HistoryObserverFixture()
        assertIs<AppResult.Failed>(f.observer("selection", "invalid").first())
        assertIs<AppResult.Failed>(f.observer("selection", f.conversation, limit = 51).first())
        assertIs<AppResult.Failed>(
            f.observer("selection", f.conversation, after = "invalid").first()
        )
        assertEquals(0, f.started)
    }
}

private class HistoryObserverFixture {
    val owner = MessageIntentFixture()
    val conversation = owner.summary!!.conversation.id
    val page =
        CollaborationHistoryPage(
            listOf(
                CollaborationHistoryMessage(
                    "@fictional:example.test",
                    null,
                    "\$event",
                    1,
                    "a".repeat(64),
                    "Owned offline content",
                    listOf("\$event"),
                    false,
                )
            ),
            null,
        )
    var started = 0
    var active = 0
    var beforeRead: suspend () -> Unit = {}
    val history =
        object : CollaborationHistoryRepository {
            override fun observe(
                access: CollaborationLocalAccess,
                conversationId: String,
                roomId: String?,
                after: String?,
                limit: Int,
            ) = flow {
                assertEquals(conversation, conversationId)
                assertNull(roomId, "Offline history requires no SDK or HTTP room discovery")
                started++
                active++
                try {
                    beforeRead()
                    emit(AppResult.Success(page))
                    awaitCancellation()
                } finally {
                    active--
                }
            }

            override suspend fun getByEvent(
                access: CollaborationLocalAccess,
                conversationId: String,
                roomId: String,
                eventId: String,
            ): AppResult<CollaborationHistoryMessage?> = error("No lookup")

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
    val observer =
        ObserveCollaborationHistory(
            history,
            owner.lists,
            owner.setup.sessions,
            owner.setup.workspaces,
            owner.server,
            owner.build,
            owner.clock,
        )

    fun observe() = observer("selection", conversation)
}
