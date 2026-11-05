package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest

class CollaborationPointPublicationTest {
    @Test
    fun concurrentPublicationRetriesLocalMergeOnceWithoutRepeatingThePointRequest() = runTest {
        val f = LocalListFixture()
        f.beforeMerge = { f.mergeResult = AppResult.Success(f.merges == 2) }
        assertIs<AppResult.Success<*>>(f.refresh.conversation("selection", "direct"))
        assertEquals(listOf("direct"), f.pointCalls)
        assertEquals(0, f.remoteCalls)
        assertEquals(2, f.merges)
        assertEquals(2, f.checkpointReads)
        assertEquals(1, f.admits)
    }

    @Test
    fun repeatedConflictIsBoundedAndCannotReopenTheObservedDenial() = runTest {
        val f = LocalListFixture()
        f.gate.value =
            dev.fajar.starter.collaboration.domain.entities.CollaborationListAdmission(
                "old",
                f.denied,
            )
        f.mergeResult = AppResult.Success(false)
        assertEquals(
            f.denied,
            assertIs<AppResult.Failed>(f.refresh.conversation("selection", "direct")).failure,
        )
        assertEquals(2, f.merges)
        assertEquals(listOf("direct"), f.pointCalls)
        assertEquals(0, f.admits)
    }

    @Test
    fun cancellationOrOwnerReplacementDuringRetryReadCannotPublishAgain() = runTest {
        for (cancel in listOf(false, true)) {
            val f = LocalListFixture()
            f.mergeResult = AppResult.Success(false)
            val entered = CompletableDeferred<Unit>()
            val released = CompletableDeferred<Unit>()
            f.beforeCheckpoint = {
                if (f.checkpointReads == 2)
                    withContext(NonCancellable) {
                        entered.complete(Unit)
                        released.await()
                    }
            }
            val request = async { f.refresh.conversation("selection", "direct") }
            entered.await()
            if (cancel) request.cancel() else f.setup.session = null
            released.complete(Unit)
            if (cancel) request.join() else assertIs<AppResult.Failed>(request.await())
            assertEquals(1, f.merges)
            assertEquals(0, f.admits)
        }
    }
}
