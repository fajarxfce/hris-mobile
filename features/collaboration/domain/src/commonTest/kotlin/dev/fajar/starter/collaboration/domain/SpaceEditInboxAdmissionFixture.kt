package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.QueueCollaborationSpaceEdit
import dev.fajar.starter.common.result.*
import dev.fajar.starter.sync.domain.repositories.SyncScheduleRepository
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.*

internal class SpaceEditInboxAdmissionFixture(legacy: Boolean = false) {
    val thread = SpaceThreadHistoryFixture(legacy)
    val access = thread.access
    val conversationId = thread.id
    var now = thread.native.setup.now
    val clock =
        object : Clock {
            override fun now() = this@SpaceEditInboxAdmissionFixture.now
        }
    val original =
        thread
            .page()
            .messages
            .single()
            .copy(
                sender = thread.participants.selected.matrixUserId,
                timestamp = (now - 1000.milliseconds).toEpochMilliseconds(),
                firstAcceptedAt = (now - 1000.milliseconds).toEpochMilliseconds(),
            )
    val target =
        CollaborationEditTarget(
            thread.room.roomId,
            original.primaryEventId,
            original.logicalId,
            original.sender,
            original.contentHash,
            original.timestamp,
            original.firstAcceptedAt!!,
            original.replyToEventId,
            original.forwarded,
        )
    val reviewed = CollaborationEditCandidate(target, original.text!!)
    val location =
        (resolveCollaborationSpaceHistoryLocation(access, conversationId, thread.room.binding)
                as AppResult.Success)
            .value
    val source =
        CollaborationSpaceInboxSource.History(
            (location as? CollaborationSpaceHistoryLocation.Original)?.access?.companyId,
            location.conversationId,
            location.roomId,
            original.sender,
            original.primaryEventId,
        )
    var history = CollaborationSpaceEditSourceSnapshot("source:1", original, reviewed)
    val generation = "a9000000-0000-4000-8000-000000000001"
    var activity =
        CollaborationSpaceInboxActivity(
            source,
            CollaborationSpaceInboxPosition(original.timestamp, 1),
            CollaborationSpaceInboxPreview(
                CollaborationSpaceInboxContent.TEXT,
                original.text,
                original.timestamp,
                null,
            ),
        )
    var current =
        CollaborationSpaceInboxConversation(
            CollaborationSpaceInboxVersion(generation, 0, 1),
            collaborationSpaceInboxEntry(conversationId, original.timestamp, null, activity),
            null,
            listOf(activity),
        )
    val group = linkedMapOf<String, CollaborationSpaceMessageRecord>()
    val company = linkedMapOf<String, CollaborationMessageRecord>()
    val writes = mutableListOf<CollaborationSpaceMessageIntent>()
    val schedules = mutableListOf<String>()
    val boundaries = mutableListOf<String>()
    var before: suspend (String) -> Unit = {}
    var afterWrite: suspend () -> Unit = {}
    var writeFailure: Failure? = null
    var replyFailure: Failure? = null
    var readFailure: Failure? = null
    var conflicts = 0
    var changingSource = false
    val failure = Failure(FailureKind.Storage, "Storage fixture failure")

    suspend fun boundary(name: String) {
        boundaries += name
        before(name)
    }

    val messages =
        object : CollaborationSpaceMessageRepository by thread.commands.spaces {
            override suspend fun get(
                access: CollaborationSpaceLocalAccess,
                id: String,
            ): AppResult<CollaborationSpaceMessageRecord?> {
                assertEquals(this@SpaceEditInboxAdmissionFixture.access, access)
                boundary("command")
                return readFailure?.let { AppResult.Failed(it) } ?: AppResult.Success(group[id])
            }

            override fun observe(access: CollaborationSpaceLocalAccess, conversationId: String?) =
                flow {
                    assertEquals(this@SpaceEditInboxAdmissionFixture.access, access)
                    assertEquals(this@SpaceEditInboxAdmissionFixture.conversationId, conversationId)
                    boundary("group-inventory")
                    emit(AppResult.Success(group.values.toList()))
                }
        }
    val companyMessages =
        object : CollaborationMessageRepository by thread.commands.originals {
            override suspend fun get(
                access: CollaborationLocalAccess,
                id: String,
            ): AppResult<CollaborationMessageRecord?> {
                assertEquals(thread.commands.originalAccess(), access)
                boundary("company-command")
                return AppResult.Success(company[id])
            }

            override fun observe(access: CollaborationLocalAccess, conversationId: String?) = flow {
                assertEquals(thread.commands.originalAccess(), access)
                assertEquals(thread.room.position.conversationId, conversationId)
                boundary("company-inventory")
                emit(AppResult.Success(company.values.toList()))
            }
        }
    val index =
        object : CollaborationSpaceInboxRepository {
            override fun observePage(
                access: CollaborationSpaceLocalAccess,
                after: String?,
                limit: Int,
            ): Flow<AppResult<CollaborationSpaceInboxPage>> = error("No background index owner")

            override suspend fun conversation(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
                candidateLimit: Int,
            ): AppResult<CollaborationSpaceInboxConversation> {
                assertEquals(this@SpaceEditInboxAdmissionFixture.access, access)
                assertEquals(this@SpaceEditInboxAdmissionFixture.conversationId, conversationId)
                assertEquals(1, candidateLimit)
                boundary("index")
                return AppResult.Success(current)
            }

            override suspend fun activities(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
                sources: List<CollaborationSpaceInboxSource>,
            ): AppResult<CollaborationSpaceInboxActivities> {
                assertTrue(
                    source in sources && sources.size in 1..2 && sources.distinct() == sources
                )
                boundary("anchor")
                return AppResult.Success(
                    CollaborationSpaceInboxActivities(current.version, listOf(activity))
                )
            }

            override suspend fun seed(
                access: CollaborationSpaceLocalAccess,
                publication: CollaborationSpaceInboxPublication,
                repair: CollaborationSpaceInboxRepairPublication?,
            ): AppResult<CollaborationSpaceInboxSeedResult> = error("No independent seed")

            override suspend fun publish(
                access: CollaborationSpaceLocalAccess,
                publication: CollaborationSpaceInboxPublication,
            ): AppResult<Boolean> = error("Edit requires joint publication")
        }
    val publications =
        object : CollaborationSpaceEditPublicationRepository {
            override suspend fun readEditSource(
                access: CollaborationSpaceLocalAccess,
                source: CollaborationSpaceHistoryLocation,
                eventId: String,
            ): AppResult<CollaborationSpaceEditSourceSnapshot?> {
                assertEquals(this@SpaceEditInboxAdmissionFixture.access, access)
                assertEquals(location, source)
                assertEquals(target.eventId, eventId)
                boundary("history")
                return AppResult.Success(if (changingSource) null else history)
            }

            override suspend fun publishEdit(
                access: CollaborationSpaceLocalAccess,
                intent: CollaborationSpaceMessageIntent,
                source: CollaborationSpaceHistoryLocation,
                sourceRevision: String,
                publication: CollaborationSpaceInboxPublication,
            ): AppResult<CollaborationSpaceEditPublicationResult> {
                assertEquals(this@SpaceEditInboxAdmissionFixture.access, access)
                assertEquals(location, source)
                assertEquals(history.revision, sourceRevision)
                boundary("write")
                writes += intent
                writeFailure?.let {
                    return AppResult.Failed(it)
                }
                if (conflicts > 0) {
                    conflicts--
                    return AppResult.Success(CollaborationSpaceEditPublicationResult.CHANGED)
                }
                assertEquals(current.version, publication.expected)
                assertTrue(validCollaborationSpaceEditPublication(access, intent, publication))
                assertNull(publication.draft)
                group[intent.id] =
                    CollaborationSpaceMessageRecord(intent, 0, CollaborationMessageState.Queued)
                activity = publication.activities.single()
                val previousVersion = assertNotNull(current.version)
                current =
                    current.copy(
                        version = previousVersion.copy(revision = previousVersion.revision + 1),
                        entry = publication.entry,
                        candidates = listOfNotNull(publication.entry.newest),
                    )
                afterWrite()
                return replyFailure?.let { AppResult.Failed(it) }
                    ?: AppResult.Success(CollaborationSpaceEditPublicationResult.STORED)
            }
        }
    val scheduler =
        object : SyncScheduleRepository {
            override suspend fun request(key: String): AppResult<Unit> {
                boundary("schedule")
                schedules += key
                return AppResult.Success(Unit)
            }
        }

    fun request(
        number: Int = 1,
        reviewed: CollaborationEditCandidate = this.reviewed,
        text: String = "Edit $number",
    ) =
        CollaborationSpaceEditRequest(
            "b9000000-0000-4000-8000-${number.toString().padStart(12, '0')}",
            conversationId,
            reviewed,
            text,
        )

    fun usecase() =
        QueueCollaborationSpaceEdit(
            messages,
            companyMessages,
            publications,
            index,
            thread.bindings,
            thread.participants.commands,
            thread.directory,
            thread.sessions,
            thread.native.server,
            scheduler,
            clock,
        )
}
