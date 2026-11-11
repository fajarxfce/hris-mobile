package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.datasources.AndroidCollaborationRoomInventorySource
import dev.fajar.starter.collaboration.data.dto.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.matrix.rustcomponents.sdk.*

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidCollaborationRoomInventorySourceTest {
    @Test
    fun localPageIsOrderedAndClosesAllHandlesIncludingOmittedRooms() = runTest {
        val rooms =
            listOf(InventoryRoom("!c:test"), InventoryRoom("!a:test"), InventoryRoom("!b:test"))
        val client = InventoryClient { rooms }
        val page = AndroidCollaborationRoomInventorySource(client).read(null, 2)
        assertEquals(listOf("!a:test", "!b:test"), page.rooms.map { it.roomId })
        assertNull(page.afterRoomId)
        assertEquals("!b:test", page.nextAfterRoomId)
        assertEquals(1, client.enumerations)
        assertTrue(rooms.all { it.closes == 1 })
        assertEquals(0, rooms.first().memberships)
        assertEquals(0, client.closes)
    }

    @Test
    fun cursorIsExclusiveAndRetainsNoHandlesFromEarlierPages() = runTest {
        val rooms =
            listOf(InventoryRoom("!c:test"), InventoryRoom("!a:test"), InventoryRoom("!b:test"))
        val client = InventoryClient { rooms }
        val page = AndroidCollaborationRoomInventorySource(client).read("!b:test", 2)
        assertEquals(listOf("!c:test"), page.rooms.map { it.roomId })
        assertEquals("!b:test", page.afterRoomId)
        assertNull(page.nextAfterRoomId)
        assertTrue(rooms.all { it.closes == 1 })
        assertEquals(1, rooms.sumOf { it.memberships })
    }

    @Test
    fun emptyInventoryIsAFiniteEmptyPage() = runTest {
        val client = InventoryClient { emptyList() }
        val page = AndroidCollaborationRoomInventorySource(client).read(null, 16)
        assertTrue(page.rooms.isEmpty())
        assertNull(page.nextAfterRoomId)
        assertEquals(1, client.enumerations)
        assertEquals(0, client.closes)
    }

    @Test
    fun aCursorBeyondTheInventoryStillDisposesEveryHandle() = runTest {
        val rooms = listOf(InventoryRoom("!a:test"), InventoryRoom("!b:test"))
        val page =
            AndroidCollaborationRoomInventorySource(InventoryClient { rooms }).read("!z:test", 1)
        assertTrue(page.rooms.isEmpty())
        assertNull(page.nextAfterRoomId)
        assertTrue(rooms.all { it.closes == 1 && it.memberships == 0 })
    }

    @Test
    fun anExactLimitDoesNotInventAnotherPage() = runTest {
        val rooms = listOf(InventoryRoom("!a:test"), InventoryRoom("!b:test"))
        val page = AndroidCollaborationRoomInventorySource(InventoryClient { rooms }).read(null, 2)
        assertEquals(2, page.rooms.size)
        assertNull(page.nextAfterRoomId)
        assertTrue(rooms.all { it.closes == 1 })
    }

    @Test
    fun largeUnorderedInventoryReturnsOnlyTheRequestedPageAndClosesAllSdkHandles() = runTest {
        val rooms =
            (0..300).reversed().map {
                InventoryRoom("!" + it.toString().padStart(4, '0') + ":test")
            }
        val page = AndroidCollaborationRoomInventorySource(InventoryClient { rooms }).read(null, 16)
        assertEquals(
            (0..15).map { "!" + it.toString().padStart(4, '0') + ":test" },
            page.rooms.map { it.roomId },
        )
        assertEquals("!0015:test", page.nextAfterRoomId)
        assertEquals(16, rooms.sumOf { it.memberships })
        assertTrue(rooms.all { it.closes == 1 })
    }

    @Test
    fun everyNativeMembershipIsPreservedWithoutImplicitJoining() = runTest {
        for (membership in Membership.entries) {
            val room = InventoryRoom("!a:test", membership)
            val page =
                AndroidCollaborationRoomInventorySource(InventoryClient { listOf(room) })
                    .read(null, 1)
            assertEquals(
                CollaborationSdkInventoryMembership.valueOf(membership.name),
                page.rooms.single().membership,
            )
            assertEquals(1, room.closes)
        }
    }

    @Test
    fun invalidPageLimitsFailBeforeAnySdkEnumeration() = runTest {
        val client = InventoryClient { error("No SDK call expected") }
        val source = AndroidCollaborationRoomInventorySource(client)
        for (limit in listOf(-1, 0, 65, Int.MAX_VALUE)) assertFailsWith<IllegalArgumentException> {
            source.read(null, limit)
        }
        assertEquals(0, client.enumerations)
    }

    @Test
    fun invalidCursorBoundsFailBeforeAnySdkEnumeration() = runTest {
        val client = InventoryClient { error("No SDK call expected") }
        val source = AndroidCollaborationRoomInventorySource(client)
        for (cursor in
            listOf("", "a", "!", "!a\nb:test", "!a b:test", "!" + "x".repeat(255))) assertFailsWith<
            IllegalArgumentException
        > {
            source.read(cursor, 1)
        }
        assertEquals(0, client.enumerations)
    }

    @Test
    fun sdkEnumerationFailureIsPreservedWithoutClosingTheParentClient() = runTest {
        val failure = IllegalStateException("Fixture enumeration failure")
        val client = InventoryClient { throw failure }
        assertSame(
            failure,
            assertFailsWith<IllegalStateException> {
                AndroidCollaborationRoomInventorySource(client).read(null, 1)
            },
        )
        assertEquals(1, client.enumerations)
        assertEquals(0, client.closes)
    }

    @Test
    fun anIdReadFailureClosesEvenHandlesThatWereNotVisited() = runTest {
        val failure = IllegalStateException("Fixture identity failure")
        val rooms =
            listOf(InventoryRoom("!a:test"), InventoryRoom("!b:test"), InventoryRoom("!c:test"))
        rooms.first().onId = { throw failure }
        assertSame(
            failure,
            assertFailsWith<IllegalStateException> {
                AndroidCollaborationRoomInventorySource(InventoryClient { rooms }).read(null, 1)
            },
        )
        assertTrue(rooms.all { it.closes == 1 })
        assertEquals(0, rooms.sumOf { it.memberships })
    }

    @Test
    fun aMembershipFailureClosesSelectedAndOmittedHandles() = runTest {
        val failure = IllegalStateException("Fixture membership failure")
        val rooms =
            listOf(InventoryRoom("!a:test"), InventoryRoom("!b:test"), InventoryRoom("!c:test"))
        rooms.first().onMembership = { throw failure }
        assertSame(
            failure,
            assertFailsWith<IllegalStateException> {
                AndroidCollaborationRoomInventorySource(InventoryClient { rooms }).read(null, 2)
            },
        )
        assertTrue(rooms.all { it.closes == 1 })
    }

    @Test
    fun duplicateSelectedRoomIdsFailAndCloseBothDistinctHandles() = runTest {
        val rooms = listOf(InventoryRoom("!a:test"), InventoryRoom("!a:test"))
        assertFailsWith<IllegalStateException> {
            AndroidCollaborationRoomInventorySource(InventoryClient { rooms }).read(null, 2)
        }
        assertTrue(rooms.all { it.closes == 1 })
    }

    @Test
    fun disposalFailureDoesNotSkipTheRemainingHandles() = runTest {
        val failure = IllegalStateException("Fixture close failure")
        val rooms =
            listOf(InventoryRoom("!a:test"), InventoryRoom("!b:test"), InventoryRoom("!c:test"))
        rooms.first().onClose = { throw failure }
        assertSame(
            failure,
            assertFailsWith<IllegalStateException> {
                AndroidCollaborationRoomInventorySource(InventoryClient { rooms }).read(null, 1)
            },
        )
        assertTrue(rooms.all { it.closes == 1 })
    }

    @Test
    fun aPrimaryFailurePreservesAllSecondaryDisposalFailures() = runTest {
        val failure = IllegalStateException("Fixture primary failure")
        val firstClose = IllegalArgumentException("Fixture close one")
        val lastClose = IllegalArgumentException("Fixture close two")
        val rooms =
            listOf(InventoryRoom("!a:test"), InventoryRoom("!b:test"), InventoryRoom("!c:test"))
        rooms.first().onId = { throw failure }
        rooms.first().onClose = { throw firstClose }
        rooms.last().onClose = { throw lastClose }
        assertSame(
            failure,
            assertFailsWith<IllegalStateException> {
                AndroidCollaborationRoomInventorySource(InventoryClient { rooms }).read(null, 1)
            },
        )
        assertEquals(listOf(firstClose, lastClose), failure.suppressedExceptions)
        assertTrue(rooms.all { it.closes == 1 })
    }

    @Test
    fun detachedResultsDoNotRetainMutableRoomMetadata() = runTest {
        val room = InventoryRoom("!a:test", Membership.INVITED)
        val page =
            AndroidCollaborationRoomInventorySource(InventoryClient { listOf(room) }).read(null, 1)
        room.identity = "!changed:test"
        room.state = Membership.JOINED
        assertEquals("!a:test", page.rooms.single().roomId)
        assertEquals(CollaborationSdkInventoryMembership.INVITED, page.rooms.single().membership)
        assertEquals(1, room.closes)
    }

    @Test
    fun alreadyCancelledCallsDoNotAcquireSdkHandles() = runTest {
        val client = InventoryClient { error("No SDK call expected") }
        val job = launch {
            currentCoroutineContext().cancel()
            AndroidCollaborationRoomInventorySource(client).read(null, 1)
        }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(0, client.enumerations)
    }

    @Test
    fun cancellationImmediatelyAfterAcquisitionClosesAllReturnedHandles() = runTest {
        val rooms = listOf(InventoryRoom("!a:test"), InventoryRoom("!b:test"))
        val job = launch {
            val own = currentCoroutineContext().job
            val client = InventoryClient {
                own.cancel()
                rooms
            }
            AndroidCollaborationRoomInventorySource(client).read(null, 1)
        }
        job.join()
        assertTrue(job.isCancelled)
        assertTrue(rooms.all { it.closes == 1 && it.memberships == 0 })
    }

    @Test
    fun cancellationDuringIdentityReadsStillDisposesUnvisitedHandles() = runTest {
        val rooms =
            listOf(InventoryRoom("!a:test"), InventoryRoom("!b:test"), InventoryRoom("!c:test"))
        val job = launch {
            val own = currentCoroutineContext().job
            rooms.first().onId = { own.cancel() }
            AndroidCollaborationRoomInventorySource(InventoryClient { rooms }).read(null, 1)
        }
        job.join()
        assertTrue(job.isCancelled)
        assertTrue(rooms.all { it.closes == 1 })
    }

    @Test
    fun cancellationDuringMembershipReadsDoesNotPublishPartialMetadata() = runTest {
        val rooms =
            listOf(InventoryRoom("!a:test"), InventoryRoom("!b:test"), InventoryRoom("!c:test"))
        var published = false
        val job = launch {
            val own = currentCoroutineContext().job
            rooms.first().onMembership = { own.cancel() }
            AndroidCollaborationRoomInventorySource(InventoryClient { rooms }).read(null, 1)
            published = true
        }
        job.join()
        assertTrue(job.isCancelled)
        assertFalse(published)
        assertTrue(rooms.all { it.closes == 1 })
    }

    @Test
    fun cancellationDuringDisposalStillClosesEverythingAndDoesNotPublishThePage() = runTest {
        val rooms =
            listOf(InventoryRoom("!a:test"), InventoryRoom("!b:test"), InventoryRoom("!c:test"))
        var published = false
        val job = launch {
            val own = currentCoroutineContext().job
            rooms.first().onClose = { own.cancel() }
            AndroidCollaborationRoomInventorySource(InventoryClient { rooms }).read(null, 1)
            published = true
        }
        job.join()
        assertTrue(job.isCancelled)
        assertFalse(published)
        assertTrue(rooms.all { it.closes == 1 })
    }

    @Test
    fun successivePagesAcquireAndDisposeFreshHandlesWithoutClosingTheSharedClient() = runTest {
        val acquisitions = mutableListOf<List<InventoryRoom>>()
        val client = InventoryClient {
            listOf(InventoryRoom("!c:test"), InventoryRoom("!a:test"), InventoryRoom("!b:test"))
                .also { acquisitions += it }
        }
        val source = AndroidCollaborationRoomInventorySource(client)
        val first = source.read(null, 2)
        val second = source.read(first.nextAfterRoomId, 2)
        assertEquals(
            listOf("!a:test", "!b:test", "!c:test"),
            (first.rooms + second.rooms).map { it.roomId },
        )
        assertNull(second.nextAfterRoomId)
        assertEquals(2, client.enumerations)
        assertTrue(acquisitions.flatten().all { it.closes == 1 })
        assertEquals(0, client.closes)
    }

    @Test
    fun theSameFailureInstanceIsNeverAddedAsItsOwnSuppressedError() = runTest {
        val failure = IllegalStateException("Fixture shared failure")
        val rooms = listOf(InventoryRoom("!a:test"), InventoryRoom("!b:test"))
        rooms.first().onId = { throw failure }
        rooms.first().onClose = { throw failure }
        assertSame(
            failure,
            assertFailsWith<IllegalStateException> {
                AndroidCollaborationRoomInventorySource(InventoryClient { rooms }).read(null, 1)
            },
        )
        assertTrue(failure.suppressedExceptions.isEmpty())
        assertTrue(rooms.all { it.closes == 1 })
    }

    @Test
    fun multipleDisposalFailuresKeepTheFirstAndAttemptEveryClose() = runTest {
        val first = IllegalStateException("Fixture first close")
        val last = IllegalArgumentException("Fixture last close")
        val rooms =
            listOf(InventoryRoom("!a:test"), InventoryRoom("!b:test"), InventoryRoom("!c:test"))
        rooms.first().onClose = { throw first }
        rooms.last().onClose = { throw last }
        assertSame(
            first,
            assertFailsWith<IllegalStateException> {
                AndroidCollaborationRoomInventorySource(InventoryClient { rooms }).read(null, 1)
            },
        )
        assertEquals(listOf(last), first.suppressedExceptions)
        assertTrue(rooms.all { it.closes == 1 })
    }

    private class InventoryClient(private val acquire: () -> List<Room>) : Client(NoHandle) {
        var enumerations = 0
        var closes = 0

        override fun rooms(): List<Room> {
            enumerations++
            return acquire()
        }

        override fun close() {
            closes++
        }
    }

    private class InventoryRoom(var identity: String, var state: Membership = Membership.JOINED) :
        Room(NoHandle) {
        var closes = 0
        var memberships = 0
        var onId: () -> Unit = {}
        var onMembership: () -> Unit = {}
        var onClose: () -> Unit = {}

        override fun id(): String {
            onId()
            return identity
        }

        override fun membership(): Membership {
            memberships++
            onMembership()
            return state
        }

        override fun close() {
            closes++
            onClose()
        }
    }
}
