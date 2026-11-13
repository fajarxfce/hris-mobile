package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.ReconcileCollaborationSpaceInboxOriginal
import dev.fajar.starter.common.result.*
import kotlin.test.*

internal class SpaceInboxCommandFixture {
    val source = SpaceComposerUseCaseFixture()
    val access = source.access
    val reference =
        CollaborationSpaceInboxRepairReference(
            collaborationSpaceComposerIntent(access, source.submission),
            0,
        )
    val generation = "c1000000-0000-4000-8000-000000000001"
    val sourceId = CollaborationSpaceInboxSource.GroupOriginal(source.submission.id)
    val publications = mutableListOf<CollaborationSpaceInboxPublication>()
    var beforePublish: suspend () -> Unit = {}
    var afterPublish: suspend () -> Unit = {}
    var publishFailure: Failure? = null
    var publishAttempts = 0
    var conflicts = 0
    var completed = false

    init {
        val initial =
            assertIs<AppResult.Success<CollaborationSpaceInboxPublication>>(
                    collaborationSpaceInboxComposerPublication(
                        source.access,
                        source.base.canonical!!,
                        source.inboxState,
                        CollaborationSpaceInboxActivities(null, emptyList()),
                        source.submission,
                        source.submission.nextDraft,
                        false,
                    )
                )
                .value
        source.inboxState =
            CollaborationSpaceInboxConversation(
                CollaborationSpaceInboxVersion(generation, 0, initial.lastOrdinal),
                initial.entry,
                initial.draft,
                initial.activities,
            )
        initial.activities.forEach { source.inboxActivities[it.source] = it }
        source.base.stored = sent()
    }

    val inbox =
        object : CollaborationSpaceInboxRepository by source.inbox {
            override suspend fun publish(
                access: CollaborationSpaceLocalAccess,
                publication: CollaborationSpaceInboxPublication,
            ): AppResult<Boolean> {
                assertEquals(this@SpaceInboxCommandFixture.access, access)
                publishAttempts++
                beforePublish()
                if (conflicts > 0) {
                    conflicts--
                    return AppResult.Success(false)
                }
                if (publication.expected != source.inboxState.version)
                    return AppResult.Success(false)
                publication.activities.forEach { source.inboxActivities[it.source] = it }
                val visible =
                    source.inboxActivities.values
                        .filter { it.preview != null }
                        .sortedWith { a, b ->
                            compareCollaborationSpaceInboxPositions(b.position, a.position)
                        }
                source.inboxState =
                    source.inboxState.copy(
                        version =
                            CollaborationSpaceInboxVersion(
                                generation,
                                source.inboxState.version!!.revision + 1,
                                publication.lastOrdinal,
                            ),
                        entry = publication.entry,
                        candidates = visible.take(2),
                    )
                assertEquals(
                    CollaborationSpaceInboxRepairChange.Complete(reference),
                    publication.repair,
                )
                completed = true
                publications += publication
                afterPublish()
                return publishFailure?.let { AppResult.Failed(it) } ?: AppResult.Success(true)
            }
        }

    fun queued() =
        CollaborationSpaceMessageRecord(
            collaborationSpaceComposerIntent(access, source.submission),
            0,
            CollaborationMessageState.Queued,
        )

    fun sent() =
        queued()
            .copy(
                revision = 3,
                state =
                    CollaborationMessageState.Sent(
                        CollaborationMessageDevice(id(10), id(11), id(12), "!room:example.test"),
                        "transaction",
                        "${'$'}event",
                        CollaborationMessageOrigin("@fictional:example.test", "a".repeat(64)),
                    ),
            )

    fun id(number: Int) = "c2000000-0000-4000-8000-${number.toString().padStart(12,'0')}"

    fun anchor() = source.inboxActivities.getValue(sourceId)

    fun anchors() = CollaborationSpaceInboxActivities(source.inboxState.version, listOf(anchor()))

    fun usecase() =
        ReconcileCollaborationSpaceInboxOriginal(
            source.base.messages,
            inbox,
            source.base.base.directory,
            source.base.sessions,
            source.base.native.server,
        )

    fun policy(record: CollaborationSpaceMessageRecord = sent()) =
        collaborationSpaceInboxCommandPublication(
            access,
            reference,
            record,
            source.inboxState,
            anchors(),
        )

    fun replaceAnchor(value: CollaborationSpaceInboxActivity) {
        source.inboxActivities[value.source] = value
        val candidates =
            source.inboxActivities.values
                .filter { it.preview != null }
                .sortedWith { a, b ->
                    compareCollaborationSpaceInboxPositions(b.position, a.position)
                }
        val current = source.inboxState
        source.inboxState =
            current.copy(
                entry =
                    collaborationSpaceInboxEntry(
                        source.original.conversationId,
                        current.entry!!.createdAt,
                        current.draft,
                        candidates.firstOrNull(),
                    ),
                candidates = candidates.take(2),
            )
    }
}
