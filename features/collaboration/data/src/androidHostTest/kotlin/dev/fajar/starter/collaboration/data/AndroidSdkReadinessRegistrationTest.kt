package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.repositories.AndroidSdkReadinessRegistration
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.COLLABORATION_READINESS_CONTROL_TYPE
import kotlin.test.*
import kotlinx.serialization.json.*
import org.junit.Test
import org.matrix.rustcomponents.sdk.EventEncryptionInfo
import org.matrix.rustcomponents.sdk.NoHandle
import org.matrix.rustcomponents.sdk.ShieldState
import org.matrix.rustcomponents.sdk.TaskHandle
import org.matrix.rustcomponents.sdk.ToDeviceMessage

class AndroidSdkReadinessRegistrationTest {
    @Test
    fun fixedOwnControlKeepsExactLongRevisionAndTrustedSource() {
        val f = Fixture()
        val packet = f.packet()
        f.registration.onMessage(packet)
        packet.content = "changed after callback"
        assertEquals(f.owner, f.signals.single().sourceOwner)
        assertEquals(f.device, f.signals.single().sourceDeviceId)
        assertEquals(9_007_199_254_740_993L, f.signals.single().control.publicationRevision)
        assertEquals("AndroidSdkReadinessRegistration([redacted])", f.registration.toString())
        f.registration.close()
    }

    @Test
    fun foreignMalformedAndOversizedPacketsNeverReachTheConsumer() {
        val f = Fixture()
        val valid = f.packet()
        val encrypted =
            valid.copy(
                encryptionInfo =
                    EventEncryptionInfo(
                        "@owned:example.test",
                        "DEVICE",
                        "fixture",
                        "session",
                        ShieldState.None,
                        ShieldState.None,
                    )
            )
        val packets =
            listOf(
                encrypted,
                valid.copy(senderId = "@other:example.test"),
                valid.copy(eventType = "other"),
                valid.copy(content = "{"),
                valid.copy(content = "[]"),
                valid.copy(content = "a".repeat(4097)),
                valid.copy(content = Json.encodeToString(f.fields + ("body" to "private"))),
                valid.copy(content = Json.encodeToString(f.fields + ("device_id" to id(8)))),
                valid.copy(content = Json.encodeToString(f.fields + ("company_id" to id(8)))),
                valid.copy(
                    content = Json.encodeToString(f.fields + ("native_session_id" to id(8)))
                ),
                valid.copy(content = Json.encodeToString(f.fields + ("account_id" to id(8)))),
                valid.copy(
                    content = Json.encodeToString(f.fields + ("publication_revision" to "01"))
                ),
                valid.copy(
                    content =
                        Json.encodeToString(f.fields)
                            .replace("\"9007199254740993\"", "9007199254740993")
                ),
            )
        packets.forEach(f.registration::onMessage)
        assertTrue(f.signals.isEmpty())
        f.registration.close()
    }

    @Test
    fun obsoleteOrClosedClientNeverDeliversALateCallback() {
        val f = Fixture()
        f.current = false
        f.registration.onMessage(f.packet())
        f.current = true
        f.registration.attach(f.task("original"))
        f.registration.deactivate()
        f.registration.onMessage(f.packet())
        f.registration.close()
        f.registration.close()
        f.registration.onMessage(f.packet())
        assertTrue(f.signals.isEmpty())
        assertEquals(listOf("original.cancel", "original.close"), f.calls)
    }

    @Test
    fun returnedHandleAfterClosingIsImmediatelyReleased() {
        val f = Fixture()
        f.registration.close()
        f.registration.attach(f.task("late"))
        f.registration.close()
        assertEquals(listOf("late.cancel", "late.close"), f.calls)
    }

    @Test
    fun failedCancellationStillReleasesHandleAndFencesCallbacks() {
        val f = Fixture()
        f.registration.attach(f.task("failed", cancelFails = true))
        assertFailsWith<IllegalStateException> { f.registration.close() }
        f.registration.close()
        f.registration.onMessage(f.packet())
        assertTrue(f.signals.isEmpty())
        assertEquals(listOf("failed.cancel", "failed.close"), f.calls)
    }

    @Test
    fun rejectedSecondHandleCannotLeakOrCloseTheFirstPrematurely() {
        val f = Fixture()
        f.registration.attach(f.task("first"))
        assertFailsWith<IllegalStateException> { f.registration.attach(f.task("second")) }
        assertEquals(listOf("second.cancel", "second.close"), f.calls)
        f.registration.close()
        assertEquals(
            listOf("second.cancel", "second.close", "first.cancel", "first.close"),
            f.calls,
        )
    }

    private class Fixture {
        val owner = CollaborationOwner("local", "https://example.test", id(1), id(2), id(3))
        val device = id(4)
        var current = true
        val calls = mutableListOf<String>()
        val signals = mutableListOf<CollaborationReadinessSignal>()
        val registration =
            AndroidSdkReadinessRegistration.company(
                owner,
                device,
                "@owned:example.test",
                { current },
                { signals += it },
            )
        val fields =
            mapOf(
                "schema_version" to "2",
                "event_id" to id(5),
                "company_id" to owner.companyId,
                "account_id" to owner.accountId,
                "native_session_id" to owner.nativeSessionId,
                "device_id" to device,
                "publication_revision" to "9007199254740993",
                "published_at" to "1794171600123",
                "expires_at" to "1794258000123",
            )

        fun packet() =
            ToDeviceMessage(
                COLLABORATION_READINESS_CONTROL_TYPE,
                "@owned:example.test",
                Json.encodeToString(fields),
                null,
            )

        fun task(name: String, cancelFails: Boolean = false) =
            object : TaskHandle(NoHandle) {
                override fun cancel() {
                    calls += "$name.cancel"
                    if (cancelFails) error("Fictional cancellation failure")
                }

                override fun close() {
                    calls += "$name.close"
                }
            }
    }

    companion object {
        private fun id(value: Int) = "a1000000-0000-4000-8000-" + value.toString().padStart(12, '0')
    }
}
