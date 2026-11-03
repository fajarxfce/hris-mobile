package dev.fajar.hris.collaboration.proof

import dev.fajar.starter.collaboration.domain.entities.CollaborationOwner
import dev.fajar.starter.common.result.AppResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class HrisProofObservationTest {
    private val owner =
        CollaborationOwner(
            "proof-login",
            "http://127.0.0.1:18088",
            "00000000-0000-4000-8000-000000000001",
            "00000000-0000-4000-8000-000000000002",
            "00000000-0000-4000-8000-000000000003",
        )

    @Test
    fun sessionSnapshotRemainsObservedUntilEachCollectorIsCancelled() = runTest {
        val access = HrisProofApplicationAccess(owner, "fictional-proof-token")
        try {
            val expected = access.sessions.current()
            repeat(2) { verifyLiveSnapshot(access.sessions.observe(), expected) }
        } finally {
            access.close()
        }
    }

    @Test
    fun matchingAndAbsentWorkspaceSnapshotsRemainObservedUntilCancellation() = runTest {
        val access = HrisProofApplicationAccess(owner, "fictional-proof-token")
        try {
            for (sessionId in listOf(owner.sessionId, "another-login")) {
                val expected = access.workspaces.current(sessionId)
                if (sessionId != owner.sessionId) assertEquals(AppResult.Success(null), expected)
                verifyLiveSnapshot(access.workspaces.observe(sessionId), expected)
            }
        } finally {
            access.close()
        }
    }

    @Test
    fun backgroundProofStillForbidsSelectedWorkspaceReadsAndObservation() = runTest {
        val access =
            HrisProofApplicationAccess(owner, "fictional-proof-token", backgroundOnly = true)
        try {
            assertThrows(IllegalStateException::class.java) {
                access.workspaces.observe(owner.sessionId)
            }
            var refused = false
            try {
                access.workspaces.current(owner.sessionId)
            } catch (_: IllegalStateException) {
                refused = true
            }
            assertTrue(refused)
            verifyLiveSnapshot(access.sessions.observe(), access.sessions.current())
        } finally {
            access.close()
        }
    }

    private suspend fun <T> CoroutineScope.verifyLiveSnapshot(source: Flow<T>, expected: T) {
        val observed = mutableListOf<T>()
        var completion: Throwable? = null
        var completed = false
        val job =
            launch(start = CoroutineStart.UNDISPATCHED) {
                source
                    .onCompletion {
                        completion = it
                        completed = true
                    }
                    .collect { observed += it }
            }
        try {
            assertEquals(1, observed.size)
            assertTrue(observed.single() == expected)
            assertFalse("A snapshot must not end the source observation", job.isCompleted)
            assertFalse(completed)
        } finally {
            job.cancelAndJoin()
        }
        assertTrue(job.isCancelled)
        assertTrue(completed)
        assertTrue(completion is CancellationException)
    }
}
