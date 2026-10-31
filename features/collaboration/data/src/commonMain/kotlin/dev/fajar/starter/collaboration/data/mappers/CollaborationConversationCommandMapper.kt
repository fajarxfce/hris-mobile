package dev.fajar.starter.collaboration.data.mappers

import dev.fajar.starter.collaboration.data.dto.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.time.Instant
import kotlinx.serialization.json.Json

internal fun conversationCommandPartition(owner: CollaborationOwner) =
    "hris.collaboration.conversation/" +
        collaborationId(owner.companyId) +
        "/" +
        collaborationId(owner.nativeSessionId)

internal fun conversationCommandResource(value: String): String {
    val kind = value.substringBefore('/')
    require(kind in setOf("direct", "room"))
    require(value == kind + "/" + collaborationId(value.substringAfter('/')))
    return value
}

internal fun conversationCommandAad(
    owner: CollaborationOwner,
    id: String,
    resource: String,
    purpose: String,
    revision: Long,
) =
    Json.encodeToString(
            listOf(
                "hris-collaboration-conversation-v1",
                owner.audience,
                owner.accountId,
                owner.companyId,
                owner.nativeSessionId,
                id,
                resource,
                purpose,
                revision.toString(),
            )
        )
        .encodeToByteArray()

internal fun CollaborationConversationCommand.orderKey() =
    createdAt.toEpochMilliseconds().toString().padStart(15, '0') + "/" + id

internal fun CollaborationConversationCommand.toDto(): CollaborationConversationCommandDto {
    val open = action as? CollaborationConversationAction.Open
    val room = action as? CollaborationConversationAction.RequestRoom
    return CollaborationConversationCommandDto(
        id,
        binding.audience,
        binding.accountId,
        binding.companyId,
        binding.nativeSessionId,
        createdAt.toString(),
        if (open != null) "OPEN" else "ROOM",
        open?.peerAccountId,
        room?.conversationId,
        room?.deviceId,
        room?.deviceVersion,
        room?.provisioningVersion,
    )
}

internal fun CollaborationConversationCommandDto.toCommand(
    owner: CollaborationOwner
): CollaborationConversationCommand {
    listOf(id, accountId, companyId, nativeSessionId).forEach(::collaborationId)
    require(
        CollaborationSetupBinding(audience, accountId, companyId, nativeSessionId) ==
            owner.setupBinding()
    )
    val created = Instant.parse(createdAt)
    require(created.toEpochMilliseconds() in 0 until 253_402_300_800_000L)
    val input =
        when (action) {
            "OPEN" -> {
                require(
                    conversationId == null &&
                        deviceId == null &&
                        deviceVersion == null &&
                        provisioningVersion == null
                )
                val peer = collaborationId(requireNotNull(peerAccountId))
                require(peer != owner.accountId)
                CollaborationConversationAction.Open(peer)
            }
            "ROOM" -> {
                require(peerAccountId == null)
                require(
                    deviceVersion != null &&
                        deviceVersion in 0..999_999 &&
                        provisioningVersion?.let { it in 0..999_998 } != false
                )
                CollaborationConversationAction.RequestRoom(
                    collaborationId(requireNotNull(conversationId)),
                    collaborationId(requireNotNull(deviceId)),
                    deviceVersion,
                    provisioningVersion,
                )
            }
            else -> error("Unsupported conversation command")
        }
    return CollaborationConversationCommand(id, owner.setupBinding(), created, input)
}

internal fun CollaborationConversationOutcome.toDto(
    attempts: Int
): CollaborationConversationOutcomeDto {
    require(attempts in 0..1_000)
    return when (this) {
        CollaborationConversationOutcome.Pending ->
            CollaborationConversationOutcomeDto("PENDING", attempts)
        is CollaborationConversationOutcome.Unconfirmed ->
            CollaborationConversationOutcomeDto(
                "UNCONFIRMED",
                attempts,
                failure =
                    failure?.let {
                        CollaborationSetupFailureDto(it.kind.name, it.code, it.retryAfterSeconds)
                    },
            )
        is CollaborationConversationOutcome.Confirmed ->
            CollaborationConversationOutcomeDto(
                "CONFIRMED",
                attempts,
                receipt = CollaborationReceiptDto(receipt.id, receipt.version),
            )
    }
}

internal fun CollaborationConversationOutcomeDto.toOutcome(
    owner: CollaborationOwner,
    command: CollaborationConversationCommand,
): CollaborationConversationOutcome {
    require(command.binding == owner.setupBinding() && attempts in 0..1_000)
    if (phase != "PENDING") require(attempts > 0)
    return when (phase) {
        "PENDING" -> {
            require(attempts == 0 && failure == null && receipt == null)
            CollaborationConversationOutcome.Pending
        }
        "UNCONFIRMED" -> {
            require(receipt == null)
            val error =
                failure?.let {
                    require(it.code == null || it.code.matches(Regex("[A-Za-z0-9_.:-]{1,100}")))
                    require(it.retryAfterSeconds == null || it.retryAfterSeconds in 0..86_400)
                    Failure(
                        FailureKind.valueOf(it.kind),
                        "Conversation operation could not be confirmed.",
                        code = it.code,
                        retryAfterSeconds = it.retryAfterSeconds,
                    )
                }
            CollaborationConversationOutcome.Unconfirmed(error)
        }
        "CONFIRMED" -> {
            require(failure == null)
            val value = requireNotNull(receipt).toReceipt()
            when (val action = command.action) {
                is CollaborationConversationAction.Open -> require(value.version == 0L)
                is CollaborationConversationAction.RequestRoom ->
                    require(value.id == action.conversationId && value.version <= 999_999)
            }
            CollaborationConversationOutcome.Confirmed(value)
        }
        else -> error("Unsupported conversation outcome")
    }
}
