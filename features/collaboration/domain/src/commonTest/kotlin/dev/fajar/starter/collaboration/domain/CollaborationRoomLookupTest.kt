package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class CollaborationRoomLookupTest {
    private val selected = CollaborationOperationScope.Selected("selection")
    private val roomId = "!room:example.test"

    @Test
    fun ownedRoomHintPublishesItsSummaryWithoutScanningOrCreatingAConversation() = runTest {
        val f = LocalListFixture()
        val result =
            assertIs<AppResult.Success<CollaborationRead<CollaborationDirectSummary>>>(
                f.refresh.byRoom(selected, roomId)
            )
        assertEquals(f.summary, result.value.value)
        assertEquals(listOf(roomId), f.roomCalls)
        assertEquals(0, f.remoteCalls)
        assertTrue(f.pointCalls.isEmpty())
        assertEquals(listOf(listOf(f.summary)), f.published)
    }

    @Test
    fun malformedRoomHintsCannotReadOrPublish() = runTest {
        val f = LocalListFixture()
        for (id in listOf("", "!", "alias", "!space room", "!line\n", "!" + "x".repeat(255))) {
            assertEquals(
                FailureKind.Validation,
                assertIs<AppResult.Failed>(f.refresh.byRoom(selected, id)).failure.kind,
            )
        }
        assertTrue(f.roomCalls.isEmpty())
        assertEquals(0, f.merges)
    }

    @Test
    fun lookupSharesThePagePublicationMutexAndCancelledWaitersNeverRead() = runTest {
        val f = LocalListFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.beforeRemoteReturn = {
            entered.complete(Unit)
            release.await()
        }
        val page = async { f.refresh("selection") }
        entered.await()
        val point = async { f.refresh.byRoom(selected, roomId) }
        runCurrent()
        assertTrue(f.roomCalls.isEmpty())
        point.cancelAndJoin()
        release.complete(Unit)
        assertIs<AppResult.Success<*>>(page.await())
        assertTrue(f.roomCalls.isEmpty())
        assertEquals(1, f.merges)
    }

    @Test
    fun failedLookupRetainsEarlierDenialUntilCurrentAdmittedAcquisition() = runTest {
        val f = LocalListFixture()
        f.pointResult = AppResult.Failed(f.denied)
        assertEquals(
            f.denied,
            assertIs<AppResult.Failed>(f.refresh.byRoom(selected, roomId)).failure,
        )
        f.pointResult = AppResult.Failed(f.network)
        assertEquals(
            f.denied,
            assertIs<AppResult.Failed>(f.refresh.byRoom(selected, roomId)).failure,
        )
        assertEquals(0, f.merges)
        f.pointResult = AppResult.Success(f.summary)
        assertIs<AppResult.Success<*>>(f.refresh.byRoom(selected, roomId))
        assertNull(f.gate.value.denial)
    }

    @Test
    fun accountReplacementOrCancellationRejectsLateSuccessfulLookup() = runTest {
        for (cancel in listOf(false, true)) {
            val f = LocalListFixture()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.beforeRemoteReturn = {
                withContext(NonCancellable) {
                    entered.complete(Unit)
                    release.await()
                }
            }
            val request = async { f.refresh.byRoom(selected, roomId) }
            entered.await()
            if (cancel) request.cancel() else f.setup.session = null
            release.complete(Unit)
            if (cancel) request.join() else assertIs<AppResult.Failed>(request.await())
            assertEquals(0, f.merges)
            assertEquals(0, f.admits)
        }
    }

    @Test
    fun receivedDenialSurvivesCancelledResponseAndFailedCleanup() = runTest {
        val f = LocalListFixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.pointResult = AppResult.Failed(f.denied)
        f.restrictResult = AppResult.Failed(f.storage)
        f.beforeRemoteReturn = {
            withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
            }
        }
        val request = async { f.refresh.byRoom(selected, roomId) }
        entered.await()
        request.cancel()
        release.complete(Unit)
        request.join()
        assertEquals(f.denied, f.gate.value.denial)
        assertEquals(0, f.merges)
        assertEquals(0, f.admits)
    }

    @Test
    fun aNewerDenialDuringCommitCannotBeClearedByAnOlderLookup() = runTest {
        val f = LocalListFixture()
        f.beforeMerge = { f.gate.value = CollaborationListAdmission("newer", f.denied) }
        assertEquals(
            f.denied,
            assertIs<AppResult.Failed>(f.refresh.byRoom(selected, roomId)).failure,
        )
        assertEquals(f.denied, f.gate.value.denial)
    }
}
