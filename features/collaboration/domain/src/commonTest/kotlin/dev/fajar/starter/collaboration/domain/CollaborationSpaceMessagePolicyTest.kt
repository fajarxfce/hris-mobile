package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import kotlin.test.*
import kotlin.time.Instant

class CollaborationSpaceMessagePolicyTest {
    private val access =
        CollaborationSpaceLocalAccess("login", "https://hris.example.test/", id(1), id(2))
    private val intent =
        CollaborationSpaceMessageIntent(
            id(4),
            access.messageOwner(),
            id(3),
            Instant.parse("2026-11-10T00:00:00Z"),
            " Original message\n",
        )
    private val device =
        CollaborationMessageDevice(id(5), id(6), id(7), "!space:matrix.example.test")
    private val origin = CollaborationMessageOrigin("@employee:matrix.example.test", "a".repeat(64))
    private val staged =
        CollaborationSpaceMessageRecord(
            intent,
            2,
            CollaborationMessageState.Staged(device, "original-txn", origin),
        )
    private val sent =
        staged.copy(
            revision = 3,
            state = CollaborationMessageState.Sent(device, "original-txn", "\$sent", origin),
        )

    @Test
    fun ownedIntentSurvivesAgeAndLocalReauthentication() {
        assertTrue(validCollaborationSpaceMessageIntent(access, intent))
        assertTrue(
            validCollaborationSpaceMessageIntent(access.copy(sessionId = "renewed-login"), intent)
        )
        assertTrue(
            validCollaborationSpaceMessageIntent(
                access,
                intent.copy(createdAt = Instant.fromEpochMilliseconds(0)),
            )
        )
        assertEquals(" Original message\n", intent.text)
    }

    @Test
    fun anotherAudienceAccountOrSpaceCannotOwnTheIntent() {
        for (other in
            listOf(
                access.copy(audience = "https://other.example.test/"),
                access.copy(accountId = id(8)),
                access.copy(spaceId = id(9)),
            )) {
            assertFalse(validCollaborationSpaceMessageIntent(other, intent))
            assertFalse(validCollaborationSpaceMessageRecord(other, staged))
        }
    }

    @Test
    fun malformedLocalIdentityAndOperationCoordinatesAreRejected() {
        for (other in
            listOf(
                access.copy(sessionId = ""),
                access.copy(sessionId = "bad login"),
                access.copy(audience = "bad\nserver"),
                access.copy(accountId = zeroId),
                access.copy(spaceId = zeroId),
            )) assertFalse(
            validCollaborationSpaceMessageIntent(other, intent.copy(owner = other.messageOwner()))
        )
        for (invalid in
            listOf(
                intent.copy(id = zeroId),
                intent.copy(conversationId = zeroId),
                intent.copy(id = id(4).uppercase()),
                intent.copy(createdAt = Instant.fromEpochMilliseconds(-1)),
                intent.copy(createdAt = Instant.fromEpochMilliseconds(253_402_300_800_000L)),
            )) assertFalse(validCollaborationSpaceMessageIntent(access, invalid))
    }

    @Test
    fun textAndReplyRetainBoundedExactContent() {
        assertTrue(
            validCollaborationSpaceMessageIntent(
                access,
                intent.copy(text = "x".repeat(16_384), replyToEventId = "\$reply"),
            )
        )
        for (text in
            listOf(
                " ",
                "\u0000",
                "x".repeat(16_385),
                "中".repeat(10_923),
                0xD800.toChar().toString(),
            )) assertFalse(validCollaborationSpaceMessageIntent(access, intent.copy(text = text)))
        for (reply in listOf("event-without-prefix", "\$bad reply", "\$é")) assertFalse(
            validCollaborationSpaceMessageIntent(access, intent.copy(replyToEventId = reply))
        )
    }

    @Test
    fun newPhysicalStatesRequireOriginAndNonzeroOriginalDeviceIds() {
        assertTrue(validCollaborationSpaceMessageRecord(access, staged))
        assertTrue(validCollaborationSpaceMessageRecord(access, sent))
        assertFalse(
            validCollaborationSpaceMessageRecord(
                access,
                staged.copy(state = CollaborationMessageState.Staged(device, "original-txn")),
            )
        )
        assertFalse(
            validCollaborationSpaceMessageRecord(
                access,
                staged.copy(
                    revision = 1,
                    state = CollaborationMessageState.Staging(device, emptySet()),
                ),
            )
        )
        for (invalid in
            listOf(
                device.copy(nativeSessionId = zeroId),
                device.copy(deviceId = zeroId),
                device.copy(bootstrapOperationId = zeroId),
            )) assertFalse(
            validCollaborationSpaceMessageRecord(
                access,
                staged.copy(
                    state = CollaborationMessageState.Staged(invalid, "original-txn", origin)
                ),
            )
        )
    }

    @Test
    fun revisionsCannotMakeStartedWorkLookQueued() {
        val queued = CollaborationSpaceMessageRecord(intent, 0, CollaborationMessageState.Queued)
        assertTrue(validCollaborationSpaceMessageRecord(access, queued))
        assertTrue(
            validCollaborationSpaceMessageRecord(
                access,
                queued.copy(state = CollaborationMessageState.Cancelled),
            )
        )
        assertFalse(validCollaborationSpaceMessageRecord(access, queued.copy(revision = 1)))
        assertFalse(validCollaborationSpaceMessageRecord(access, staged.copy(revision = 0)))
        assertFalse(
            validCollaborationSpaceMessageRecord(
                access,
                staged.copy(revision = 9_007_199_254_740_992L),
            )
        )
    }

    @Test
    fun editContextCannotChangeReplyForwardingRoomOrSender() {
        val target =
            CollaborationEditTarget(
                device.roomId,
                "\$original",
                id(10),
                origin.sender,
                origin.contentHash,
                10,
                5,
                "\$reply",
                true,
            )
        val edited = intent.copy(replyToEventId = "\$reply", forwarded = true, editTarget = target)
        assertTrue(validCollaborationSpaceMessageIntent(access, edited))
        assertTrue(validCollaborationSpaceMessageRecord(access, staged.copy(intent = edited)))
        for (invalid in
            listOf(
                edited.copy(id = id(10)),
                edited.copy(replyToEventId = null),
                edited.copy(forwarded = false),
            )) assertFalse(validCollaborationSpaceMessageIntent(access, invalid))
        for (invalid in
            listOf(
                target.copy(roomId = "!elsewhere:matrix.example.test"),
                target.copy(sender = "@other:matrix.example.test"),
            )) assertFalse(
            validCollaborationSpaceMessageRecord(
                access,
                staged.copy(intent = edited.copy(editTarget = invalid)),
            )
        )
    }

    @Test
    fun onlyUnstartedCommandsCanBeLocallyCancelled() {
        val queued = CollaborationSpaceMessageRecord(intent, 0, CollaborationMessageState.Queued)
        assertTrue(validCollaborationSpaceMessageCancellation(access, queued))
        assertFalse(
            validCollaborationSpaceMessageCancellation(
                access,
                queued.copy(state = CollaborationMessageState.Cancelled),
            )
        )
        assertFalse(validCollaborationSpaceMessageCancellation(access, staged))
        assertFalse(validCollaborationSpaceMessageCancellation(access, sent))
    }

    @Test
    fun terminalEvidenceMustContainTheExactSentEventWithBoundedUniqueAliases() {
        assertTrue(
            validCollaborationSpaceMessageCompletion(
                access,
                CollaborationSpaceMessageCompletion(sent, listOf("\$sent", "\$alias")),
            )
        )
        for (events in
            listOf(
                emptyList(),
                listOf("\$other"),
                listOf("\$sent", "\$sent"),
                listOf("\$sent", "invalid"),
                listOf("\$sent") + (1..128).map { "\$alias$it" },
            )) assertFalse(
            validCollaborationSpaceMessageCompletion(
                access,
                CollaborationSpaceMessageCompletion(sent, events),
            )
        )
        assertFalse(
            validCollaborationSpaceMessageCompletion(
                access,
                CollaborationSpaceMessageCompletion(staged, listOf("\$sent")),
            )
        )
    }

    private companion object {
        const val zeroId = "00000000-0000-0000-0000-000000000000"

        fun id(value: Int) = "a2000000-0000-4000-8000-${value.toString().padStart(12, '0')}"
    }
}
