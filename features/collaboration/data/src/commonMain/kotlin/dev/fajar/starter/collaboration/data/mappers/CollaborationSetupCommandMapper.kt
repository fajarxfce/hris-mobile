package dev.fajar.starter.collaboration.data.mappers

import dev.fajar.starter.collaboration.data.dto.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.database.CommandScope
import dev.fajar.starter.identity.domain.policies.accountStorageKey
import kotlin.time.Instant
import kotlinx.serialization.json.Json

internal fun collaborationCommandScope(owner: CollaborationOwner) =
    CommandScope(owner.sessionId, accountStorageKey(owner.audience, owner.accountId))

internal fun collaborationCommandPartition(owner: CollaborationOwner) =
    "hris.collaboration.setup/" +
        collaborationId(owner.companyId) +
        "/" +
        collaborationId(owner.nativeSessionId)

internal fun collaborationCommandAad(
    owner: CollaborationOwner,
    id: String,
    purpose: String,
    revision: Long,
) =
    Json.encodeToString(
            listOf(
                "hris-collaboration-setup-v1",
                owner.audience,
                owner.accountId,
                owner.companyId,
                owner.nativeSessionId,
                id,
                "setup",
                purpose,
                revision.toString(),
            )
        )
        .encodeToByteArray()

internal fun CollaborationSetupCommand.orderKey() =
    createdAt.toEpochMilliseconds().toString().padStart(15, '0') + "/" + id

internal fun CollaborationSetupCommand.toDto(): CollaborationSetupCommandDto {
    val register = action as? CollaborationSetupAction.Register
    val retry = action as? CollaborationSetupAction.Retry
    val bootstrap = action as? CollaborationSetupAction.Bootstrap
    return CollaborationSetupCommandDto(
        id,
        binding.audience,
        binding.accountId,
        binding.companyId,
        binding.nativeSessionId,
        platform.name,
        clientBuild,
        createdAt.toString(),
        when (action) {
            is CollaborationSetupAction.Register -> "REGISTER"
            is CollaborationSetupAction.Retry -> "RETRY"
            is CollaborationSetupAction.Bootstrap -> "BOOTSTRAP"
        },
        register?.label,
        retry?.deviceId ?: bootstrap?.deviceId,
        retry?.deviceVersion ?: bootstrap?.deviceVersion,
        retry?.provisioningVersion,
        bootstrap?.gatewayVersion,
    )
}

internal fun CollaborationSetupCommandDto.toCommand(
    owner: CollaborationOwner
): CollaborationSetupCommand {
    listOf(id, accountId, companyId, nativeSessionId).forEach(::collaborationId)
    require(
        CollaborationSetupBinding(audience, accountId, companyId, nativeSessionId) ==
            owner.setupBinding()
    )
    require(clientBuild in 1..Int.MAX_VALUE)
    val created = Instant.parse(createdAt)
    require(created.toEpochMilliseconds() in 0 until 253_402_300_800_000L)
    val request =
        when (action) {
            "REGISTER" -> {
                require(
                    deviceId == null &&
                        deviceVersion == null &&
                        provisioningVersion == null &&
                        gatewayVersion == null
                )
                val name = requireNotNull(label)
                require(
                    name == name.trim() &&
                        name.length in 1..64 &&
                        name.none { it.code in 0..31 || it.code in 127..159 }
                )
                CollaborationSetupAction.Register(name)
            }
            "RETRY" -> {
                require(label == null && gatewayVersion == null)
                CollaborationSetupAction.Retry(
                    collaborationId(requireNotNull(deviceId)),
                    requireDeviceVersion(),
                    requireNotNull(provisioningVersion).also { require(it in 0..999_998) },
                )
            }
            "BOOTSTRAP" -> {
                require(
                    label == null &&
                        provisioningVersion == null &&
                        (gatewayVersion?.let { it in 0..999_998 } != false)
                )
                CollaborationSetupAction.Bootstrap(
                    collaborationId(requireNotNull(deviceId)),
                    requireDeviceVersion(),
                    gatewayVersion,
                )
            }
            else -> error("Unsupported collaboration setup action.")
        }
    return CollaborationSetupCommand(
        id,
        owner.setupBinding(),
        CollaborationNativePlatform.valueOf(platform),
        clientBuild,
        created,
        request,
    )
}

private fun CollaborationSetupCommandDto.requireDeviceVersion() =
    requireNotNull(deviceVersion).also { require(it in 0..999_999) }
