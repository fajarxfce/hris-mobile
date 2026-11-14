package dev.fajar.starter.collaboration.data.inbox

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.validCollaborationSpaceInboxHistoryProgress
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class InboxHistoryProgressDto(
    val schema: Int,
    val latest: InboxHistoryTriggerDto,
    val completed: InboxHistoryCompletionDto?,
    val pass: InboxHistoryPassDto?,
) {
    constructor(
        value: CollaborationSpaceInboxHistoryProgress
    ) : this(
        1,
        InboxHistoryTriggerDto(value.latest),
        value.completed?.let {
            InboxHistoryCompletionDto(it.passId, InboxHistoryTriggerDto(it.trigger))
        },
        value.pass?.let { InboxHistoryPassDto(it) },
    )

    fun domain(): CollaborationSpaceInboxHistoryProgress {
        require(schema == 1)
        return CollaborationSpaceInboxHistoryProgress(
                latest.domain(),
                completed?.let {
                    CollaborationSpaceInboxHistoryCompletion(it.passId, it.trigger.domain())
                },
                pass?.domain(),
            )
            .also { require(validCollaborationSpaceInboxHistoryProgress(it)) }
    }
}

@Serializable
internal data class InboxHistoryTriggerDto(
    val historySignal: String?,
    val catalog: InboxHistoryCatalogDto?,
    val completedRepairDemand: String?,
    val destinationGeneration: String?,
) {
    constructor(
        value: CollaborationSpaceInboxHistoryTrigger
    ) : this(
        value.historySignal,
        value.catalog?.let { InboxHistoryCatalogDto(it.generation, it.revision) },
        value.completedRepairDemand,
        value.destinationGeneration,
    )

    fun domain() =
        CollaborationSpaceInboxHistoryTrigger(
            historySignal,
            catalog?.let {
                CollaborationSpaceConversationCatalogVersion(it.generation, it.revision)
            },
            completedRepairDemand,
            destinationGeneration,
        )
}

@Serializable internal data class InboxHistoryCatalogDto(val generation: String, val revision: Long)

@Serializable
internal data class InboxHistoryCompletionDto(
    val passId: String,
    val trigger: InboxHistoryTriggerDto,
)

@Serializable
internal data class InboxHistoryPassDto(
    val id: String,
    val trigger: InboxHistoryTriggerDto,
    val attemptedTrigger: InboxHistoryTriggerDto,
    val traversal: InboxHistoryTraversalDto,
    val catalogDeferred: Boolean,
) {
    constructor(
        value: CollaborationSpaceInboxHistoryPass
    ) : this(
        value.id,
        InboxHistoryTriggerDto(value.trigger),
        InboxHistoryTriggerDto(value.attemptedTrigger),
        when (val work = value.traversal) {
            is CollaborationSpaceInboxHistoryTraversal.Catalog ->
                InboxHistoryTraversalDto.Catalog(
                    work.after,
                    work.batch?.let { InboxHistoryBatchDto(it) },
                )
            is CollaborationSpaceInboxHistoryTraversal.Sources ->
                InboxHistoryTraversalDto.Sources(
                    work.after,
                    work.batch?.let { InboxHistoryBatchDto(it) },
                    work.unfinished,
                    work.advanced,
                )
            CollaborationSpaceInboxHistoryTraversal.Ready -> InboxHistoryTraversalDto.Ready
            CollaborationSpaceInboxHistoryTraversal.Parked -> InboxHistoryTraversalDto.Parked
        },
        value.catalogDeferred,
    )

    fun domain() =
        CollaborationSpaceInboxHistoryPass(
            id,
            trigger.domain(),
            attemptedTrigger.domain(),
            traversal.domain(),
            catalogDeferred,
        )
}

@Serializable
internal data class InboxHistoryBatchDto(val remaining: List<String>, val next: String?) {
    constructor(
        value: CollaborationSpaceInboxHistoryBatch
    ) : this(value.remaining.toList(), value.next)

    fun domain() = CollaborationSpaceInboxHistoryBatch(remaining.toList(), next)
}

@Serializable
internal sealed interface InboxHistoryTraversalDto {
    @Serializable
    @SerialName("catalog")
    data class Catalog(val after: String?, val batch: InboxHistoryBatchDto?) :
        InboxHistoryTraversalDto

    @Serializable
    @SerialName("sources")
    data class Sources(
        val after: String?,
        val batch: InboxHistoryBatchDto?,
        val unfinished: Boolean,
        val advanced: Boolean,
    ) : InboxHistoryTraversalDto

    @Serializable @SerialName("ready") data object Ready : InboxHistoryTraversalDto

    @Serializable @SerialName("parked") data object Parked : InboxHistoryTraversalDto

    fun domain(): CollaborationSpaceInboxHistoryTraversal =
        when (this) {
            is Catalog -> CollaborationSpaceInboxHistoryTraversal.Catalog(after, batch?.domain())
            is Sources ->
                CollaborationSpaceInboxHistoryTraversal.Sources(
                    after,
                    batch?.domain(),
                    unfinished,
                    advanced,
                )
            Ready -> CollaborationSpaceInboxHistoryTraversal.Ready
            Parked -> CollaborationSpaceInboxHistoryTraversal.Parked
        }
}
