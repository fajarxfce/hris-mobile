package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.repositories.AndroidCollaborationHistoryWindow
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.matrix.rustcomponents.sdk.*

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidCollaborationHistoryAnchorTest {
    @Test
    fun displayedOriginalAvoidsRawAcquisitionAndKeepsTheFullWindow() = runTest {
        val f = runtimeLeaseFixture(StandardTestDispatcher(testScheduler))
        val history = HistoryRoom()
        history.initial = {
            listOf(TimelineDiff.Reset((1..3).map { HistoryItem(history.calls, "\$original-$it") }))
        }
        f.readRoom = { history }
        try {
            f.lease.completeAcquisition()
            val window =
                assertIs<AppResult.Success<AndroidCollaborationHistoryWindow>>(
                        f.lease.readOriginalHistory("!room:test", "\$original-3")
                    )
                    .value
            assertEquals(3, window.originals.size)
            assertNull(window.acquiredAnchor)
            assertEquals(1, f.calls.count { it == "room.lookup" })
            assertEquals(0, f.syncRequests)
        } finally {
            f.close()
        }
    }

    @Test
    fun foldedEditAcquiresTheExactEventAfterClosingThePresentationWindow() = runTest {
        val f = runtimeLeaseFixture(StandardTestDispatcher(testScheduler))
        val history = HistoryRoom()
        history.item.replacementJson = editJson
        val raw = AnchorRoom(f.calls)
        var lookups = 0
        f.readRoom = {
            if (lookups++ == 0) history
            else {
                assertEquals(1, history.calls.count { it == "room.close" })
                raw
            }
        }
        try {
            f.lease.completeAcquisition()
            val window =
                assertIs<AppResult.Success<AndroidCollaborationHistoryWindow>>(
                        f.lease.readOriginalHistory("!room:test", "\$edit")
                    )
                    .value
            val acquired = assertNotNull(window.acquiredAnchor)
            assertEquals(f.lease.owner, acquired.owner)
            assertEquals(f.lease.deviceId, acquired.deviceId)
            assertEquals("\$edit", acquired.event.eventId)
            assertEquals(
                window.edits.single(),
                assertIs<CollaborationAcquiredEventContent.Replacement>(acquired.event.content).edit,
            )
            assertEquals("\$original", window.originals.single().eventId)
            assertEquals(
                "Original text",
                assertIs<CollaborationHistoryChange.Text>(window.originals.single()).text,
            )
            assertEquals(2, lookups)
            assertEquals(1, f.calls.count { it == "anchor.read" })
            assertEquals(1, f.calls.count { it == "anchor.close" })
            assertEquals(0, f.syncRequests)
        } finally {
            f.close()
        }
    }

    @Test
    fun rawAnchorDenialCannotPublishPreviouslyCapturedOriginals() = runTest {
        val f = runtimeLeaseFixture(StandardTestDispatcher(testScheduler))
        val history = HistoryRoom()
        val raw = AnchorRoom(f.calls)
        raw.beforeRead = { f.persistence.reportAuthenticationFailure() }
        var lookups = 0
        f.readRoom = { if (lookups++ == 0) history else raw }
        try {
            f.lease.completeAcquisition()
            val result =
                assertIs<AppResult.Failed>(f.lease.readOriginalHistory("!room:test", "\$edit"))
            assertSame(f.lease.credentialFailures.value, result.failure)
            assertEquals(1, history.calls.count { it == "room.close" })
            assertEquals(1, f.calls.count { it == "anchor.close" })
        } finally {
            f.close()
        }
    }

    @Test
    fun cancelledAnchorReadRemainsOwnedUntilTheParentCanReleaseItsClient() = runTest {
        val f = runtimeLeaseFixture(StandardTestDispatcher(testScheduler))
        val history = HistoryRoom()
        val raw = AnchorRoom(f.calls)
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        raw.beforeRead = {
            entered.complete(Unit)
            release.await()
        }
        var lookups = 0
        f.readRoom = { if (lookups++ == 0) history else raw }
        try {
            f.lease.completeAcquisition()
            val reading = async { f.lease.readOriginalHistory("!room:test", "\$edit") }
            entered.await()
            assertEquals(1, f.calls.count { it == "anchor.read" })
            reading.cancel()
            val closing = async { f.lease.close() }
            runCurrent()
            assertFalse(closing.isCompleted)
            assertFalse("client.close" in f.calls)
            release.complete(Unit)
            reading.join()
            assertTrue(reading.isCancelled)
            assertEquals(AppResult.Success(Unit), closing.await())
            assertTrue(f.calls.indexOf("anchor.close") < f.calls.indexOf("client.close"))
        } finally {
            release.complete(Unit)
            f.close()
        }
    }
}

private const val editJson =
    """{"type":"m.room.message","event_id":"${'$'}edit","sender":"@fictional:example.test","origin_server_ts":2,"content":{"msgtype":"m.text","body":"* Edited","m.new_content":{"msgtype":"m.text","body":"Edited"},"m.relates_to":{"rel_type":"m.replace","event_id":"${'$'}original"}}}"""

private class AnchorRoom(private val calls: MutableList<String>) : Room(NoHandle) {
    var beforeRead: suspend () -> Unit = {}

    override fun membership() = Membership.JOINED

    override suspend fun isEncrypted() = true

    override suspend fun loadOrFetchRawEvent(eventId: String): RawTimelineEvent {
        assertEquals("\$edit", eventId)
        calls += "anchor.read"
        beforeRead()
        return RawTimelineEvent(editJson, RawEventEncryption.DECRYPTED)
    }

    override fun close() {
        calls += "anchor.close"
    }
}
