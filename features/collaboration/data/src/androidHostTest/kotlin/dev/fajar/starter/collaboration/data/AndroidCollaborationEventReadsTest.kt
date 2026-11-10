package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.datasources.CollaborationRawEventSource
import dev.fajar.starter.collaboration.data.dto.*
import dev.fajar.starter.collaboration.data.repositories.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.network.RequestFailureException
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidCollaborationEventReadsTest {
    @Test
    fun exactMappedEventKeepsPhysicalIdentityAndIndependentRoomFacts() = runTest {
        val f = EventReadFixture(backgroundScope)
        f.source.read = { room, event -> eventReadDto(room, event).copy(roomJoined = false) }
        val snapshot = assertIs<AppResult.Success<CollaborationEventSnapshot>>(f.read()).value
        assertEquals(setupOwner, snapshot.owner)
        assertEquals(SETUP_DEVICE, snapshot.deviceId)
        assertEquals("!room:test", snapshot.roomId)
        assertFalse(snapshot.roomJoined)
        assertTrue(snapshot.roomEncrypted)
        assertEquals("\$original", snapshot.event.eventId)
        assertEquals(
            "Fictional text",
            assertIs<CollaborationAcquiredEventContent.Original>(snapshot.event.content)
                .message
                .text,
        )
        assertEquals(1, f.source.calls)
        assertFalse(snapshot.toString().contains("Fictional"))
        assertEquals(AppResult.Success(Unit), f.children.close())
    }

    @Test
    fun invalidCoordinatesNeverStartNativeWork() = runTest {
        val f = EventReadFixture(backgroundScope)
        for ((room, event) in
            listOf(
                "bad" to "\$event",
                "!room:test" to "bad",
                "!" to "\$event",
                "!bad room:test" to "\$event",
            )) {
            assertEquals(
                FailureKind.Validation,
                assertIs<AppResult.Failed>(f.reader.read(room, event)).failure.kind,
            )
        }
        assertEquals(0, f.source.calls)
    }

    @Test
    fun alreadyCancelledCallerCannotAcquireAChild() = runTest {
        val f = EventReadFixture(backgroundScope)
        val caller = launch {
            currentCoroutineContext().cancel()
            f.read()
            error("Cancelled caller published an event")
        }
        caller.join()
        assertTrue(caller.isCancelled)
        assertEquals(0, f.source.calls)
    }

    @Test
    fun existingDenialPreventsRawIo() = runTest {
        val f = EventReadFixture(backgroundScope)
        f.failure = denied
        assertEquals(AppResult.Failed(denied), f.read())
        assertEquals(0, f.source.calls)
    }

    @Test
    fun denialWhileInitializationIsQueuedPreventsRawIo() = runTest {
        val f = EventReadFixture(backgroundScope)
        val caller = async(start = CoroutineStart.UNDISPATCHED) { f.read() }
        f.failure = denied
        assertEquals(AppResult.Failed(denied), caller.await())
        assertEquals(0, f.source.calls)
    }

    @Test
    fun coordinatesReturnedBySourceCannotRelabelAnotherEventOrRoom() = runTest {
        val f = EventReadFixture(backgroundScope)
        for (raw in listOf(eventReadDto(event = "\$other"), eventReadDto(room = "!other:test"))) {
            f.source.read = { _, _ -> raw }
            assertIs<AppResult.Failed>(f.read())
        }
        assertEquals(2, f.source.calls)
    }

    @Test
    fun denialAfterRawReadRejectsTheContent() = runTest {
        val f = EventReadFixture(backgroundScope)
        f.source.read = { room, event ->
            f.failure = denied
            eventReadDto(room, event)
        }
        assertEquals(AppResult.Failed(denied), f.read())
        assertEquals(1, f.source.calls)
    }

    @Test
    fun lateAuthoritativeDenialSurvivesAnIoFailure() = runTest {
        val f = EventReadFixture(backgroundScope)
        f.source.read = { _, _ ->
            f.failure = denied
            error("Fictional native failure")
        }
        assertEquals(AppResult.Failed(denied), f.read())
    }

    @Test
    fun classifiedRequestFailuresSurviveWithoutBeingCalledStorageErrors() = runTest {
        val f = EventReadFixture(backgroundScope)
        for (kind in listOf(FailureKind.Unauthorized, FailureKind.AccessDenied)) {
            val failure = Failure(kind, "Fictional rejection", code = "fixture.rejected")
            f.source.read = { _, _ -> throw RequestFailureException(failure) }
            assertSame(failure, assertIs<AppResult.Failed>(f.read()).failure)
        }
    }

    @Test
    fun malformedRawContentIsAnAcquisitionFailureAndReleasesCapacity() = runTest {
        val f = EventReadFixture(backgroundScope, capacity = 1)
        f.source.read = { room, event -> eventReadDto(room, event).copy(json = "{") }
        assertEquals(FailureKind.Unexpected, assertIs<AppResult.Failed>(f.read()).failure.kind)
        f.source.read = ::eventReadDto
        assertIs<AppResult.Success<*>>(f.read())
        assertEquals(2, f.source.calls)
    }

    @Test
    fun stoppedParentCannotStartARead() = runTest {
        val f = EventReadFixture(backgroundScope)
        assertEquals(AppResult.Success(Unit), f.children.close())
        assertEquals(
            "client.collaboration_children_stopped",
            assertIs<AppResult.Failed>(f.read()).failure.code,
        )
        assertEquals(0, f.source.calls)
    }

    @Test
    fun cancelledWaiterKeepsLateIoUnderParentOwnershipUntilItFinishes() = runTest {
        val f = EventReadFixture(backgroundScope, capacity = 1)
        val release = CompletableDeferred<Unit>()
        f.source.read = { room, event ->
            release.await()
            eventReadDto(room, event)
        }
        val caller = async { f.read() }
        runCurrent()
        caller.cancel()
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(caller.isCompleted)
        assertEquals(
            "client.collaboration_children_full",
            assertIs<AppResult.Failed>(f.read()).failure.code,
        )
        assertEquals(1, f.source.calls)
        release.complete(Unit)
        runCurrent()
        assertIs<AppResult.Success<*>>(f.read())
        assertEquals(2, f.source.calls)
    }

    @Test
    fun expiredWaiterRetainsCapacityAndDoesNotRetryTheNativeRead() = runTest {
        val f = EventReadFixture(backgroundScope, capacity = 1)
        val release = CompletableDeferred<Unit>()
        f.source.read = { room, event ->
            release.await()
            eventReadDto(room, event)
        }
        val caller = async { f.read() }
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(
            "client.collaboration_child_pending",
            assertIs<AppResult.Failed>(caller.await()).failure.code,
        )
        assertEquals(
            "client.collaboration_children_full",
            assertIs<AppResult.Failed>(f.read()).failure.code,
        )
        assertEquals(1, f.source.calls)
        release.complete(Unit)
        runCurrent()
        assertIs<AppResult.Success<*>>(f.read())
        assertEquals(2, f.source.calls)
    }

    @Test
    fun closingParentRejectsTheInFlightResultAndWaitsForNativeRead() = runTest {
        val f = EventReadFixture(backgroundScope)
        val release = CompletableDeferred<Unit>()
        f.source.read = { room, event ->
            release.await()
            eventReadDto(room, event)
        }
        val caller = async { f.read() }
        runCurrent()
        val closing = async { f.children.close() }
        runCurrent()
        assertFalse(closing.isCompleted)
        release.complete(Unit)
        assertIs<AppResult.Failed>(caller.await())
        assertEquals(AppResult.Success(Unit), closing.await())
        assertEquals(1, f.source.calls)
    }

    @Test
    fun overlappingReadsKeepSeparateCapturesEvenWhenTheyFinishInReverseOrder() = runTest {
        val f = EventReadFixture(backgroundScope)
        val releaseFirst = CompletableDeferred<Unit>()
        f.source.read = { room, event ->
            if (event == "\$first") releaseFirst.await()
            eventReadDto(room, event)
        }
        val first = async { f.reader.read("!one:test", "\$first") }
        runCurrent()
        val second =
            assertIs<AppResult.Success<CollaborationEventSnapshot>>(
                    f.reader.read("!two:test", "\$second")
                )
                .value
        assertEquals("!two:test", second.roomId)
        assertEquals("\$second", second.event.eventId)
        releaseFirst.complete(Unit)
        val original = assertIs<AppResult.Success<CollaborationEventSnapshot>>(first.await()).value
        assertEquals("!one:test", original.roomId)
        assertEquals("\$first", original.event.eventId)
        assertEquals(2, f.source.calls)
        assertEquals(AppResult.Success(Unit), f.children.close())
    }

    private val denied =
        Failure(FailureKind.AccessDenied, "Fictional denial", code = "fixture.denied")
}

private class EventReadFixture(scope: CoroutineScope, capacity: Int = 8) {
    var failure: Failure? = null
    val children = SdkChildResources(scope, capacity)
    val source = EventReadSource()
    val reader =
        AndroidCollaborationEventReads(setupOwner, SETUP_DEVICE, source, children) { failure }

    suspend fun read() = reader.read("!room:test", "\$original")
}

private class EventReadSource : CollaborationRawEventSource {
    var calls = 0
    var read: suspend (String, String) -> CollaborationSdkRawEventDto = ::eventReadDto

    override suspend fun read(roomId: String, eventId: String): CollaborationSdkRawEventDto {
        calls++
        return read.invoke(roomId, eventId)
    }
}

internal fun eventReadDto(room: String = "!room:test", event: String = "\$original") =
    CollaborationSdkRawEventDto(
        room,
        event,
        true,
        true,
        CollaborationSdkRawEventEncryption.DECRYPTED,
        """{"event_id":"$event","room_id":"$room","sender":"@sender:test","origin_server_ts":10,"type":"m.room.message","content":{"msgtype":"m.text","body":"Fictional text"}}""",
    )
