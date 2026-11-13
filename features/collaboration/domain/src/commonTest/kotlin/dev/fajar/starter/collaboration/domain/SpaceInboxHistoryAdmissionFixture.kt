package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.PublishCollaborationSpaceInboxHistory
import dev.fajar.starter.common.result.*
import kotlin.test.*
import kotlinx.coroutines.flow.Flow

internal class SpaceInboxHistoryAdmissionFixture(
    legacy: Boolean = false,
    val base: SpaceEditInboxAdmissionFixture = SpaceEditInboxAdmissionFixture(legacy),
) {
    val access = base.access
    val id = base.conversationId
    val createdAt = base.thread.room.binding.conversation.createdAt.toEpochMilliseconds()
    var revision = "source:1"
    val originalByEvent = linkedMapOf(base.original.primaryEventId to base.original)
    val operations =
        linkedMapOf<CollaborationHistoryEditOperation, List<CollaborationEditEvidence>>()
    val activityBySource =
        linkedMapOf<CollaborationSpaceInboxSource, CollaborationSpaceInboxActivity>()
    var current = CollaborationSpaceInboxConversation(null, null, null, emptyList())
    var before: suspend (String) -> Unit = {}
    val boundaries = mutableListOf<String>()
    val snapshots = mutableListOf<List<CollaborationHistoryEditOperation>>()
    val references = mutableListOf<List<String>>()
    val anchorQueries = mutableListOf<List<CollaborationSpaceInboxSource>>()
    val publications = mutableListOf<CollaborationSpaceInboxPublication>()
    var commits = 0
    var noops = 0
    var writeConflicts = 0
    var lostResponse: Failure? = null
    var snapshotFailure: Failure? = null
    var rawSnapshot: CollaborationSpaceHistorySnapshot? = null
    var completionFailure: Failure? = null
    val groupCompletions = linkedMapOf<String, CollaborationSpaceMessageCompletion>()
    val originalCompletions = linkedMapOf<String, CollaborationMessageCompletion>()
    val failure = Failure(FailureKind.Storage, "Fixture storage failure")

    init {
        base.before = { boundary(it) }
    }

    suspend fun boundary(name: String) {
        boundaries += name
        before(name)
    }

    fun retain(activity: CollaborationSpaceInboxActivity) {
        activityBySource[activity.source] = activity
        val version = CollaborationSpaceInboxVersion(base.generation, 7, activity.position.ordinal)
        current =
            CollaborationSpaceInboxConversation(
                version,
                collaborationSpaceInboxEntry(
                    id,
                    createdAt,
                    null,
                    activity.takeIf { it.preview != null },
                ),
                null,
                listOfNotNull(activity.takeIf { it.preview != null }),
            )
    }

    suspend fun snapshot(
        ids: List<String>,
        requested: List<CollaborationHistoryEditOperation>,
    ): AppResult<CollaborationSpaceHistorySnapshot?> {
        references += ids.toList()
        snapshots += requested.toList()
        boundary(if (requested.isEmpty()) "history" else "operations")
        snapshotFailure?.let {
            return AppResult.Failed(it)
        }
        rawSnapshot?.let {
            return AppResult.Success(it)
        }
        return AppResult.Success(
            CollaborationSpaceHistorySnapshot(
                revision,
                ids.map { CollaborationSpaceHistorySnapshotEvent(it, originalByEvent[it]) },
                requested.map {
                    CollaborationHistoryEditOperationEvidence(it, operations[it].orEmpty())
                },
            )
        )
    }

    suspend fun publish(
        sourceRevision: String,
        publication: CollaborationSpaceInboxPublication,
    ): AppResult<Boolean> {
        publications += publication
        boundary("publish")
        if (writeConflicts > 0) {
            writeConflicts--
            return AppResult.Success(false)
        }
        if (sourceRevision != revision || publication.expected != current.version)
            return AppResult.Success(false)
        assertNull(publication.draft)
        assertNull(publication.repair)
        val unchanged =
            publication.entry == current.entry &&
                publication.activities.all { activityBySource[it.source] == it }
        if (unchanged) noops++
        else {
            commits++
            publication.activities.forEach { activityBySource[it.source] = it }
            val version =
                CollaborationSpaceInboxVersion(
                    base.generation,
                    (current.version?.revision ?: -1) + 1,
                    publication.lastOrdinal,
                )
            val candidates =
                activityBySource.values
                    .filter { it.preview != null }
                    .sortedWith { a, b ->
                        -compareCollaborationSpaceInboxPositions(a.position, b.position)
                    }
            current =
                CollaborationSpaceInboxConversation(
                    version,
                    publication.entry,
                    current.draft,
                    candidates,
                )
        }
        lostResponse?.let {
            return AppResult.Failed(it)
        }
        return AppResult.Success(true)
    }

    val history =
        object : CollaborationSpaceHistorySnapshotRepository {
            override suspend fun snapshot(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
                roomId: String,
                eventIds: List<String>,
                editOperations: List<CollaborationHistoryEditOperation>,
            ): AppResult<CollaborationSpaceHistorySnapshot?> {
                assertIs<CollaborationSpaceHistoryLocation.Space>(base.location)
                assertEquals(this@SpaceInboxHistoryAdmissionFixture.access, access)
                assertEquals(id, conversationId)
                assertEquals(base.location.roomId, roomId)
                return this@SpaceInboxHistoryAdmissionFixture.snapshot(eventIds, editOperations)
            }

            override suspend fun publishInbox(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
                roomId: String,
                sourceRevision: String,
                publication: CollaborationSpaceInboxPublication,
            ): AppResult<Boolean> {
                assertIs<CollaborationSpaceHistoryLocation.Space>(base.location)
                assertEquals(this@SpaceInboxHistoryAdmissionFixture.access, access)
                assertEquals(id, conversationId)
                assertEquals(base.location.roomId, roomId)
                return this@SpaceInboxHistoryAdmissionFixture.publish(sourceRevision, publication)
            }
        }

    val companyHistory =
        object : CollaborationCompanyHistorySnapshotRepository {
            override suspend fun snapshot(
                source: CollaborationSpaceHistoryLocation.Original,
                eventIds: List<String>,
                editOperations: List<CollaborationHistoryEditOperation>,
            ): AppResult<CollaborationSpaceHistorySnapshot?> {
                assertEquals(base.location, source)
                return this@SpaceInboxHistoryAdmissionFixture.snapshot(eventIds, editOperations)
            }

            override suspend fun publishInbox(
                access: CollaborationSpaceLocalAccess,
                source: CollaborationSpaceHistoryLocation.Original,
                sourceRevision: String,
                publication: CollaborationSpaceInboxPublication,
            ): AppResult<Boolean> {
                assertEquals(this@SpaceInboxHistoryAdmissionFixture.access, access)
                assertEquals(base.location, source)
                return this@SpaceInboxHistoryAdmissionFixture.publish(sourceRevision, publication)
            }
        }

    val inbox =
        object : CollaborationSpaceInboxRepository {
            override fun observePage(
                access: CollaborationSpaceLocalAccess,
                after: String?,
                limit: Int,
            ): Flow<AppResult<CollaborationSpaceInboxPage>> = error("No background observer")

            override suspend fun conversation(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
                candidateLimit: Int,
            ): AppResult<CollaborationSpaceInboxConversation> {
                assertEquals(this@SpaceInboxHistoryAdmissionFixture.access, access)
                assertEquals(id, conversationId)
                assertTrue(candidateLimit in 2..17)
                boundary("index")
                return AppResult.Success(
                    current.copy(candidates = current.candidates.take(candidateLimit))
                )
            }

            override suspend fun activities(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
                sources: List<CollaborationSpaceInboxSource>,
            ): AppResult<CollaborationSpaceInboxActivities> {
                assertEquals(this@SpaceInboxHistoryAdmissionFixture.access, access)
                assertEquals(id, conversationId)
                assertTrue(sources.size in 1..32 && sources.distinct() == sources)
                anchorQueries += sources.toList()
                boundary("anchors")
                return AppResult.Success(
                    CollaborationSpaceInboxActivities(
                        current.version,
                        sources.mapNotNull { activityBySource[it] },
                    )
                )
            }

            override suspend fun seed(
                access: CollaborationSpaceLocalAccess,
                publication: CollaborationSpaceInboxPublication,
                repair: CollaborationSpaceInboxRepairPublication?,
            ): AppResult<CollaborationSpaceInboxSeedResult> =
                error("History never seeds another command")

            override suspend fun publish(
                access: CollaborationSpaceLocalAccess,
                publication: CollaborationSpaceInboxPublication,
            ): AppResult<Boolean> = error("Publication requires the checked source")
        }

    val completions =
        object : CollaborationSpaceMessageCompletionRepository {
            override suspend fun readCompletion(
                access: CollaborationSpaceLocalAccess,
                id: String,
            ): AppResult<CollaborationSpaceMessageCompletion?> {
                assertEquals(this@SpaceInboxHistoryAdmissionFixture.access, access)
                boundary("group-completion")
                return completionFailure?.let { AppResult.Failed(it) }
                    ?: AppResult.Success(groupCompletions[id])
            }

            override suspend fun archiveCompletion(
                access: CollaborationSpaceLocalAccess,
                completion: CollaborationSpaceMessageCompletion,
                repair: CollaborationSpaceInboxRepairPublication,
            ): AppResult<Boolean> = error("Publication never retires a command")
        }

    val companyCompletions =
        object : CollaborationMessageCompletionRepository {
            override suspend fun readCompletion(
                access: CollaborationLocalAccess,
                id: String,
            ): AppResult<CollaborationMessageCompletion?> {
                assertEquals(
                    (base.location as CollaborationSpaceHistoryLocation.Original).access,
                    access,
                )
                boundary("company-completion")
                return completionFailure?.let { AppResult.Failed(it) }
                    ?: AppResult.Success(originalCompletions[id])
            }

            override suspend fun archiveCompletion(
                access: CollaborationLocalAccess,
                completion: CollaborationMessageCompletion,
            ): AppResult<Boolean> = error("Publication never retires a command")
        }

    fun usecase() =
        PublishCollaborationSpaceInboxHistory(
            history,
            companyHistory,
            base.messages,
            base.companyMessages,
            completions,
            companyCompletions,
            inbox,
            base.thread.bindings,
            base.thread.directory,
            base.thread.sessions,
            base.thread.native.server,
        )

    suspend fun result(ids: List<String> = listOf(base.original.primaryEventId)) =
        usecase()(access, id, ids)
}
