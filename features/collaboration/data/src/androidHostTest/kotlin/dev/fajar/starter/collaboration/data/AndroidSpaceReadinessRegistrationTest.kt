@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.datasources.AndroidCollaborationSdkClientSource
import dev.fajar.starter.collaboration.data.repositories.AndroidSdkReadinessRegistration
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.matrix.rustcomponents.sdk.*

class AndroidSpaceReadinessRegistrationTest {
    @Test
    fun actualOriginalSenderKeepsTrustedLogicalSourceAndExactRevision() {
        val f = Fixture()
        try {
            val packet = f.packet()
            f.registration.onMessage(packet)
            packet.content = "mutated after callback"
            val signal = f.signals.single()
            assertSame(f.source, signal.source)
            assertEquals(Long.MAX_VALUE, signal.control.publicationRevision)
            assertEquals(f.source.owner.spaceId, signal.control.spaceId)
            assertEquals(COLLABORATION_SPACE_READINESS_CONTROL_TYPE, f.registration.eventType)
        } finally {
            f.registration.close()
        }
    }

    @Test
    fun foreignCompanyEncryptedMalformedOrScopeChangingFramesNeverSelectAnOwner() {
        val f = Fixture()
        try {
            val valid = f.packet()
            val packets =
                mutableListOf(
                    valid.copy(senderId = "@derived_from_group:matrix.example.test"),
                    valid.copy(eventType = COLLABORATION_READINESS_CONTROL_TYPE),
                    valid.copy(
                        encryptionInfo =
                            EventEncryptionInfo(
                                f.sender,
                                "DEVICE",
                                "fixture",
                                "session",
                                ShieldState.None,
                                ShieldState.None,
                            )
                    ),
                    valid.copy(content = "{"),
                    valid.copy(content = "[]"),
                    valid.copy(content = "a".repeat(4097)),
                    valid.copy(
                        content =
                            Json.encodeToString(
                                (f.fields - "space_id") + ("company_id" to f.source.owner.spaceId)
                            )
                    ),
                    valid.copy(content = Json.encodeToString(f.fields + ("operation_id" to id(9)))),
                    valid.copy(
                        content =
                            Json.encodeToString(f.fields)
                                .replace("\"${Long.MAX_VALUE}\"", Long.MAX_VALUE.toString())
                    ),
                )
            for (key in listOf("space_id", "account_id", "native_session_id", "device_id")) {
                packets += valid.copy(content = Json.encodeToString(f.fields + (key to id(9))))
            }
            for (key in f.fields.keys) packets +=
                valid.copy(content = Json.encodeToString(f.fields - key))
            packets.forEach(f.registration::onMessage)
            assertTrue(f.signals.isEmpty())
        } finally {
            f.registration.close()
        }
    }

    @Test
    fun obsoleteGroupCallbacksAndLateHandlesCannotSurviveDisposal() {
        val f = Fixture()
        val calls = mutableListOf<String>()
        f.current = false
        f.registration.onMessage(f.packet())
        f.current = true
        f.registration.close()
        f.registration.onMessage(f.packet())
        f.registration.attach(
            object : TaskHandle(NoHandle) {
                override fun cancel() {
                    calls += "cancel"
                }

                override fun close() {
                    calls += "close"
                }
            }
        )
        f.registration.close()
        assertEquals(listOf("cancel", "close"), calls)
        assertTrue(f.signals.isEmpty())
    }

    @Test
    fun originalLeaseSubscribesOnlyToGroupTypeWithoutChangingPhysicalIdentityOrOpeningSync() =
        runTest {
            val f = runtimeLeaseFixture(StandardTestDispatcher(testScheduler))
            val source = logicalSource(f.initial.deviceId)
            val signals = mutableListOf<CollaborationSpaceReadinessSignal>()
            try {
                f.expectedReadinessEventType = COLLABORATION_SPACE_READINESS_CONTROL_TYPE
                val originalOwner = f.lease.owner
                assertNotEquals(originalOwner.nativeSessionId, source.owner.parent.nativeSessionId)
                repeat(3) {
                    f.lease.startReadinessObservation(
                        AndroidCollaborationSdkClientSource(),
                        source,
                        { error("No company signal") },
                        { signals += it },
                    )
                }
                assertEquals(listOf("readiness.subscribe"), f.calls)
                val listener = assertNotNull(f.readinessListener)
                listener.onMessage(packet(source, f.initial.matrixUserId))
                assertSame(source, signals.single().source)
                assertEquals(originalOwner, f.lease.owner)
                assertEquals(0, f.syncRequests)
                f.lease.finishAcquisition()
                assertEquals(AppResult.Success(Unit), f.lease.close())
                listener.onMessage(packet(source, f.initial.matrixUserId))
                assertEquals(1, signals.size)
                assertEquals(1, f.calls.count { it == "readiness.cancel" })
                assertEquals(1, f.calls.count { it == "readiness.close" })
                assertTrue(f.calls.indexOf("readiness.close") < f.calls.indexOf("client.close"))
            } finally {
                f.lease.finishAcquisition()
                f.close()
            }
        }

    @Test
    fun closingOriginalLeaseDuringGroupSubscriptionDisposesTheReturnedHandle() = runTest {
        val f = runtimeLeaseFixture(StandardTestDispatcher(testScheduler))
        try {
            f.expectedReadinessEventType = COLLABORATION_SPACE_READINESS_CONTROL_TYPE
            f.beforeReadinessSubscribe = { f.lease.requestClose() }
            val source = logicalSource(f.initial.deviceId)
            f.lease.startReadinessObservation(
                AndroidCollaborationSdkClientSource(),
                source,
                { error("No company signal") },
                { error("Closed group") },
            )
            assertEquals(
                listOf("readiness.subscribe", "readiness.cancel", "readiness.close"),
                f.calls,
            )
            assertNotNull(f.readinessListener).onMessage(packet(source, f.initial.matrixUserId))
            val closing = async { f.lease.close() }
            runCurrent()
            assertFalse(closing.isCompleted)
            assertFalse("directory.close" in f.calls)
            f.lease.finishAcquisition()
            runCurrent()
            assertEquals(AppResult.Success(Unit), closing.await())
            assertEquals(1, f.calls.count { it == "readiness.close" })
        } finally {
            f.lease.finishAcquisition()
            f.close()
        }
    }

    @Test
    fun groupControlArrivesThroughTheOneOriginalSyncWithoutJoiningOrEnablingQueues() = runTest {
        val f = runtimeLeaseFixture(StandardTestDispatcher(testScheduler))
        val source = logicalSource(f.initial.deviceId)
        val signals = mutableListOf<CollaborationSpaceReadinessSignal>()
        val response = CompletableDeferred<Unit>()
        try {
            f.expectedReadinessEventType = COLLABORATION_SPACE_READINESS_CONTROL_TYPE
            f.lease.startReadinessObservation(
                AndroidCollaborationSdkClientSource(),
                source,
                { error("No company control") },
                { signals += it },
            )
            val listener = assertNotNull(f.readinessListener)
            f.beforeSync = {
                listener.onMessage(packet(source, f.initial.matrixUserId))
                response.await()
            }
            f.lease.completeAcquisition()
            assertEquals(AppResult.Success(Unit), f.lease.publish())
            val first = async { f.lease.synchronizeDevice() }
            val second = async { f.lease.synchronizeDevice() }
            runCurrent()
            assertEquals(1, f.syncRequests)
            assertEquals(source, signals.single().source)
            assertFalse(first.isCompleted)
            response.complete(Unit)
            runCurrent()
            assertEquals(AppResult.Success(Unit), first.await())
            assertEquals(AppResult.Success(Unit), second.await())
            assertEquals(1, f.syncRequests)
            assertEquals(listOf(0uL), f.syncSettings.map { it.timeoutMs })
            assertEquals(listOf("readiness.subscribe"), f.calls.toList())
        } finally {
            response.complete(Unit)
            f.lease.finishAcquisition()
            f.close()
        }
        assertEquals(1, f.calls.count { it == "client.close" })
        assertEquals(1, f.calls.count { it == "directory.close" })
        assertEquals(1, f.calls.count { it == "released" })
        assertTrue(f.calls.indexOf("readiness.close") < f.calls.indexOf("client.close"))
    }

    @Test
    fun closingDuringGroupSyncDeactivatesControlsAndKeepsTheOriginalStoreUntilDrain() = runTest {
        val f = runtimeLeaseFixture(StandardTestDispatcher(testScheduler))
        val source = logicalSource(f.initial.deviceId)
        val signals = mutableListOf<CollaborationSpaceReadinessSignal>()
        val response = CompletableDeferred<Unit>()
        try {
            f.expectedReadinessEventType = COLLABORATION_SPACE_READINESS_CONTROL_TYPE
            f.lease.startReadinessObservation(
                AndroidCollaborationSdkClientSource(),
                source,
                { error("No company control") },
                { signals += it },
            )
            val listener = assertNotNull(f.readinessListener)
            f.beforeSync = { response.await() }
            f.lease.completeAcquisition()
            assertEquals(AppResult.Success(Unit), f.lease.publish())
            val waiting = async { f.lease.synchronizeDevice() }
            runCurrent()
            waiting.cancelAndJoin()
            val closing = async { f.lease.close() }
            runCurrent()
            listener.onMessage(packet(source, f.initial.matrixUserId))
            assertTrue(signals.isEmpty())
            assertFalse(closing.isCompleted)
            assertFalse("directory.close" in f.calls)
            assertIs<AppResult.Failed>(f.lease.synchronizeDevice())
            response.complete(Unit)
            runCurrent()
            assertEquals(AppResult.Success(Unit), closing.await())
            assertEquals(1, f.syncRequests)
            assertEquals(1, f.calls.count { it == "readiness.cancel" })
            assertEquals(1, f.calls.count { it == "readiness.close" })
        } finally {
            response.complete(Unit)
            f.lease.finishAcquisition()
            f.close()
        }
        assertEquals(1, f.calls.count { it == "client.close" })
        assertEquals(1, f.calls.count { it == "directory.close" })
        assertEquals(1, f.calls.count { it == "released" })
        assertTrue(f.calls.indexOf("readiness.close") < f.calls.indexOf("client.close"))
    }

    private class Fixture {
        val source = logicalSource(id(4))
        val sender = "@hris_original_company:matrix.example.test"
        val fields = fields(source)
        var current = true
        val signals = mutableListOf<CollaborationSpaceReadinessSignal>()
        val registration =
            AndroidSdkReadinessRegistration.organization(
                source,
                sender,
                { current },
                { signals += it },
            )

        fun packet() = packet(source, sender)
    }

    companion object {
        private fun id(value: Int) = "12345678-1234-4234-8234-" + value.toString().padStart(12, '0')

        private fun logicalSource(device: String) =
            CollaborationSpaceRuntimeReady(
                CollaborationSpaceOwner(
                    CollaborationNativeParent("logical", "https://example.test", id(1), id(2)),
                    id(3),
                ),
                device,
                id(5),
            )

        private fun fields(source: CollaborationSpaceRuntimeReady) =
            mapOf(
                "schema_version" to "1",
                "event_id" to id(6),
                "space_id" to source.owner.spaceId,
                "account_id" to source.owner.parent.accountId,
                "native_session_id" to source.owner.parent.nativeSessionId,
                "device_id" to source.deviceId,
                "publication_revision" to Long.MAX_VALUE.toString(),
                "published_at" to "1794171600123",
                "expires_at" to "1794258000123",
            )

        private fun packet(source: CollaborationSpaceRuntimeReady, sender: String) =
            ToDeviceMessage(
                COLLABORATION_SPACE_READINESS_CONTROL_TYPE,
                sender,
                Json.encodeToString(fields(source)),
                null,
            )
    }
}
