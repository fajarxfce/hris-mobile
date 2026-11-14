package dev.fajar.starter.collaboration.data.inbox

import dev.fajar.starter.collaboration.domain.entities.*
import kotlinx.serialization.Serializable

@Serializable
internal data class InboxHistorySourceDto(
    val schema: Int,
    val owner: InboxHistoryOwnerDto,
    val destinationGeneration: String?,
    val passId: String,
    val cursor: InboxHistoryCursorDto?,
    val historyComplete: Boolean,
    val pending: InboxHistoryPendingWindowDto?,
) {
    constructor(
        value: CollaborationSpaceInboxHistorySourceProgress
    ) : this(
        1,
        InboxHistoryOwnerDto(value.owner),
        value.destinationGeneration,
        value.passId,
        value.historyCursor?.let(::InboxHistoryCursorDto),
        value.historyComplete,
        value.pendingWindow?.let(::InboxHistoryPendingWindowDto),
    )

    fun domain(conversationId: String): CollaborationSpaceInboxHistorySourceProgress {
        require(schema == 1)
        val source = owner.domain()
        return CollaborationSpaceInboxHistorySourceProgress(
            conversationId,
            source,
            destinationGeneration,
            passId,
            cursor?.domain(source),
            historyComplete,
            pending?.domain(),
        )
    }
}

@Serializable
internal data class InboxHistoryOwnerDto(
    val audience: String,
    val accountId: String,
    val kind: String,
    val organizationId: String,
    val conversationId: String,
    val roomId: String,
) {
    constructor(
        value: CollaborationHistoryChangeOwner
    ) : this(
        value.audience,
        value.accountId,
        value.kind.name,
        value.organizationId,
        value.conversationId,
        value.roomId,
    )

    fun domain() =
        CollaborationHistoryChangeOwner(
            audience,
            accountId,
            CollaborationHistoryChangeOwnerKind.valueOf(kind),
            organizationId,
            conversationId,
            roomId,
        )
}

@Serializable
internal data class InboxHistoryCursorDto(
    val kind: String,
    val generation: String,
    val revision: Long,
    val afterRevision: Long?,
    val throughRevision: Long?,
    val recordId: String?,
) {
    constructor(
        value: CollaborationHistoryChangeCursor
    ) : this(
        if (value is CollaborationHistoryChangeCursor.After) "after" else "within",
        when (value) {
            is CollaborationHistoryChangeCursor.After -> value.generation
            is CollaborationHistoryChangeCursor.Within -> value.window.generation
        },
        when (value) {
            is CollaborationHistoryChangeCursor.After -> value.revision
            is CollaborationHistoryChangeCursor.Within -> value.revision
        },
        (value as? CollaborationHistoryChangeCursor.Within)?.window?.afterRevision,
        (value as? CollaborationHistoryChangeCursor.Within)?.window?.throughRevision,
        (value as? CollaborationHistoryChangeCursor.Within)?.recordId,
    )

    fun domain(owner: CollaborationHistoryChangeOwner): CollaborationHistoryChangeCursor =
        when (kind) {
            "after" -> {
                require(afterRevision == null && throughRevision == null && recordId == null)
                CollaborationHistoryChangeCursor.After(owner, generation, revision)
            }
            "within" ->
                CollaborationHistoryChangeCursor.Within(
                    CollaborationHistoryChangeWindow(
                        owner,
                        generation,
                        afterRevision,
                        requireNotNull(throughRevision),
                    ),
                    revision,
                    requireNotNull(recordId),
                )
            else -> error("Invalid history cursor kind")
        }
}

@Serializable
internal data class InboxHistoryPendingWindowDto(
    val generation: String,
    val throughRevision: Long,
    val after: String?,
    val complete: Boolean,
) {
    constructor(
        value: CollaborationSpaceInboxHistoryPendingWindow
    ) : this(value.generation, value.throughRevision, value.after, value.complete)

    fun domain() =
        CollaborationSpaceInboxHistoryPendingWindow(generation, throughRevision, after, complete)
}

@Serializable
internal data class InboxHistoryReferenceDto(
    val schema: Int,
    val eventId: String,
    val insertedAtRevision: Long,
)
