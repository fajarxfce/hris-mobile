package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.datasources.AndroidCollaborationRawEventSource
import dev.fajar.starter.collaboration.data.dto.CollaborationSdkRawEventEncryption
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.matrix.rustcomponents.sdk.*

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidCollaborationRawEventSourceTest {
    @Test
    fun exactReadRetainsEverySdkEncryptionClassificationAndUnknownFields() = runTest {
        val classifications =
            listOf(
                RawEventEncryption.NOT_DECRYPTED to
                    CollaborationSdkRawEventEncryption.NOT_DECRYPTED,
                RawEventEncryption.DECRYPTED to CollaborationSdkRawEventEncryption.DECRYPTED,
                RawEventEncryption.UNABLE_TO_DECRYPT to
                    CollaborationSdkRawEventEncryption.UNABLE_TO_DECRYPT,
            )
        for ((native, expected) in classifications) {
            val room = RawEventRoom(encryption = native)
            val original = room.raw.json
            val sdk = RawEventClient { room }
            val result = AndroidCollaborationRawEventSource(sdk).read("!one:test", "\$one")
            assertEquals("!one:test", result.roomId)
            assertEquals("\$one", result.eventId)
            assertTrue(result.roomJoined)
            assertTrue(result.roomEncrypted)
            assertEquals(expected, result.encryption)
            assertEquals(original, result.json)
            assertEquals(listOf("!one:test"), sdk.rooms)
            assertEquals(listOf("membership", "encryption", "read:\$one", "close"), room.calls)
            assertEquals("", room.raw.json)
            assertEquals(0, sdk.closes)
            assertFalse(result.toString().contains("fixture"))
        }
    }

    @Test
    fun detachedResultSurvivesLaterMutationOfNativeValue() = runTest {
        val room = RawEventRoom()
        val original = room.raw.json
        val result =
            AndroidCollaborationRawEventSource(RawEventClient { room }).read("!one:test", "\$one")
        room.raw.json = "different native content"
        room.raw.encryption = RawEventEncryption.UNABLE_TO_DECRYPT
        assertEquals(original, result.json)
        assertEquals(CollaborationSdkRawEventEncryption.DECRYPTED, result.encryption)
    }

    @Test
    fun membershipAndEncryptionAreFactsWithoutImplicitProvisioning() = runTest {
        for (membership in Membership.entries) {
            val room = RawEventRoom(membership = membership, encrypted = false)
            val result =
                AndroidCollaborationRawEventSource(RawEventClient { room })
                    .read("!one:test", "\$one")
            assertEquals(membership == Membership.JOINED, result.roomJoined)
            assertFalse(result.roomEncrypted)
            assertEquals(CollaborationSdkRawEventEncryption.DECRYPTED, result.encryption)
            assertEquals(listOf("membership", "encryption", "read:\$one", "close"), room.calls)
        }
    }

    @Test
    fun missingKnownRoomFailsWithoutJoiningOrRetrying() = runTest {
        val sdk = RawEventClient { null }
        assertFailsWith<IllegalStateException> {
            AndroidCollaborationRawEventSource(sdk).read("!missing:test", "\$one")
        }
        assertEquals(listOf("!missing:test"), sdk.rooms)
        assertEquals(0, sdk.closes)
    }

    @Test
    fun alreadyCancelledInvocationStartsNoSdkWork() = runTest {
        val sdk = RawEventClient { error("No SDK acquisition allowed") }
        val request = async {
            currentCoroutineContext().cancel()
            AndroidCollaborationRawEventSource(sdk).read("!one:test", "\$one")
        }
        assertFailsWith<CancellationException> { request.await() }
        assertTrue(sdk.rooms.isEmpty())
    }

    @Test
    fun cancellationImmediatelyAfterRoomAcquisitionStillClosesIt() = runTest {
        val room = RawEventRoom()
        lateinit var request: Deferred<*>
        val sdk = RawEventClient {
            request.cancel()
            room
        }
        request = async { AndroidCollaborationRawEventSource(sdk).read("!one:test", "\$one") }
        assertFailsWith<CancellationException> { request.await() }
        assertEquals(listOf("close"), room.calls)
    }

    @Test
    fun cancellationDuringEncryptionReadClosesWithoutReadingEvent() = runTest {
        val room = RawEventRoom()
        room.beforeEncryption = { awaitCancellation() }
        val request = async {
            AndroidCollaborationRawEventSource(RawEventClient { room }).read("!one:test", "\$one")
        }
        runCurrent()
        request.cancelAndJoin()
        assertEquals(listOf("membership", "encryption", "close"), room.calls)
    }

    @Test
    fun cancellationDuringNativeEventReadClosesExactlyOnce() = runTest {
        val room = RawEventRoom()
        room.beforeRead = { awaitCancellation() }
        val request = async {
            AndroidCollaborationRawEventSource(RawEventClient { room }).read("!one:test", "\$one")
        }
        runCurrent()
        request.cancelAndJoin()
        assertEquals(listOf("membership", "encryption", "read:\$one", "close"), room.calls)
    }

    @Test
    fun lateValueAfterCancellationIsClearedAndCannotEscape() = runTest {
        val room = RawEventRoom()
        room.beforeRead = { currentCoroutineContext().cancel() }
        val request = async {
            AndroidCollaborationRawEventSource(RawEventClient { room }).read("!one:test", "\$one")
        }
        assertFailsWith<CancellationException> { request.await() }
        assertEquals("", room.raw.json)
        assertEquals(1, room.calls.count { it == "close" })
    }

    @Test
    fun acquisitionAndNativeReadExceptionsKeepTheirExactIdentity() = runTest {
        val expected = IllegalStateException("Fixture SDK read failure")
        val missing = RawEventClient { throw expected }
        assertSame(
            expected,
            assertFails { AndroidCollaborationRawEventSource(missing).read("!one:test", "\$one") },
        )
        assertEquals(0, missing.closes)
        for (atEncryption in listOf(true, false)) {
            val room = RawEventRoom()
            if (atEncryption) room.beforeEncryption = { throw expected }
            else room.beforeRead = { throw expected }
            assertSame(
                expected,
                assertFails {
                    AndroidCollaborationRawEventSource(RawEventClient { room })
                        .read("!one:test", "\$one")
                },
            )
            assertEquals(1, room.calls.count { it == "close" })
        }
    }

    @Test
    fun failedCleanupAfterSuccessDoesNotReturnContent() = runTest {
        val room = RawEventRoom()
        val expected = IllegalStateException("Fixture close failure")
        room.onClose = { throw expected }
        assertSame(
            expected,
            assertFails {
                AndroidCollaborationRawEventSource(RawEventClient { room })
                    .read("!one:test", "\$one")
            },
        )
        assertEquals("", room.raw.json)
        assertEquals(1, room.calls.count { it == "close" })
    }

    @Test
    fun cleanupFailureDoesNotReplaceTheOriginalReadFailure() = runTest {
        val room = RawEventRoom()
        val readFailure = IllegalStateException("Fixture read failure")
        val closeFailure = IllegalArgumentException("Fixture close failure")
        room.beforeRead = { throw readFailure }
        room.onClose = { throw closeFailure }
        val actual = assertFails {
            AndroidCollaborationRawEventSource(RawEventClient { room }).read("!one:test", "\$one")
        }
        assertSame(readFailure, actual)
        assertSame(closeFailure, actual.suppressedExceptions.single())
        assertEquals(1, room.calls.count { it == "close" })
    }

    @Test
    fun invalidUtf8SizeOrMalformedSurrogateCannotEscapeTheRawBoundary() = runTest {
        for (json in listOf("", "x".repeat(96_001), "é".repeat(48_001), "\uD800")) {
            val room = RawEventRoom(json = json)
            assertFails {
                AndroidCollaborationRawEventSource(RawEventClient { room })
                    .read("!one:test", "\$one")
            }
            assertEquals("", room.raw.json)
            assertEquals(1, room.calls.count { it == "close" })
        }
    }

    @Test
    fun exactUtf8ByteLimitIsAdmittedWithoutParsingOrRewriting() = runTest {
        val original = "é".repeat(48_000)
        val room = RawEventRoom(json = original)
        val result =
            AndroidCollaborationRawEventSource(RawEventClient { room }).read("!one:test", "\$one")
        assertEquals(original, result.json)
        assertEquals("", room.raw.json)
    }

    @Test
    fun concurrentInvocationsKeepSeparateRoomHandlesAndResults() = runTest {
        val first = RawEventRoom(json = "first raw event")
        val second = RawEventRoom(json = "second raw event")
        val releaseFirst = CompletableDeferred<Unit>()
        first.beforeRead = { releaseFirst.await() }
        val sdk = RawEventClient { if (it == "!one:test") first else second }
        val source = AndroidCollaborationRawEventSource(sdk)
        val pending = async { source.read("!one:test", "\$one") }
        runCurrent()
        val completed = source.read("!two:test", "\$two")
        assertFalse(pending.isCompleted)
        assertEquals("second raw event", completed.json)
        assertEquals("!two:test", completed.roomId)
        assertEquals("\$two", completed.eventId)
        assertEquals(0, first.calls.count { it == "close" })
        assertEquals(1, second.calls.count { it == "close" })
        releaseFirst.complete(Unit)
        val result = pending.await()
        assertEquals("first raw event", result.json)
        assertEquals("!one:test", result.roomId)
        assertEquals("\$one", result.eventId)
        assertEquals(1, first.calls.count { it == "close" })
        assertEquals(0, sdk.closes)
    }
}

private class RawEventClient(private val acquire: (String) -> Room?) : Client(NoHandle) {
    val rooms = mutableListOf<String>()
    var closes = 0

    override fun getRoom(roomId: String): Room? {
        rooms += roomId
        return acquire(roomId)
    }

    override fun close() {
        closes++
    }
}

private class RawEventRoom(
    json: String = """{"event_id":"${'$'}one","content":{"org.hris.message_id":"fixture"}}""",
    encryption: RawEventEncryption = RawEventEncryption.DECRYPTED,
    private val membership: Membership = Membership.JOINED,
    private val encrypted: Boolean = true,
) : Room(NoHandle) {
    val raw = RawTimelineEvent(json, encryption)
    val calls = mutableListOf<String>()
    var beforeEncryption: suspend () -> Unit = {}
    var beforeRead: suspend () -> Unit = {}
    var onClose: () -> Unit = {}

    override fun membership(): Membership {
        calls += "membership"
        return membership
    }

    override suspend fun isEncrypted(): Boolean {
        calls += "encryption"
        beforeEncryption()
        return encrypted
    }

    override suspend fun loadOrFetchRawEvent(eventId: String): RawTimelineEvent {
        calls += "read:$eventId"
        beforeRead()
        return raw
    }

    override fun close() {
        calls += "close"
        onClose()
    }
}
