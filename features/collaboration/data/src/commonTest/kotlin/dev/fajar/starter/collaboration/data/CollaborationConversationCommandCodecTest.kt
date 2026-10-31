package dev.fajar.starter.collaboration.data

import dev.fajar.starter.collaboration.data.dto.*
import dev.fajar.starter.collaboration.data.mappers.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.serialization.json.Json

class CollaborationConversationCommandCodecTest {
    private val command =
        CollaborationConversationCommand(
            SETUP_OPERATION,
            setupOwner.setupBinding(),
            Instant.parse("2026-10-01T00:00:00Z"),
            CollaborationConversationAction.Open(PEER_ID),
        )

    @Test
    fun portableEncodingKeepsOriginalOperationAndNullInitialOrObservedRoomVersion() {
        for (action in
            listOf(
                CollaborationConversationAction.Open(PEER_ID),
                CollaborationConversationAction.RequestRoom(CONVERSATION_ID, SETUP_DEVICE, 1, null),
                CollaborationConversationAction.RequestRoom(CONVERSATION_ID, SETUP_DEVICE, 1, 4),
            )) {
            val original = command.copy(action = action)
            val json = Json.encodeToString(original.toDto())
            assertEquals(
                original,
                Json.decodeFromString<CollaborationConversationCommandDto>(json)
                    .toCommand(setupOwner),
            )
        }
    }

    @Test
    fun savedFailuresKeepOnlySafeClassificationAndRetryMetadata() {
        val outcome =
            CollaborationConversationOutcome.Unconfirmed(
                Failure(
                    FailureKind.Network,
                    "Private response body",
                    code = "collaboration_provider_busy",
                    retryAfterSeconds = 5,
                )
            )
        val json = Json.encodeToString(outcome.toDto(2))
        assertFalse(json.contains("Private response body"))
        val saved =
            assertIs<CollaborationConversationOutcome.Unconfirmed>(
                Json.decodeFromString<CollaborationConversationOutcomeDto>(json)
                    .toOutcome(setupOwner, command)
            )
        assertEquals(FailureKind.Network, saved.failure?.kind)
        assertEquals("collaboration_provider_busy", saved.failure?.code)
        assertEquals(5, saved.failure?.retryAfterSeconds)
        assertFalse(saved.failure!!.message.contains("Private response body"))
    }

    @Test
    fun malformedScopeIntentAndOutcomeCannotBeDecodedAsOwnedState() {
        assertFails { command.toDto().toCommand(setupOwner.copy(companyId = SETUP_JOB)) }
        assertFails {
            command
                .copy(action = CollaborationConversationAction.Open(setupOwner.accountId))
                .toDto()
                .toCommand(setupOwner)
        }
        assertFails {
            CollaborationConversationOutcomeDto("PENDING", 1).toOutcome(setupOwner, command)
        }
        assertFails {
            CollaborationConversationOutcome.Confirmed(
                    CollaborationSetupReceipt(CONVERSATION_ID, 1)
                )
                .toDto(1)
                .toOutcome(setupOwner, command)
        }
        val room =
            command.copy(
                action =
                    CollaborationConversationAction.RequestRoom(
                        CONVERSATION_ID,
                        SETUP_DEVICE,
                        1,
                        null,
                    )
            )
        assertFails {
            CollaborationConversationOutcome.Confirmed(CollaborationSetupReceipt(SETUP_JOB, 0))
                .toDto(1)
                .toOutcome(setupOwner, room)
        }
    }

    @Test
    fun authenticatedEnvelopeSeparatesResourcePurposeRevisionAndStableOwner() {
        val resource = command.action.resourceId()
        val original = conversationCommandAad(setupOwner, command.id, resource, "request", 0)
        assertContentEquals(
            original,
            conversationCommandAad(
                setupOwner.copy(sessionId = "restored"),
                command.id,
                resource,
                "request",
                0,
            ),
        )
        for (changed in
            listOf(
                conversationCommandAad(setupOwner, command.id, resource, "outcome", 0),
                conversationCommandAad(setupOwner, command.id, resource, "request", 1),
                conversationCommandAad(
                    setupOwner,
                    command.id,
                    "room/$CONVERSATION_ID",
                    "request",
                    0,
                ),
                conversationCommandAad(
                    setupOwner.copy(nativeSessionId = SETUP_JOB),
                    command.id,
                    resource,
                    "request",
                    0,
                ),
            )) assertFalse(original.contentEquals(changed))
    }
}
