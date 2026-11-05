package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.datasources.CollaborationSummarySyncRemoteSource
import dev.fajar.starter.collaboration.data.dto.CollaborationSummaryFactDto
import dev.fajar.starter.collaboration.data.mappers.*
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSummarySyncRepository
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.network.safeApiCall
import kotlin.time.Instant
import org.koin.core.annotation.Single

@Single
class RemoteCollaborationSummarySyncRepository(
    private val remote: CollaborationSummarySyncRemoteSource,
    private val server: IdentityServer,
) : CollaborationSummarySyncRepository {
    override suspend fun bootstrap(owner: CollaborationOwner, cursor: String?, limit: Int) =
        safeApiCall {
            validate(owner, cursor, limit)
            val dto = remote.bootstrap(owner.sessionId, owner.companyId, cursor, limit)
            require(
                dto.items.size <= limit && (dto.nextPage == null) != (dto.changesCursor == null)
            )
            dto.nextPage?.let(::token)
            dto.changesCursor?.let(::token)
            if (dto.nextPage != null) require(dto.items.size == limit)
            val items = dto.items.map { it.fact(owner) }
            require(items.zipWithNext().all { (a, b) -> a.conversationId < b.conversationId })
            CollaborationSummaryBootstrap(
                items,
                dto.nextPage,
                dto.changesCursor,
                dto.pending,
                time(dto.serverTime),
            )
        }

    override suspend fun changes(owner: CollaborationOwner, cursor: String, limit: Int) =
        safeApiCall {
            validate(owner, cursor, limit)
            val dto = remote.changes(owner.sessionId, owner.companyId, cursor, limit)
            token(dto.nextCursor)
            require(dto.items.size <= limit && (!dto.hasMore || dto.items.size == limit))
            require(
                dto.items.all { it.position > 0 } &&
                    dto.items.zipWithNext().all { (a, b) -> a.position < b.position }
            )
            CollaborationSummaryChanges(
                dto.items.map { CollaborationSummaryChange(it.position, it.fact.fact(owner)) },
                dto.nextCursor,
                dto.hasMore,
                dto.pending,
                time(dto.serverTime),
            )
        }

    private fun CollaborationSummaryFactDto.fact(
        owner: CollaborationOwner
    ): CollaborationSummaryFact {
        collaborationId(conversationId)
        require(revision >= 0)
        val value = summary?.toSummary(owner)
        require(value == null || value.conversation.id == conversationId)
        return CollaborationSummaryFact(conversationId, revision, value)
    }

    private fun validate(owner: CollaborationOwner, cursor: String?, limit: Int) {
        require(
            owner.audience == server.audience &&
                owner.sessionId.length in 1..128 &&
                owner.sessionId.none { it.code < 33 }
        )
        collaborationId(owner.accountId)
        collaborationId(owner.companyId)
        collaborationId(owner.nativeSessionId)
        require(limit in 1..50)
        cursor?.let(::token)
    }

    private fun token(value: String) {
        require(value.length in 1..2048 && value.all { it.code in 33..126 })
    }

    private fun time(value: String) =
        Instant.parse(value).also { require(it.toEpochMilliseconds() >= 0) }
}
