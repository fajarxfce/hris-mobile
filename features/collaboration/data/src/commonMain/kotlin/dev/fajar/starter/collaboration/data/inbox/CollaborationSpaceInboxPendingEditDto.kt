package dev.fajar.starter.collaboration.data.inbox

import dev.fajar.starter.collaboration.data.messages.MessageEditTargetDto
import dev.fajar.starter.collaboration.data.messages.toMessageEditDto
import dev.fajar.starter.collaboration.domain.entities.*
import kotlinx.serialization.Serializable

@Serializable
internal data class InboxEditSourceDto(val kind: String, val parts: List<String>) {
    constructor(
        value: CollaborationSpaceInboxEditSource
    ) : this(
        when (value) {
            is CollaborationSpaceInboxEditSource.Group -> "GROUP"
            is CollaborationSpaceInboxEditSource.Company -> "COMPANY"
        },
        when (value) {
            is CollaborationSpaceInboxEditSource.Group -> listOf(value.id)
            is CollaborationSpaceInboxEditSource.Company ->
                listOf(value.companyId, value.conversationId, value.id)
        },
    )

    fun domain(): CollaborationSpaceInboxEditSource =
        when (kind) {
            "GROUP" -> {
                require(parts.size == 1)
                CollaborationSpaceInboxEditSource.Group(parts[0])
            }
            "COMPANY" -> {
                require(parts.size == 3)
                CollaborationSpaceInboxEditSource.Company(parts[0], parts[1], parts[2])
            }
            else -> error("Unknown inbox edit source")
        }

    override fun toString() = "InboxEditSourceDto([redacted])"
}

@Serializable
internal data class InboxPendingEditDto(
    val source: InboxEditSourceDto,
    val target: MessageEditTargetDto,
    val createdAt: Long,
    val publishedAtRevision: Long,
) {
    constructor(
        value: CollaborationSpaceInboxPendingEdit
    ) : this(
        InboxEditSourceDto(value.source),
        value.target.toMessageEditDto(),
        value.createdAt,
        value.publishedAtRevision,
    )

    fun domain() =
        CollaborationSpaceInboxPendingEdit(
            source.domain(),
            target.toDomain(),
            createdAt,
            publishedAtRevision,
        )

    override fun toString() = "InboxPendingEditDto([redacted])"
}
