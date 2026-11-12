package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.*
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.Session
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import dev.fajar.starter.sync.domain.repositories.SyncScheduleRepository
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.*

internal class SpaceMessageUseCaseFixture {
    val room = SpaceRoomBindingPreparationFixture()
    val base = room.base
    val native = room.native
    val access = room.access
    val id = "e1000000-0000-4000-8000-000000000001"
    val text = "A retained local message"
    val identity = MutableStateFlow<AppResult<Session?>>(AppResult.Success(native.session))
    var canonical: CollaborationSpaceConversationMetadata? = room.conversation.localMetadata()
    var stored: CollaborationSpaceMessageRecord? = null
    val facts =
        MutableStateFlow<AppResult<List<CollaborationSpaceMessageRecord>>>(
            AppResult.Success(emptyList())
        )
    val writes = mutableListOf<CollaborationSpaceMessageIntent>()
    val schedules = mutableListOf<String>()
    var reads = 0
    var canonicalReads = 0
    var observations = 0
    var stops = 0
    var beforeRead: suspend () -> Unit = {}
    var beforeCanonical: suspend () -> Unit = {}
    var readFailure: Failure? = null
    var onInsert:
        suspend (CollaborationSpaceMessageIntent) -> AppResult<CollaborationMessageInsertion> =
        {
            stored = CollaborationSpaceMessageRecord(it, 0, CollaborationMessageState.Queued)
            AppResult.Success(CollaborationMessageInsertion.STORED)
        }
    var onSchedule: suspend () -> AppResult<Unit> = { AppResult.Success(Unit) }
    var readFacts: () -> Flow<AppResult<List<CollaborationSpaceMessageRecord>>> = { facts }
    val sessions =
        object : SessionRepository by room.source.local.sessions {
            override suspend fun current() = identity.value

            override fun observe() = identity
        }
    val messages =
        object : CollaborationSpaceMessageRepository {
            override suspend fun get(
                access: CollaborationSpaceLocalAccess,
                id: String,
            ): AppResult<CollaborationSpaceMessageRecord?> {
                assertEquals(this@SpaceMessageUseCaseFixture.id, id)
                reads++
                beforeRead()
                return readFailure?.let { AppResult.Failed(it) } ?: AppResult.Success(stored)
            }

            override suspend fun insert(
                access: CollaborationSpaceLocalAccess,
                intent: CollaborationSpaceMessageIntent,
            ): AppResult<CollaborationMessageInsertion> {
                writes += intent
                return onInsert(intent)
            }

            override fun observe(access: CollaborationSpaceLocalAccess, conversationId: String?) =
                flow {
                    observations++
                    try {
                        emitAll(readFacts())
                    } finally {
                        stops++
                    }
                }

            override fun observeRecord(
                access: CollaborationSpaceLocalAccess,
                id: String,
            ): Flow<AppResult<CollaborationSpaceMessageRecord?>> =
                error("Inventory observation only")

            override suspend fun update(
                access: CollaborationSpaceLocalAccess,
                expected: CollaborationSpaceMessageRecord,
                state: CollaborationMessageState,
            ): AppResult<Boolean> = error("Queueing never stages or delivers")
        }
    val metadata =
        object : CollaborationSpaceConversationMetadataRepository {
            override suspend fun getById(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
            ): AppResult<CollaborationSpaceConversationMetadata?> {
                assertEquals(room.conversation.id, conversationId)
                canonicalReads++
                beforeCanonical()
                return AppResult.Success(canonical)
            }

            override suspend fun findByPeer(
                access: CollaborationSpaceLocalAccess,
                peerAccountId: String,
            ): AppResult<CollaborationSpaceConversationMetadata?> = error("No contact discovery")

            override suspend fun retain(
                access: CollaborationSpaceLocalAccess,
                conversation: CollaborationSpaceConversation,
            ): AppResult<Boolean> = error("No conversation acquisition")
        }
    val scheduler =
        object : SyncScheduleRepository {
            override suspend fun request(key: String): AppResult<Unit> {
                schedules += key
                return onSchedule()
            }
        }

    fun queue() =
        QueueCollaborationSpaceText(
            messages,
            metadata,
            base.directory,
            sessions,
            native.server,
            scheduler,
        )

    fun observe() =
        ObserveCollaborationSpaceMessages(messages, base.directory, sessions, native.server)

    fun record() =
        CollaborationSpaceMessageRecord(
            CollaborationSpaceMessageIntent(
                id,
                access.messageOwner(),
                room.conversation.id,
                native.setup.now,
                text,
            ),
            0,
            CollaborationMessageState.Queued,
        )
}
