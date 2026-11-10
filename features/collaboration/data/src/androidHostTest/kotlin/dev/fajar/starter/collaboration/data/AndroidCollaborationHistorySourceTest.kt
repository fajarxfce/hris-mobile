package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.datasources.AndroidCollaborationHistorySource
import dev.fajar.starter.collaboration.data.repositories.*
import dev.fajar.starter.collaboration.domain.entities.CollaborationHistoryChange
import dev.fajar.starter.common.result.*
import kotlin.coroutines.CoroutineContext
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.matrix.rustcomponents.sdk.*
import uniffi.matrix_sdk_ui.TimelineReadReceiptTracking

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidCollaborationHistorySourceTest {
    @Test
    fun malformedSdkSnapshotIsAnAcquisitionFailureRatherThanAStorageFailure() = runTest {
        val room = HistoryRoom()
        room.item.json = "{invalid-json"
        val children = SdkChildResources(this)
        val windows =
            AndroidCollaborationHistoryWindows(
                client(room),
                children,
                StandardTestDispatcher(testScheduler),
            ) {
                null
            }
        val failure = assertIs<AppResult.Failed>(windows.read("!room:test", "\$original")).failure
        assertEquals(FailureKind.Unexpected, failure.kind)
        assertEquals(1, room.calls.count { it == "room.close" })
        assertEquals(AppResult.Success(Unit), children.close())
    }

    @Test
    fun focusedSnapshotCopiesOriginalOnceAndClosesBeforeReturning() = runTest {
        val room = HistoryRoom()
        val children = SdkChildResources(this)
        val windows =
            AndroidCollaborationHistoryWindows(
                client(room),
                children,
                StandardTestDispatcher(testScheduler),
            ) {
                null
            }
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
        val windows =
            AndroidCollaborationHistoryWindows(
                client(room),
                children,
                StandardTestDispatcher(testScheduler),
            ) {
                failure
            }
        assertEquals(AppResult.Failed(denied), windows.read("!room:test", "\$original"))
        assertEquals(1, room.calls.count { it == "room.close" })
        children.close()
    }

    @Test
    fun cancellationWhileDecoderIsQueuedStillReleasesTheOwnedNativeWindow() = runTest {
        val room = HistoryRoom()
        val children = SdkChildResources(this)
        val queued = mutableListOf<Runnable>()
        val decoder =
            object : CoroutineDispatcher() {
                override fun dispatch(context: CoroutineContext, block: Runnable) {
                    queued += block
                }
            }
        val windows = AndroidCollaborationHistoryWindows(client(room), children, decoder) { null }
        val pending = async { windows.read("!room:test", "\$original") }
        runCurrent()
        assertFalse(pending.isCompleted)
        assertEquals(1, room.item.copied)
        assertEquals(0, room.calls.count { it == "room.close" })
        pending.cancel()
        assertEquals(1, queued.size)
        queued.removeAt(0).run()
        runCurrent()
        pending.join()
        assertTrue(pending.isCancelled)
        assertEquals(1, room.calls.count { it == "listener.cancel" })
        assertEquals(1, room.calls.count { it == "room.close" })
        assertEquals(AppResult.Success(Unit), children.close())
    }

    @Test
    fun unfinishedNativeListenerCleanupRetainsResourcesForParentRetry() = runTest {
        val room = HistoryRoom()
        room.task.finished = false
        val children = SdkChildResources(this)
        val windows =
            AndroidCollaborationHistoryWindows(
                client(room),
                children,
                StandardTestDispatcher(testScheduler),
            ) {
                null
            }
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

    @Test
    fun disposingWindowsDoesNotReleaseNativeFocusKeysButAnExistingKeyCanBeReadAgain() = runTest {
        val acquired = mutableListOf<HistoryRoom>()
        val sdk =
            object : Client(NoHandle) {
                override fun getRoom(roomId: String): Room = HistoryRoom().also { acquired += it }
            }
        val children = SdkChildResources(this)
        val windows =
            AndroidCollaborationHistoryWindows(
                sdk,
                children,
                StandardTestDispatcher(testScheduler),
                SdkHistoryFocuses(2),
            ) {
                null
            }
        for (room in listOf("!one:test", "!two:test")) assertIs<AppResult.Success<*>>(
            windows.read(room, "\$original")
        )
        assertEquals(
            "client.collaboration_history_focus_capacity",
            assertIs<AppResult.Failed>(windows.read("!third:test", "\$original")).failure.code,
        )
        assertEquals(2, acquired.size)
        assertTrue(acquired.all { it.calls.count { call -> call == "room.close" } == 1 })
        assertIs<AppResult.Success<*>>(windows.read("!one:test", "\$original"))
        assertEquals(3, acquired.size)
        assertEquals(AppResult.Success(Unit), children.close())
    }

    @Test
    fun uncertainFailedNativeCreationRetainsItsKeyAfterEveryResourceCloses() = runTest {
        var acquired = 0
        val sdk =
            object : Client(NoHandle) {
                override fun getRoom(roomId: String): Room =
                    HistoryRoom().also {
                        acquired++
                        if (acquired == 1)
                            it.beforeTimeline = { error("Uncertain native cache creation") }
                    }
            }
        val children = SdkChildResources(this)
        val windows =
            AndroidCollaborationHistoryWindows(
                sdk,
                children,
                StandardTestDispatcher(testScheduler),
                SdkHistoryFocuses(1),
            ) {
                null
            }
        assertIs<AppResult.Failed>(windows.read("!one:test", "\$original"))
        assertEquals(
            "client.collaboration_history_focus_capacity",
            assertIs<AppResult.Failed>(windows.read("!two:test", "\$original")).failure.code,
        )
        assertEquals(1, acquired)
        assertIs<AppResult.Success<*>>(windows.read("!one:test", "\$original"))
        assertEquals(2, acquired)
        assertEquals(AppResult.Success(Unit), children.close())
    }

    @Test
    fun rejectedChildRegistrationNeverConsumesAnUncreatedNativeFocus() = runTest {
        val children = SdkChildResources(this)
        assertEquals(AppResult.Success(Unit), children.close())
        val focuses = SdkHistoryFocuses(1)
        val room = HistoryRoom()
        val windows =
            AndroidCollaborationHistoryWindows(
                client(room),
                children,
                StandardTestDispatcher(testScheduler),
                focuses,
            ) {
                null
            }
        assertIs<AppResult.Failed>(windows.read("!one:test", "\$original"))
        assertTrue(room.calls.isEmpty())
        assertTrue(focuses.reserve("!two:test", "\$other"))
    }

    private fun client(room: HistoryRoom) =
        object : Client(NoHandle) {
            override fun getRoom(roomId: String): Room = room
        }
}
