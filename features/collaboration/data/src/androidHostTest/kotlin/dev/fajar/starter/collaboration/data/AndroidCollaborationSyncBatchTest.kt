package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.datasources.createAndroidCollaborationSync
import dev.fajar.starter.collaboration.data.dto.SdkSyncMembership
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.matrix.rustcomponents.sdk.*

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidCollaborationSyncBatchTest {
    @Test
    fun exactPositionsMembershipAndGapFlagsAreDetachedFromTheNativeResponse() = runTest {
        val ids = mutableListOf("\$original", "\$replacement", "\$redaction")
        val joined =
            SyncRoomTimelineChangeV2("!joined:example", SyncTimelineMembershipV2.JOINED, ids, false)
        val left =
            SyncRoomTimelineChangeV2(
                "!left:example",
                SyncTimelineMembershipV2.LEFT,
                emptyList(),
                true,
            )
        val rooms = mutableListOf(joined, left)
        val response = response("previous", "next", rooms, rescan = true)
        val client = SyncClient { response }
        val connection = createAndroidCollaborationSync(client, backgroundScope)
        assertNull(connection.batches.first())
        assertEquals(0, client.calls)
        connection.start()
        runCurrent()
        val observed = assertNotNull(connection.batches.value)
        assertEquals("previous", observed.previousBatch)
        assertEquals("next", observed.nextBatch)
        assertTrue(observed.rescanRequired)
        assertEquals(
            listOf(SdkSyncMembership.Joined, SdkSyncMembership.Left),
            observed.rooms.map { it.membership },
        )
        assertEquals(ids, observed.rooms[0].eventIds)
        assertFalse(observed.rooms[0].limited)
        assertTrue(observed.rooms[1].limited)
        assertTrue(observed.rooms[1].eventIds.isEmpty())
        response.previousBatch = "changed-previous"
        response.nextBatch = "changed-next"
        response.timelineChanges.rescanRequired = false
        joined.roomId = "!changed:example"
        left.limited = false
        ids.clear()
        rooms.clear()
        assertEquals("previous", observed.previousBatch)
        assertEquals("next", observed.nextBatch)
        assertEquals("!joined:example", observed.rooms[0].roomId)
        assertEquals(
            listOf("\$original", "\$replacement", "\$redaction"),
            observed.rooms[0].eventIds,
        )
        assertTrue(observed.rescanRequired && observed.rooms[1].limited)
        connection.stop()
        assertEquals(1, client.calls)
        assertEquals(0, client.positionReads)
        assertFalse(client.closed)
    }

    @Test
    fun emptyResponsesRetainTheirOwnPositionPairWithoutInventingRoomWork() = runTest {
        val client = SyncClient { response(null, "next") }
        val connection = createAndroidCollaborationSync(client, backgroundScope)
        connection.start()
        runCurrent()
        val observed = assertNotNull(connection.batches.value)
        assertNull(observed.previousBatch)
        assertEquals("next", observed.nextBatch)
        assertTrue(observed.rooms.isEmpty())
        assertFalse(observed.rescanRequired)
        connection.stop()
        assertEquals(0, client.positionReads)
    }

    @Test
    fun failedLaterRequestPreservesTheBatchAndUsesTheExistingLongPoll() = runTest {
        var requests = 0
        val client = SyncClient {
            if (++requests == 1) response("before", "after")
            else error("Fictional transport interruption")
        }
        val connection = createAndroidCollaborationSync(client, backgroundScope)
        connection.start()
        runCurrent()
        val observed = assertNotNull(connection.batches.value)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, client.calls)
        assertEquals(listOf(0uL, 10_000uL), client.settings.map { it.timeoutMs })
        assertEquals(observed, connection.batches.value)
        assertNotNull(connection.problems.value)
        assertTrue(connection.completedFirstSync.value)
        connection.stop()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(2, client.calls)
        assertEquals(0, client.positionReads)
        assertFalse(client.closed)
    }

    @Test
    fun sourceCompletionAfterStopIsDrainedWithoutPublishingOrClosingTheBorrowedClient() = runTest {
        val pending = CompletableDeferred<SyncResponseV2>()
        val client = SyncClient { pending.await() }
        val connection = createAndroidCollaborationSync(client, backgroundScope)
        connection.start()
        runCurrent()
        val closing = async { connection.stop() }
        runCurrent()
        assertFalse(closing.isCompleted)
        pending.complete(response("before", "after"))
        closing.await()
        assertNull(connection.batches.value)
        assertFalse(connection.completedFirstSync.value)
        assertFalse(client.closed)
        assertEquals(1, client.calls)
    }

    private fun response(
        previous: String?,
        next: String,
        rooms: List<SyncRoomTimelineChangeV2> = emptyList(),
        rescan: Boolean = false,
    ) =
        SyncResponseV2(
            previous,
            next,
            SyncResponseRoomsV2(emptyList(), emptyList(), emptyList(), emptyList()),
            SyncTimelineChangesV2(rooms, rescan),
        )

    private class SyncClient(private val respond: suspend () -> SyncResponseV2) : Client(NoHandle) {
        var calls = 0
        var positionReads = 0
        var closed = false
        val settings = mutableListOf<SyncSettingsV2>()

        override suspend fun syncOnceV2(settings: SyncSettingsV2): SyncResponseV2 {
            calls++
            this.settings += settings.copy()
            return respond()
        }

        override suspend fun storedSyncPositionV2(): String? {
            positionReads++
            error("A later position must not label an earlier response")
        }

        override fun close() {
            closed = true
        }
    }
}
