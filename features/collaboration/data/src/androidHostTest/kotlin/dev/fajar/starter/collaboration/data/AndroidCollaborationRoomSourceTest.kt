package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.datasources.AndroidCollaborationRoomSource
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.matrix.rustcomponents.sdk.*

/** Actual raw adapter with inert pinned FFI handles; native HTTP acceptance is separate. */
class AndroidCollaborationRoomSourceTest {
    @Test
    fun joinedRoomIsReturnedWithoutAProviderJoinAndRemainsOwnedByTheCaller() = runTest {
        val f = RoomFixture(Membership.JOINED)
        val result = f.source.joined("!original:test")
        assertSame(f.known, result)
        assertEquals(listOf("lookup", "membership"), f.calls)
        result.close()
        assertEquals(listOf("lookup", "membership", "known.close"), f.calls)
    }

    @Test
    fun unknownRoomStillJoinsExactlyOnceWithTheOriginalIdentifier() = runTest {
        val f = RoomFixture(null)
        val result = f.source.joined("!original:test")
        assertSame(f.joined, result)
        assertEquals(listOf("lookup", "join"), f.calls)
        result.close()
        assertEquals(listOf("lookup", "join", "joined.close"), f.calls)
    }

    @Test
    fun everyOtherMembershipClosesItsLookupBeforeTheExistingJoinOperation() = runTest {
        for (state in Membership.entries.filter { it != Membership.JOINED }) {
            val f = RoomFixture(state)
            val result = f.source.joined("!original:test")
            assertSame(f.joined, result)
            assertEquals(listOf("lookup", "membership", "known.close", "join"), f.calls)
            result.close()
            assertEquals(1, f.calls.count { it == "known.close" })
            assertEquals(1, f.calls.count { it == "joined.close" })
        }
    }

    @Test
    fun failedLookupPreservesItsFailureAndDoesNotTryJoining() = runTest {
        val f = RoomFixture(Membership.JOINED)
        val failure = IllegalStateException("Fixture lookup failure")
        f.beforeLookup = { throw failure }
        assertSame(
            failure,
            assertFailsWith<IllegalStateException> { f.source.joined("!original:test") },
        )
        assertEquals(listOf("lookup"), f.calls)
    }

    @Test
    fun failedMembershipClosesTheAcquiredHandleAndPreservesBothFailures() = runTest {
        val f = RoomFixture(Membership.JOINED)
        val failure = IllegalStateException("Fixture membership failure")
        val closing = IllegalArgumentException("Fixture close failure")
        f.beforeMembership = { throw failure }
        f.beforeKnownClose = { throw closing }
        assertSame(
            failure,
            assertFailsWith<IllegalStateException> { f.source.joined("!original:test") },
        )
        assertEquals(listOf(closing), failure.suppressed.toList())
        assertEquals(listOf("lookup", "membership", "known.close"), f.calls)
    }

    @Test
    fun failedLookupDisposalDoesNotStartAnotherAcquisitionOrCloseTwice() = runTest {
        val f = RoomFixture(Membership.INVITED)
        val failure = IllegalStateException("Fixture disposal failure")
        f.beforeKnownClose = { throw failure }
        assertSame(
            failure,
            assertFailsWith<IllegalStateException> { f.source.joined("!original:test") },
        )
        assertEquals(listOf("lookup", "membership", "known.close"), f.calls)
    }

    @Test
    fun failedJoinPreservesItsFailureAfterClosingTheUnjoinedLookup() = runTest {
        val f = RoomFixture(Membership.INVITED)
        val failure = IllegalStateException("Fixture join failure")
        f.beforeJoin = { throw failure }
        assertSame(
            failure,
            assertFailsWith<IllegalStateException> { f.source.joined("!original:test") },
        )
        assertEquals(listOf("lookup", "membership", "known.close", "join"), f.calls)
    }

    @Test
    fun alreadyCancelledCallerDoesNotTouchTheSdk() = runTest {
        val f = RoomFixture(Membership.JOINED)
        val task = launch {
            currentCoroutineContext().cancel()
            f.source.joined("!original:test")
            fail("Cancelled caller must not acquire a room")
        }
        task.join()
        assertTrue(task.isCancelled)
        assertTrue(f.calls.isEmpty())
    }

    @Test
    fun cancellationDuringLookupOrMembershipClosesWithoutHandingOverOrJoining() = runTest {
        for (duringMembership in listOf(false, true)) {
            val f = RoomFixture(Membership.JOINED)
            val task =
                launch(start = CoroutineStart.LAZY) {
                    val current = currentCoroutineContext()
                    if (duringMembership) f.beforeMembership = { current.cancel() }
                    else f.beforeLookup = { current.cancel() }
                    f.source.joined("!original:test")
                    fail("Cancelled room must not escape")
                }
            task.start()
            task.join()
            assertTrue(task.isCancelled)
            assertEquals(1, f.calls.count { it == "known.close" })
            assertFalse("join" in f.calls)
        }
    }

    @Test
    fun cancellationDuringJoinDisposesTheLateHandleBeforePropagating() = runTest {
        val f = RoomFixture(null)
        val task = launch {
            val current = currentCoroutineContext()
            f.beforeJoin = { current.cancel() }
            f.source.joined("!original:test")
            fail("Cancelled joined room must not escape")
        }
        task.join()
        assertTrue(task.isCancelled)
        assertEquals(listOf("lookup", "join", "joined.close"), f.calls)
    }

    private class RoomFixture(state: Membership?) {
        val calls = mutableListOf<String>()
        var beforeLookup: () -> Unit = {}
        var beforeMembership: () -> Unit = {}
        var beforeKnownClose: () -> Unit = {}
        var beforeJoin: suspend () -> Unit = {}
        val known =
            object : Room(NoHandle) {
                override fun membership(): Membership {
                    calls += "membership"
                    beforeMembership()
                    return requireNotNull(state)
                }

                override fun close() {
                    calls += "known.close"
                    beforeKnownClose()
                }
            }
        val joined =
            object : Room(NoHandle) {
                override fun close() {
                    calls += "joined.close"
                }
            }
        val source =
            AndroidCollaborationRoomSource(
                object : Client(NoHandle) {
                    override fun getRoom(roomId: String): Room? {
                        assertEquals("!original:test", roomId)
                        calls += "lookup"
                        beforeLookup()
                        return known.takeIf { state != null }
                    }

                    override suspend fun joinRoomById(roomId: String): Room {
                        assertEquals("!original:test", roomId)
                        calls += "join"
                        beforeJoin()
                        return joined
                    }

                    override fun close() {
                        fail("Borrowed client must stay with its parent")
                    }
                }
            )
    }
}
