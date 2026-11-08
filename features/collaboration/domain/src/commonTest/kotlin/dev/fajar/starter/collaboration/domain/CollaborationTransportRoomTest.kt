package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.collaborationTransportRoom
import dev.fajar.starter.common.result.*
import kotlin.test.*

class CollaborationTransportRoomTest {
    private val owner =
        CollaborationOwner("local", "https://example.test/", "account", "company", "parent")
    private val value =
        CollaborationDirectTransport(
            owner,
            "device",
            1,
            CollaborationNativePlatform.ANDROID,
            "conversation",
            "account",
            "peer",
            "!opaque-v12-room",
            0,
            true,
        )

    @Test
    fun unreadyRecipientBlocksDeliveryWithoutRevokingOwnedContent() {
        val failure = assertIs<AppResult.Failed>(match(value.copy(recipientReady = false))).failure
        assertEquals(FailureKind.Conflict, failure.kind)
        assertEquals("client.collaboration_recipient_unavailable", failure.code)
        assertEquals(AppResult.Success(value.matrixRoomId), match(value))
    }

    @Test
    fun currentSnapshotSelectsOnlyItsExactRoom() {
        assertEquals(AppResult.Success("!opaque-v12-room"), match(value))
        for (wrongOwner in
            listOf(
                owner.copy(sessionId = "other"),
                owner.copy(accountId = "other"),
                owner.copy(companyId = "other"),
                owner.copy(nativeSessionId = "other"),
                owner.copy(audience = "other"),
            )) {
            val failure = assertIs<AppResult.Failed>(match(value.copy(owner = wrongOwner))).failure
            assertEquals(FailureKind.Unauthorized, failure.kind)
            assertEquals("client.collaboration_queue_owner_changed", failure.code)
        }
    }

    @Test
    fun changedDeviceConversationAndMalformedRoomDoNotAdmitAWriter() {
        val cases =
            listOf(
                Triple(
                    FailureKind.Unavailable,
                    "client.collaboration_device_unavailable",
                    listOf(
                        value.copy(deviceId = "other"),
                        value.copy(deviceVersion = -1),
                        value.copy(platform = CollaborationNativePlatform.IOS),
                    ),
                ),
                Triple(
                    FailureKind.AccessDenied,
                    "client.collaboration_conversation_mismatch",
                    listOf(
                        value.copy(conversationId = "other"),
                        value.copy(firstAccountId = "other"),
                        value.copy(secondAccountId = "account"),
                    ),
                ),
                Triple(
                    FailureKind.Unavailable,
                    "client.collaboration_room_not_ready",
                    listOf(
                        value.copy(matrixRoomId = "!line\n"),
                        value.copy(matrixRoomId = "!"),
                        value.copy(matrixRoomId = "!" + "x".repeat(255)),
                        value.copy(roomProvisioningVersion = -1),
                    ),
                ),
            )
        for ((kind, code, candidates) in cases) {
            for (candidate in candidates) {
                val failure = assertIs<AppResult.Failed>(match(candidate)).failure
                assertEquals(kind, failure.kind)
                assertEquals(code, failure.code)
            }
        }
    }

    private fun match(candidate: CollaborationDirectTransport) =
        collaborationTransportRoom(candidate, owner, "device", "conversation", "ANDROID")
}
