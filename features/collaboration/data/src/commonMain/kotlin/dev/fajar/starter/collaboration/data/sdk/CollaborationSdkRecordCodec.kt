package dev.fajar.starter.collaboration.data.sdk

import dev.fajar.starter.collaboration.data.dto.CollaborationGatewayDto
import dev.fajar.starter.collaboration.data.mappers.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.collaborationGatewayMatchesCommand
import kotlin.io.encoding.Base64
import kotlin.time.Instant
import kotlinx.serialization.json.Json

internal class CollaborationSdkRecordCodec(private val scope: CollaborationSdkScope) {
    fun decode(value: String): CollaborationSdkRecord {
        require(value.encodeToByteArray().size in 1..16_384)
        return Json.decodeFromString<CollaborationSdkRecord>(value).also(::validate)
    }

    fun encode(value: CollaborationSdkRecord): String {
        validate(value)
        return Json.encodeToString(value).also { require(it.encodeToByteArray().size <= 16_384) }
    }

    fun create(
        command: CollaborationSetupCommand,
        gateway: CollaborationGateway,
        passphrase: String,
    ): CollaborationSdkRecord {
        require(collaborationGatewayMatchesCommand(scope.owner, command, gateway))
        require(gateway.deviceId == scope.deviceId)
        val owner = scope.owner
        return CollaborationSdkRecord(
                1,
                scope.storeId,
                owner.audience,
                owner.accountId,
                owner.companyId,
                owner.nativeSessionId,
                scope.deviceId,
                scope.homeserverUrl,
                gateway.matrixUserId,
                gateway.matrixDeviceId,
                passphrase,
                command.id,
                gateway.sessionId,
                gateway.version,
                command.clientBuild,
                Instant.fromEpochMilliseconds(gateway.accessExpiresAtEpochMillis).toString(),
                Instant.fromEpochMilliseconds(gateway.expiresAtEpochMillis).toString(),
                gateway.accessToken,
                gateway.refreshToken,
                false,
                0,
            )
            .also(::validate)
    }

    fun withTokens(
        previous: CollaborationSdkRecord,
        accessToken: String,
        refreshToken: String,
    ): CollaborationSdkRecord {
        validate(previous)
        if (previous.accessToken == accessToken && previous.refreshToken == refreshToken)
            return previous
        return previous
            .copy(
                accessToken = accessToken,
                refreshToken = refreshToken,
                credentialRevision = previous.credentialRevision + 1,
            )
            .also(::validate)
    }

    fun initialized(previous: CollaborationSdkRecord): CollaborationSdkRecord {
        validate(previous)
        if (previous.initialized) return previous
        return previous
            .copy(initialized = true, credentialRevision = previous.credentialRevision + 1)
            .also(::validate)
    }

    private fun validate(record: CollaborationSdkRecord) {
        val owner = scope.owner
        require(record.schema == 1 && record.storeId == scope.storeId)
        require(
            record.audience == owner.audience &&
                record.accountId == owner.accountId &&
                record.companyId == owner.companyId &&
                record.nativeSessionId == owner.nativeSessionId &&
                record.deviceId == scope.deviceId &&
                record.homeserverUrl == scope.homeserverUrl
        )
        require(record.credentialRevision in 0..9_007_199_254_740_991L)
        require(record.bootstrapClientBuild in 1..Int.MAX_VALUE)
        collaborationId(record.bootstrapOperationId)
        require(record.bootstrapGatewayVersion in 0..999_999)
        val key = Base64.decode(record.passphrase)
        try {
            require(key.size == 32 && Base64.encode(key) == record.passphrase)
        } finally {
            key.fill(0)
        }
        CollaborationGatewayDto(
                record.bootstrapGatewayId,
                record.bootstrapGatewayVersion,
                record.matrixUserId,
                record.matrixDeviceId,
                record.accessToken,
                record.refreshToken,
                record.initialAccessExpiresAt,
                record.expiresAt,
            )
            .toGateway(
                owner,
                CollaborationBootstrap(
                    record.bootstrapOperationId,
                    scope.deviceId,
                    0,
                    (record.bootstrapGatewayVersion - 1).takeIf { it >= 0 },
                ),
            )
    }
}
