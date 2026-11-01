package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.datasources.AndroidCollaborationHistorySource
import dev.fajar.starter.collaboration.data.repositories.*
import dev.fajar.starter.collaboration.domain.entities.CollaborationHistoryChange
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.matrix.rustcomponents.sdk.*
import uniffi.matrix_sdk_ui.TimelineReadReceiptTracking

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidCollaborationHistorySourceTest {
    @Test
    fun focusedSnapshotCopiesOriginalOnceAndClosesBeforeReturning() = runTest {
        val room = HistoryRoom()
        val children = SdkChildResources(this)
        val windows = AndroidCollaborationHistoryWindows(client(room), children) { null }
        val result =
            assertIs<AppResult.Success<AndroidCollaborationHistoryWindow>>(
                    windows.read("!room:test", "\$original")
                )
                .value
        assertEquals(
            "Original text",
            assertIs<CollaborationHistoryChange.Text>(result.originals.single()).text,
        )
        assertEquals(
            20u.toUShort(),
            assertIs<TimelineFocus.Event>(room.configuration!!.focus).numContextEvents,
        )
        assertEquals(
            "\$original",
            assertIs<TimelineFocus.Event>(room.configuration!!.focus).eventId,
        )
        assertEquals(TimelineReadReceiptTracking.DISABLED, room.configuration!!.trackReadReceipts)
        assertEquals(1, room.item.copied)
        assertEquals(1, room.item.disposed)
        assertTrue(result.extractionNanos >= 0)
        assertEquals(room.item.model.length.toLong(), result.modelCharacters)
        assertEquals(room.item.json.length.toLong(), result.originalCharacters)
        assertEquals(
            listOf("listener.cancel", "listener.close", "timeline.close", "room.close"),
            room.calls.takeLast(4),
        )
        assertEquals(AppResult.Success(Unit), children.close())
        assertEquals(1, room.calls.count { it == "room.close" })
    }

    @Test
    fun laterCallbacksAreDisposedWithoutChangingTheCapturedSnapshot() = runTest {
        val room = HistoryRoom()
        val source = AndroidCollaborationHistorySource("\$original") { room }
        source.open()
        val first = source.snapshot()
        val late = HistoryItem(room.calls, "\$late")
        room.emit(listOf(TimelineDiff.PushBack(late)))
        assertEquals(0, late.copied)
        assertEquals(1, late.disposed)
        assertEquals(first, source.snapshot())
        source.stop()
        assertFailsWith<IllegalStateException> { source.snapshot() }
    }

    @Test
    fun malformedOrOversizeInitialSnapshotReleasesEveryValue() = runTest {
        for (oversize in listOf(false, true)) {
            val room = HistoryRoom()
            val items = (1..129).map { HistoryItem(room.calls, "\$event-$it") }
            room.initial = {
                if (oversize) listOf(TimelineDiff.Reset(items))
                else listOf(TimelineDiff.PushBack(items.first()))
            }
            val source = AndroidCollaborationHistorySource("\$original") { room }
            assertFailsWith<IllegalStateException> { source.open() }
            assertEquals(if (oversize) 129 else 1, items.sumOf { it.disposed })
            assertTrue(items.all { it.copied == 0 })
            source.stop()
            assertEquals(1, room.calls.count { it == "room.close" })
        }
    }

    @Test
    fun failedEncryptionAndPartialTimelineAcquisitionStillCloseTheRoom() = runTest {
        for (encrypted in listOf(false, true)) {
            val room = HistoryRoom(encrypted)
            room.beforeTimeline = { error("Fixture acquisition failed") }
            val source = AndroidCollaborationHistorySource("\$original") { room }
            assertFailsWith<Exception> { source.open() }
            source.stop()
            assertEquals(1, room.calls.count { it == "room.close" })
            assertEquals(0, room.calls.count { it == "listener.open" })
        }
    }

    @Test
    fun oversizedDiagnosticModelFailsWithoutPublishingAnyOriginal() = runTest {
        val room = HistoryRoom()
        room.item.model = "x".repeat(4_194_305)
        val source = AndroidCollaborationHistorySource("\$original") { room }
        assertFailsWith<IllegalStateException> { source.open() }
        assertFailsWith<IllegalStateException> { source.snapshot() }
        assertEquals(1, room.item.disposed)
        assertTrue("provider.destroy" in room.calls)
        source.stop()
    }

    @Test
    fun lateCredentialDenialClosesAcquiredResourcesAndCannotReturnContent() = runTest {
        val room = HistoryRoom()
        val children = SdkChildResources(this)
        val denied = Failure(FailureKind.Unauthorized, "Fixture denied")
        var failure: Failure? = null
        room.beforeListenerReturns = { failure = denied }
        val windows = AndroidCollaborationHistoryWindows(client(room), children) { failure }
        assertEquals(AppResult.Failed(denied), windows.read("!room:test", "\$original"))
        assertEquals(1, room.calls.count { it == "room.close" })
        children.close()
    }

    @Test
    fun unfinishedNativeListenerCleanupRetainsResourcesForParentRetry() = runTest {
        val room = HistoryRoom()
        room.task.finished = false
        val children = SdkChildResources(this)
        val windows = AndroidCollaborationHistoryWindows(client(room), children) { null }
        val pending = async { windows.read("!room:test", "\$original") }
        advanceUntilIdle()
        assertIs<AppResult.Failed>(pending.await())
        assertEquals(0, room.calls.count { it == "timeline.close" })
        assertEquals(0, room.calls.count { it == "room.close" })
        room.task.finished = true
        // The first retry observes the earlier failed drain after the caller's timeout;
        // another explicit close retries that retained resource instead of discarding it.
        assertIs<AppResult.Failed>(children.close())
        assertEquals(AppResult.Success(Unit), children.close())
        assertEquals(1, room.calls.count { it == "room.close" })
    }

    private fun client(room: HistoryRoom) =
        object : Client(NoHandle) {
            override fun getRoom(roomId: String): Room = room
        }
}
