package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.datasources.CollaborationRecoveryRemoteSource
import dev.fajar.starter.collaboration.data.mappers.collaborationId
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.validCollaborationDevicePage
import dev.fajar.starter.collaboration.domain.repositories.CollaborationRecoveryRepository
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.network.safeApiCall
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import org.koin.core.annotation.Single

@Single
class RemoteCollaborationRecoveryRepository(
    private val remote: CollaborationRecoveryRemoteSource,
    private val server: IdentityServer,
) : CollaborationRecoveryRepository {
    override suspend fun devices(
        parent: CollaborationNativeParent,
        afterCompanyId: String?,
        limit: Int,
    ) = safeApiCall {
        validate(parent)
        require(limit in 1..32)
        afterCompanyId?.let(::collaborationId)
        val raw = remote.devices(parent.sessionId, afterCompanyId, limit)
        require(raw.accountId == parent.accountId && raw.nativeSessionId == parent.nativeSessionId)
        require(raw.devices.size <= limit)
        val page =
            CollaborationDevicePage(
                parent,
                raw.devices.map {
                    CollaborationDeviceReference(
                        collaborationId(it.companyId),
                        collaborationId(it.deviceId),
                        it.version,
                        CollaborationDeviceState.valueOf(it.state),
                        CollaborationNativePlatform.valueOf(it.platform),
                    )
                },
                raw.nextAfterCompanyId?.let(::collaborationId),
            )
        require(validCollaborationDevicePage(page, parent, afterCompanyId, limit))
        page
    }

    override suspend fun admission(owner: CollaborationOwner) = safeApiCall {
        validate(
            CollaborationNativeParent(
                owner.sessionId,
                owner.audience,
                owner.accountId,
                owner.nativeSessionId,
            )
        )
        collaborationId(owner.companyId)
        val raw = remote.admission(owner.sessionId, owner.companyId)
        require(
            raw.accountId == owner.accountId &&
                raw.nativeSessionId == owner.nativeSessionId &&
                raw.companyId == owner.companyId
        )
        require(
            raw.deviceVersion >= 0 && raw.nativeSessionVersion >= 0 && raw.credentialVersion >= 0
        )
        val admittedAt = Instant.parse(raw.admittedAt)
        val expiresAt = Instant.parse(raw.expiresAt)
        require(
            admittedAt.toEpochMilliseconds() >= 0 &&
                expiresAt > admittedAt &&
                expiresAt - admittedAt <= 60.seconds
        )
        CollaborationAdmissionObservation(
            owner,
            collaborationId(raw.deviceId),
            raw.deviceVersion,
            raw.nativeSessionVersion,
            raw.credentialVersion,
            admittedAt,
            expiresAt,
        )
    }

    private fun validate(parent: CollaborationNativeParent) {
        require(parent.audience == server.audience)
        require(parent.sessionId.length in 1..128 && parent.sessionId.all { it.code in 33..126 })
        collaborationId(parent.accountId)
        collaborationId(parent.nativeSessionId)
    }
}
