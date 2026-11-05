package dev.fajar.starter.collaboration.data.identity

import dev.fajar.starter.collaboration.data.dto.*
import dev.fajar.starter.collaboration.data.mappers.toSpaceIdentity
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

internal fun spaceRegistrationCommandScope(access: CollaborationSpaceLocalAccess): CommandScope {
    require(
        validCollaborationSpaceAccountAccess(
            CollaborationSpaceAccountAccess(access.sessionId, access.audience, access.accountId)
        )
    )
    require(validCollaborationMessageId(access.spaceId))
    return CommandScope(access.sessionId, accountStorageKey(access.audience, access.accountId))
}

internal fun spaceRegistrationCommandPartition(access: CollaborationSpaceLocalAccess): String {
    spaceRegistrationCommandScope(access)
    return "hris.collaboration.space-registration/v1/" + access.spaceId
}

internal fun spaceRegistrationResource(nativeSessionId: String): String {
    require(validCollaborationMessageId(nativeSessionId))
    return "registration/" + nativeSessionId
}

internal fun CollaborationSpaceRegistrationCommand.orderKey() =
    createdAt.toEpochMilliseconds().toString().padStart(15, '0') + "/" + id

internal class CollaborationSpaceRegistrationCommandCodec(
    private val access: CollaborationSpaceLocalAccess,
    private val nativeSessionId: String,
    private val cipher: ContentCipher,
    private val key: ByteArray,
) {
    suspend fun request(command: CollaborationSpaceRegistrationCommand): String {
        require(validCollaborationSpaceRegistrationCommand(access, command))
        require(command.binding.nativeSessionId == nativeSessionId)
        val b = command.binding
        val provider =
            command.provider.let {
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
            }
        require(
            provider.toSpaceIdentity(
                CollaborationSpaceOwner(
                    CollaborationNativeParent(
                        access.sessionId,
                        b.audience,
                        b.accountId,
                        b.nativeSessionId,
                    ),
                    b.spaceId,
                )
            ) == command.provider
        )
        return seal(
            command.id,
            "request",
            0,
            Json.encodeToString(
                RegistrationRequest(
                    1,
                    command.id,
                    b.audience,
                    b.accountId,
                    b.spaceId,
                    b.nativeSessionId,
                    command.platform.name,
                    command.clientBuild,
                    command.createdAt.toString(),
                    provider,
                    command.label,
                    command.origin.name,
                    command.legacy?.let {
                        OriginalSdk(
                            it.binding.audience,
                            it.binding.accountId,
                            it.binding.companyId,
                            it.binding.nativeSessionId,
                            it.deviceId,
                            it.identity.matrixUserId,
                            it.identity.matrixDeviceId,
                            it.identity.fingerprint,
                        )
                    },
                )
            ),
        )
    }

    suspend fun outcome(
        command: CollaborationSpaceRegistrationCommand,
        revision: Long,
        outcome: CollaborationSpaceRegistrationOutcome,
        attempts: Int,
    ): String {
        require(command.binding.nativeSessionId == nativeSessionId)
        require(
            validCollaborationSpaceRegistrationRecord(
                access,
                CollaborationSpaceRegistrationRecord(command, revision, attempts, outcome),
            )
        )
        val state =
            when (outcome) {
                CollaborationSpaceRegistrationOutcome.Pending ->
                    RegistrationOutcome(1, "PENDING", attempts)
                is CollaborationSpaceRegistrationOutcome.Unconfirmed ->
                    RegistrationOutcome(
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
                is CollaborationSpaceRegistrationOutcome.Registered ->
                    RegistrationOutcome(
                        1,
                        "REGISTERED",
                        attempts,
                        receipt =
                            CollaborationReceiptDto(outcome.receipt.id, outcome.receipt.version),
                    )
            }
        state.domain()
        return seal(command.id, "outcome", revision, Json.encodeToString(state))
    }

    suspend fun decode(raw: CommandRecord): CollaborationSpaceRegistrationRecord {
        require(
            raw.partition == spaceRegistrationCommandPartition(access) &&
                raw.resourceId == spaceRegistrationResource(nativeSessionId)
        )
        require(validCollaborationMessageId(raw.id) && raw.revision in 0..9_007_199_254_740_991L)
        val request =
            Json.decodeFromString<RegistrationRequest>(open(raw.id, "request", 0, raw.request))
        require(request.schema == 1)
        val command =
            CollaborationSpaceRegistrationCommand(
                request.id,
                CollaborationSpaceDeviceBinding(
                    request.audience,
                    request.accountId,
                    request.spaceId,
                    request.nativeSessionId,
                ),
                CollaborationNativePlatform.valueOf(request.platform),
                request.clientBuild,
                Instant.parse(request.createdAt),
                request.provider.toSpaceIdentity(
                    CollaborationSpaceOwner(
                        CollaborationNativeParent(
                            access.sessionId,
                            request.audience,
                            request.accountId,
                            request.nativeSessionId,
                        ),
                        request.spaceId,
                    )
                ),
                request.label,
                CollaborationSpaceDeviceOrigin.valueOf(request.origin),
                request.legacy?.let {
                    CollaborationSpaceLegacySdk(
                        CollaborationSetupBinding(
                            it.audience,
                            it.accountId,
                            it.companyId,
                            it.nativeSessionId,
                        ),
                        it.deviceId,
                        CollaborationLocalDeviceIdentity(
                            it.matrixUserId,
                            it.matrixDeviceId,
                            it.fingerprint,
                        ),
                    )
                },
            )
        require(validCollaborationSpaceRegistrationCommand(access, command))
        require(command.binding.nativeSessionId == nativeSessionId)
        require(command.id == raw.id && raw.sortKey == command.orderKey())
        val state =
            Json.decodeFromString<RegistrationOutcome>(
                open(raw.id, "outcome", raw.revision, raw.state)
            )
        return CollaborationSpaceRegistrationRecord(
                command,
                raw.revision,
                state.attempts,
                state.domain(),
            )
            .also { require(validCollaborationSpaceRegistrationRecord(access, it)) }
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
                    "hris-collaboration-space-registration-v1",
                    access.audience,
                    access.accountId,
                    access.spaceId,
                    id,
                    spaceRegistrationResource(nativeSessionId),
                    purpose,
                    revision.toString(),
                )
            )
            .encodeToByteArray()
}

@Serializable
private data class RegistrationRequest(
    val schema: Int,
    val id: String,
    val audience: String,
    val accountId: String,
    val spaceId: String,
    val nativeSessionId: String,
    val platform: String,
    val clientBuild: Long,
    val createdAt: String,
    val provider: CollaborationSpaceIdentityDto,
    val label: String,
    val origin: String,
    val legacy: OriginalSdk?,
)

@Serializable
private data class OriginalSdk(
    val audience: String,
    val accountId: String,
    val companyId: String,
    val nativeSessionId: String,
    val deviceId: String,
    val matrixUserId: String,
    val matrixDeviceId: String,
    val fingerprint: String,
)

@Serializable
private data class RegistrationOutcome(
    val schema: Int,
    val phase: String,
    val attempts: Int,
    val failure: CollaborationSetupFailureDto? = null,
    val receipt: CollaborationReceiptDto? = null,
) {
    fun domain(): CollaborationSpaceRegistrationOutcome {
        require(schema == 1 && attempts in 0..1_000)
        return when (phase) {
            "PENDING" -> {
                require(attempts == 0 && failure == null && receipt == null)
                CollaborationSpaceRegistrationOutcome.Pending
            }
            "UNCONFIRMED" -> {
                require(attempts > 0 && receipt == null)
                CollaborationSpaceRegistrationOutcome.Unconfirmed(
                    failure?.let {
                        require(it.code == null || it.code.matches(Regex("[A-Za-z0-9_.:-]{1,100}")))
                        require(it.retryAfterSeconds == null || it.retryAfterSeconds in 0..86_400)
                        Failure(
                            FailureKind.valueOf(it.kind),
                            "Device registration could not be confirmed.",
                            code = it.code,
                            retryAfterSeconds = it.retryAfterSeconds,
                        )
                    }
                )
            }
            "REGISTERED" -> {
                require(attempts > 0 && failure == null)
                val value = requireNotNull(receipt)
                CollaborationSpaceRegistrationOutcome.Registered(
                    CollaborationSetupReceipt(value.id, value.version)
                )
            }
            else -> error("Unsupported device registration outcome.")
        }
    }
}
