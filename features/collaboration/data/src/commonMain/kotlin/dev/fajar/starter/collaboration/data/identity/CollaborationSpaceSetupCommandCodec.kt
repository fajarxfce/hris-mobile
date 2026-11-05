package dev.fajar.starter.collaboration.data.identity

import dev.fajar.starter.collaboration.data.dto.*
import dev.fajar.starter.collaboration.data.mappers.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.*
import dev.fajar.starter.identity.domain.policies.accountStorageKey
import dev.fajar.starter.securestorage.ContentCipher
import kotlin.io.encoding.Base64
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal fun spaceSetupCommandScope(access: CollaborationSpaceLocalAccess): CommandScope {
    require(
        validCollaborationSpaceAccountAccess(
            CollaborationSpaceAccountAccess(access.sessionId, access.audience, access.accountId)
        )
    )
    require(validCollaborationMessageId(access.spaceId))
    return CommandScope(access.sessionId, accountStorageKey(access.audience, access.accountId))
}

internal fun spaceSetupCommandPartition(access: CollaborationSpaceLocalAccess): String {
    spaceSetupCommandScope(access)
    return "hris.collaboration.space-setup/v1/" + access.spaceId
}

internal fun spaceSetupResource(nativeSessionId: String): String {
    require(validCollaborationMessageId(nativeSessionId))
    return "setup/" + nativeSessionId
}

internal fun CollaborationSpaceSetupCommand.orderKey() =
    createdAt.toEpochMilliseconds().toString().padStart(15, '0') + "/" + id

internal class CollaborationSpaceSetupCommandCodec(
    private val access: CollaborationSpaceLocalAccess,
    private val nativeSessionId: String,
    private val cipher: ContentCipher,
    private val key: ByteArray,
) {
    suspend fun request(command: CollaborationSpaceSetupCommand): String {
        require(validCollaborationSpaceSetupCommand(access, command))
        require(command.binding.nativeSessionId == nativeSessionId)
        val request = command.toSetupDto()
        require(request.command(access) == command)
        return seal(command.id, "request", 0, Json.encodeToString(request))
    }

    suspend fun outcome(
        command: CollaborationSpaceSetupCommand,
        revision: Long,
        outcome: CollaborationSpaceSetupOutcome,
        attempts: Int,
    ): String {
        require(command.binding.nativeSessionId == nativeSessionId)
        require(
            validCollaborationSpaceSetupRecord(
                access,
                CollaborationSpaceSetupRecord(command, revision, attempts, outcome),
            )
        )
        val state =
            when (outcome) {
                CollaborationSpaceSetupOutcome.Pending -> SetupOutcomeDto(1, "PENDING", attempts)
                is CollaborationSpaceSetupOutcome.Unconfirmed ->
                    SetupOutcomeDto(
                        1,
                        "UNCONFIRMED",
                        attempts,
                        failure =
                            outcome.failure?.let {
                                CollaborationSetupFailureDto(
                                    it.kind.name,
                                    it.code,
                                    it.retryAfterSeconds,
                                )
                            },
                    )
                is CollaborationSpaceSetupOutcome.Retried ->
                    SetupOutcomeDto(
                        1,
                        "RETRIED",
                        attempts,
                        receipt =
                            CollaborationReceiptDto(outcome.receipt.id, outcome.receipt.version),
                    )
                is CollaborationSpaceSetupOutcome.Bootstrapped ->
                    SetupOutcomeDto(
                        1,
                        "BOOTSTRAPPED",
                        attempts,
                        gateway = outcome.gateway.toSetupDto(),
                    )
            }
        state.domain(command)
        return seal(command.id, "outcome", revision, Json.encodeToString(state))
    }

    suspend fun decode(raw: CommandRecord): CollaborationSpaceSetupRecord {
        require(
            raw.partition == spaceSetupCommandPartition(access) &&
                raw.resourceId == spaceSetupResource(nativeSessionId)
        )
        require(validCollaborationMessageId(raw.id) && raw.revision in 0..9_007_199_254_740_991L)
        val command =
            Json.decodeFromString<SetupRequestDto>(open(raw.id, "request", 0, raw.request))
                .command(access)
        require(command.binding.nativeSessionId == nativeSessionId)
        require(command.id == raw.id && raw.sortKey == command.orderKey())
        val state =
            Json.decodeFromString<SetupOutcomeDto>(open(raw.id, "outcome", raw.revision, raw.state))
        return CollaborationSpaceSetupRecord(
                command,
                raw.revision,
                state.attempts,
                state.domain(command),
            )
            .also { require(validCollaborationSpaceSetupRecord(access, it)) }
    }

    private suspend fun seal(id: String, purpose: String, revision: Long, value: String): String {
        val bytes = value.encodeToByteArray()
        return try {
            require(bytes.size in 1..4096)
            Base64.encode(cipher.encrypt(key, aad(id, purpose, revision), bytes)).also {
                require(it.length <= 8192)
            }
        } finally {
            bytes.fill(0)
        }
    }

    private suspend fun open(id: String, purpose: String, revision: Long, value: String): String {
        require(value.length in 1..8192)
        val bytes = cipher.decrypt(key, aad(id, purpose, revision), Base64.decode(value))
        return try {
            require(bytes.size in 1..4096)
            bytes.decodeToString(throwOnInvalidSequence = true)
        } finally {
            bytes.fill(0)
        }
    }

    private fun aad(id: String, purpose: String, revision: Long) =
        Json.encodeToString(
                listOf(
                    "hris-collaboration-space-setup-v1",
                    access.audience,
                    access.accountId,
                    access.spaceId,
                    id,
                    spaceSetupResource(nativeSessionId),
                    purpose,
                    revision.toString(),
                )
            )
            .encodeToByteArray()
}

@Serializable
private data class SetupRequestDto(
    val schema: Int,
    val registrationId: String,
    val id: String,
    val audience: String,
    val accountId: String,
    val spaceId: String,
    val nativeSessionId: String,
    val platform: String,
    val clientBuild: Long,
    val createdAt: String,
    val kind: String,
    val deviceId: String,
    val deviceVersion: Long,
    val provisioningRevision: Long? = null,
    val gatewayVersion: Long? = null,
    val provider: CollaborationSpaceIdentityDto? = null,
    val matrixDeviceId: String? = null,
) {
    fun command(access: CollaborationSpaceLocalAccess): CollaborationSpaceSetupCommand {
        require(schema == 1)
        val binding = CollaborationSpaceDeviceBinding(audience, accountId, spaceId, nativeSessionId)
        val nativePlatform = CollaborationNativePlatform.valueOf(platform)
        val request =
            when (kind) {
                "RETRY" -> {
                    require(gatewayVersion == null && provider == null && matrixDeviceId == null)
                    CollaborationSpaceProvisioningRetry(
                        id,
                        binding,
                        deviceId,
                        deviceVersion,
                        requireNotNull(provisioningRevision),
                    )
                }
                "BOOTSTRAP" -> {
                    require(provisioningRevision == null)
                    val owner =
                        CollaborationSpaceOwner(
                            CollaborationNativeParent(
                                access.sessionId,
                                audience,
                                accountId,
                                nativeSessionId,
                            ),
                            spaceId,
                        )
                    CollaborationSpaceBootstrap(
                        id,
                        binding,
                        nativePlatform,
                        clientBuild,
                        deviceId,
                        deviceVersion,
                        gatewayVersion,
                        requireNotNull(provider).toSpaceIdentity(owner),
                        requireNotNull(matrixDeviceId),
                    )
                }
                else -> error("Unsupported native setup request.")
            }
        return CollaborationSpaceSetupCommand(
                registrationId,
                nativePlatform,
                clientBuild,
                Instant.parse(createdAt),
                request,
            )
            .also { require(validCollaborationSpaceSetupCommand(access, it)) }
    }
}

private fun CollaborationSpaceSetupCommand.toSetupDto(): SetupRequestDto {
    val retry = request as? CollaborationSpaceProvisioningRetry
    val bootstrap = request as? CollaborationSpaceBootstrap
    return SetupRequestDto(
        1,
        registrationId,
        id,
        binding.audience,
        binding.accountId,
        binding.spaceId,
        binding.nativeSessionId,
        platform.name,
        clientBuild,
        createdAt.toString(),
        if (retry != null) "RETRY" else "BOOTSTRAP",
        request.deviceId,
        request.deviceVersion,
        retry?.provisioningRevision,
        bootstrap?.gatewayVersion,
        bootstrap?.provider?.let {
            CollaborationSpaceIdentityDto(
                it.spaceId,
                it.accountId,
                it.serverName,
                it.matrixUserId,
                it.origin.name,
                it.legacyCompanyId,
                it.version,
                it.createdAt.toString(),
            )
        },
        bootstrap?.matrixDeviceId,
    )
}

private fun CollaborationSpaceGateway.toSetupDto() =
    CollaborationSpaceGatewayDto(
        binding.spaceId,
        binding.accountId,
        deviceId,
        binding.nativeSessionId,
        matrixUserId,
        matrixDeviceId,
        sessionId,
        version,
        accessToken,
        refreshToken,
        Instant.fromEpochMilliseconds(accessExpiresAtEpochMillis).toString(),
        Instant.fromEpochMilliseconds(expiresAtEpochMillis).toString(),
    )

@Serializable
private data class SetupOutcomeDto(
    val schema: Int,
    val phase: String,
    val attempts: Int,
    val failure: CollaborationSetupFailureDto? = null,
    val receipt: CollaborationReceiptDto? = null,
    val gateway: CollaborationSpaceGatewayDto? = null,
) {
    fun domain(command: CollaborationSpaceSetupCommand): CollaborationSpaceSetupOutcome {
        require(schema == 1 && attempts in 0..1_000)
        return when (phase) {
            "PENDING" -> {
                require(attempts == 0 && failure == null && receipt == null && gateway == null)
                CollaborationSpaceSetupOutcome.Pending
            }
            "UNCONFIRMED" -> {
                require(attempts > 0 && receipt == null && gateway == null)
                CollaborationSpaceSetupOutcome.Unconfirmed(
                    failure?.let {
                        require(it.code == null || it.code.matches(Regex("[A-Za-z0-9_.:-]{1,100}")))
                        require(it.retryAfterSeconds == null || it.retryAfterSeconds in 0..86_400)
                        Failure(
                            FailureKind.valueOf(it.kind),
                            "Native setup could not be confirmed.",
                            code = it.code,
                            retryAfterSeconds = it.retryAfterSeconds,
                        )
                    }
                )
            }
            "RETRIED" -> {
                require(attempts > 0 && failure == null && gateway == null)
                val value = requireNotNull(receipt)
                CollaborationSpaceSetupOutcome.Retried(
                    CollaborationSetupReceipt(value.id, value.version)
                )
            }
            "BOOTSTRAPPED" -> {
                require(attempts > 0 && failure == null && receipt == null)
                val request =
                    command.request as? CollaborationSpaceBootstrap
                        ?: error("Unexpected gateway outcome.")
                CollaborationSpaceSetupOutcome.Bootstrapped(
                    requireNotNull(gateway).toSpaceGateway(request)
                )
            }
            else -> error("Unsupported native setup outcome.")
        }
    }
}
