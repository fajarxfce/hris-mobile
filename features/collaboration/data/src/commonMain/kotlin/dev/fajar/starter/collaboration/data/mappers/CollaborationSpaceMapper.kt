package dev.fajar.starter.collaboration.data.mappers

import dev.fajar.starter.collaboration.data.dto.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import kotlin.time.Instant

fun CollaborationSpaceObservationDto.toObservation(
    parent: CollaborationNativeParent
): CollaborationSpaceObservation {
    require(collaborationId(accountId) == parent.accountId)
    require(collaborationId(nativeSessionId) == parent.nativeSessionId)
    return CollaborationSpaceObservation(
            CollaborationSpaceOwner(parent, collaborationId(spaceId)),
            nativeSessionVersion,
            credentialVersion,
            Instant.fromEpochMilliseconds(collaborationTime(admittedAt)),
            Instant.fromEpochMilliseconds(collaborationTime(expiresAt)),
        )
        .also { require(validCollaborationSpaceObservation(it, parent)) }
}

fun CollaborationSpaceContactPageDto.toPage(
    owner: CollaborationSpaceOwner,
    after: String?,
    limit: Int,
): CollaborationSpaceContactPage {
    require(items.size <= limit)
    return CollaborationSpaceContactPage(
            owner,
            items.map { item ->
                require(item.companies.size in 1..8)
                CollaborationSpaceContact(
                    collaborationId(item.accountId),
                    item.displayName,
                    item.companies.map {
                        CollaborationSpaceContactCompany(collaborationId(it.id), it.name)
                    },
                    item.hasMoreCompanies,
                )
            },
            nextCursor?.let(::collaborationId),
        )
        .also { require(validCollaborationSpaceContactPage(it, owner, after, limit)) }
}
