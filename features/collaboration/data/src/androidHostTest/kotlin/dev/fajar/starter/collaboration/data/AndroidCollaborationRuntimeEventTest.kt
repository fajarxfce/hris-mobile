package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.matrix.rustcomponents.sdk.*

/** Real parent/child integration over inert FFI handles, without loading a native library. */
@OptIn(ExperimentalCoroutinesApi::class)
class AndroidCollaborationRuntimeEventTest {
    @Test
    fun readUsesTheExistingClientAndClosesOnlyItsOwnRoom() = runTest {
        val f = runtimeLeaseFixture(StandardTestDispatcher(testScheduler))
        val room = EventReadRoom(f.calls)
        f.readRoom = {
            assertEquals("!room:test", it)
            room
        }
        try {
            f.lease.completeAcquisition()
            assertEquals(AppResult.Success(Unit), f.lease.publish())
            val result =
                assertIs<AppResult.Success<CollaborationEventSnapshot>>(
                        f.lease.readEvent("!room:test", "\$original")
                    )
                    .value
            assertEquals(f.lease.owner, result.owner)
            assertEquals(f.lease.deviceId, result.deviceId)
            assertEquals("\$original", result.event.eventId)
            assertIs<CollaborationAcquiredEventContent.Original>(result.event.content)
            assertEquals(listOf("room.lookup", "event.read", "room.close"), f.calls)
            assertEquals(0, f.syncRequests)
            assertEquals("", room.raw.json)
        } finally {
            f.close()
        }
        assertEquals(1, f.calls.count { it == "room.close" })
        assertEquals(1, f.calls.count { it == "client.close" })
    }

    @Test
    fun parentCloseWaitsForTheRawReadBeforeReleasingClientAndDatabase() = runTest {
        val f = runtimeLeaseFixture(StandardTestDispatcher(testScheduler))
        val room = EventReadRoom(f.calls)
        val release = CompletableDeferred<Unit>()
        room.beforeRead = { release.await() }
        f.readRoom = { room }
        try {
            f.lease.completeAcquisition()
            val reading = async { f.lease.readEvent("!room:test", "\$original") }
            runCurrent()
            val closing = async { f.lease.close() }
            runCurrent()
            assertTrue(f.lease.closingRequested)
            assertFalse(closing.isCompleted)
            assertFalse("client.close" in f.calls)
            assertFalse("directory.close" in f.calls)
            release.complete(Unit)
            assertIs<AppResult.Failed>(reading.await())
            assertEquals(AppResult.Success(Unit), closing.await())
            assertTrue(f.calls.indexOf("room.close") < f.calls.indexOf("client.close"))
            assertTrue(f.calls.indexOf("client.close") < f.calls.indexOf("directory.close"))
        } finally {
            release.complete(Unit)
            f.close()
        }
        assertEquals(1, f.calls.count { it == "room.close" })
        assertEquals(1, f.calls.count { it == "client.close" })
    }

    @Test
    fun credentialRejectionDuringReadClosesRoomAndRetainsExactFailure() = runTest {
        val f = runtimeLeaseFixture(StandardTestDispatcher(testScheduler))
        val room = EventReadRoom(f.calls)
        room.beforeRead = { f.persistence.reportAuthenticationFailure() }
        f.readRoom = { room }
        try {
            f.lease.completeAcquisition()
            val result = assertIs<AppResult.Failed>(f.lease.readEvent("!room:test", "\$original"))
            assertSame(f.lease.credentialFailures.value, result.failure)
            assertEquals(1, f.calls.count { it == "room.close" })
            assertFalse("client.close" in f.calls)
            assertEquals(0, f.syncRequests)
        } finally {
            f.close()
        }
    }

    @Test
    fun closedParentCannotLookUpAnotherRoom() = runTest {
        val f = runtimeLeaseFixture(StandardTestDispatcher(testScheduler))
        try {
            f.lease.completeAcquisition()
            assertEquals(AppResult.Success(Unit), f.lease.close())
            assertIs<AppResult.Failed>(f.lease.readEvent("!room:test", "\$original"))
            assertFalse("room.lookup" in f.calls)
        } finally {
            f.close()
        }
    }
}

private class EventReadRoom(private val calls: MutableList<String>) : Room(NoHandle) {
    var beforeRead: suspend () -> Unit = {}
    val raw = RawTimelineEvent(eventReadDto().json, RawEventEncryption.DECRYPTED)

    override fun membership(): Membership = Membership.JOINED

    override suspend fun isEncrypted(): Boolean = true

    override suspend fun loadOrFetchRawEvent(eventId: String): RawTimelineEvent {
        assertEquals("\$original", eventId)
        calls += "event.read"
        beforeRead()
        return raw
    }

    override fun close() {
        calls += "room.close"
    }
}
