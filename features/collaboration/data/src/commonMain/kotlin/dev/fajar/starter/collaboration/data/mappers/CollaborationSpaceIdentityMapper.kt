package dev.fajar.starter.collaboration.data.mappers

import dev.fajar.starter.collaboration.data.dto.CollaborationSpaceIdentityDto
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.validCollaborationSpaceIdentity
import io.ktor.http.*
import kotlin.time.Instant

fun CollaborationSpaceIdentityDto.toSpaceIdentity(
    owner: CollaborationSpaceOwner
): CollaborationSpaceIdentity {
    val value =
        CollaborationSpaceIdentity(
            collaborationId(spaceId),
            collaborationId(accountId),
            serverName,
            matrixUserId,
            CollaborationSpaceIdentityOrigin.valueOf(origin),
            legacyCompanyId?.let(::collaborationId),
            version,
            Instant.parse(createdAt),
        )
    require(validCollaborationSpaceIdentity(owner, value))
    // Validate syntax only; no request or SDK endpoint is ever constructed from this metadata.
    val address = Url("https://$serverName")
    require(address.host.isNotBlank() && address.port in 1..65535)
    require(address.user == null && address.password == null)
    require(
        address.encodedPath in setOf("", "/") &&
            address.parameters.isEmpty() &&
            address.fragment.isEmpty()
    )
    return value
}
