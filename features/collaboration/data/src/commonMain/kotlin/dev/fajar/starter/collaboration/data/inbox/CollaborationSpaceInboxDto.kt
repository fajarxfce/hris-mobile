package dev.fajar.starter.collaboration.data.inbox

import dev.fajar.starter.collaboration.domain.entities.*
import kotlin.time.Instant
import kotlinx.serialization.Serializable

@Serializable
internal data class InboxPositionDto(val timestamp: Long, val ordinal: Long) {
    constructor(value: CollaborationSpaceInboxPosition) : this(value.timestamp, value.ordinal)

    fun domain() = CollaborationSpaceInboxPosition(timestamp, ordinal)
}

@Serializable
internal data class InboxSourceDto(val kind: String, val parts: List<String>) {
    constructor(
        value: CollaborationSpaceInboxSource
    ) : this(
        when (value) {
            is CollaborationSpaceInboxSource.GroupOriginal -> "group"
            is CollaborationSpaceInboxSource.CompanyOriginal -> "company"
            is CollaborationSpaceInboxSource.History -> "history"
        },
        when (value) {
            is CollaborationSpaceInboxSource.GroupOriginal -> listOf(value.id)
            is CollaborationSpaceInboxSource.CompanyOriginal ->
                listOf(value.companyId, value.conversationId, value.id)
            is CollaborationSpaceInboxSource.History ->
                listOf(
                    value.companyId ?: "",
                    value.conversationId,
                    value.roomId,
                    value.sender,
                    value.eventId,
                )
        },
    )

    fun partsWithKind() = listOf(kind) + parts

    fun domain(): CollaborationSpaceInboxSource =
        when (kind) {
            "group" -> {
                require(parts.size == 1)
                CollaborationSpaceInboxSource.GroupOriginal(parts[0])
            }
            "company" -> {
                require(parts.size == 3)
                CollaborationSpaceInboxSource.CompanyOriginal(parts[0], parts[1], parts[2])
            }
            "history" -> {
                require(parts.size == 5)
                CollaborationSpaceInboxSource.History(
                    parts[0].takeUnless { it.isEmpty() },
                    parts[1],
                    parts[2],
                    parts[3],
                    parts[4],
                )
            }
            else -> error("Unsupported inbox source")
        }
}

@Serializable
internal data class InboxPreviewDto(
    val kind: String,
    val text: String?,
    val timestamp: Long,
    val delivery: String?,
) {
    constructor(
        value: CollaborationSpaceInboxPreview
    ) : this(value.kind.name, value.text, value.timestamp, value.delivery?.name)

    fun domain() =
        CollaborationSpaceInboxPreview(
            CollaborationSpaceInboxContent.valueOf(kind),
            text,
            timestamp,
            delivery?.let(CollaborationMessageDelivery::valueOf),
        )
}

@Serializable
internal data class InboxActivityDto(
    val source: InboxSourceDto,
    val position: InboxPositionDto,
    val preview: InboxPreviewDto?,
    val pendingEdit: InboxPendingEditDto? = null,
) {
    constructor(
        value: CollaborationSpaceInboxActivity
    ) : this(
        InboxSourceDto(value.source),
        InboxPositionDto(value.position),
        value.preview?.let(::InboxPreviewDto),
        value.pendingEdit?.let(::InboxPendingEditDto),
    )

    fun domain() =
        CollaborationSpaceInboxActivity(
            source.domain(),
            position.domain(),
            preview?.domain(),
            pendingEdit?.domain(),
        )
}

@Serializable
internal data class InboxDraftValueDto(
    val conversationId: String,
    val revision: String,
    val text: String,
    val savedAt: String,
    val submissionId: String?,
    val reviewRequired: Boolean,
    val replyToEventId: String?,
) {
    constructor(
        value: CollaborationDraft
    ) : this(
        value.conversationId,
        value.revision,
        value.text,
        value.savedAt.toString(),
        value.submissionId,
        value.reviewRequired,
        value.replyToEventId,
    )

    fun domain() =
        CollaborationDraft(
            conversationId,
            revision,
            text,
            Instant.parse(savedAt),
            submissionId,
            reviewRequired,
            replyToEventId,
        )
}

@Serializable
internal data class InboxDraftDto(val value: InboxDraftValueDto, val position: InboxPositionDto) {
    constructor(
        value: CollaborationSpaceInboxDraft
    ) : this(InboxDraftValueDto(value.value), InboxPositionDto(value.position))

    fun domain() = CollaborationSpaceInboxDraft(value.domain(), position.domain())
}

@Serializable
internal data class InboxEntryDto(
    val createdAt: Long,
    val draft: InboxDraftDto?,
    val newest: InboxActivityDto?,
    val position: InboxPositionDto,
) {
    constructor(
        value: CollaborationSpaceInboxEntry
    ) : this(
        value.createdAt,
        value.draft?.let(::InboxDraftDto),
        value.newest?.let(::InboxActivityDto),
        InboxPositionDto(value.position),
    )

    fun domain(conversationId: String) =
        CollaborationSpaceInboxEntry(
            conversationId,
            createdAt,
            draft?.domain(),
            newest?.domain(),
            position.domain(),
        )
}
