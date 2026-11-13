package dev.fajar.starter.collaboration.presentation.spacethread

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.AppResult
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.*

/** Exact unresolved room facts; every unused native/acquisition method fails immediately. */
internal class SpaceChatThreadContentFixture(private val f: SpaceChatThreadFixture) {
    var bindingReaders = 0
    val bindings =
        object : CollaborationSpaceRoomBindingRepository {
            override fun observeByConversation(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
            ): Flow<AppResult<CollaborationSpaceRoomBinding?>> = flow {
                assertEquals(f.access, access)
                assertEquals(f.conversationId, conversationId)
                bindingReaders++
                try {
                    emit(AppResult.Success(null))
                    kotlinx.coroutines.awaitCancellation()
                } finally {
                    bindingReaders--
                }
            }

            override suspend fun getByConversation(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
            ): AppResult<CollaborationSpaceRoomBinding?> = error("No one-shot binding acquisition")

            override suspend fun findByRoom(
                access: CollaborationSpaceLocalAccess,
                serverName: String,
                roomId: String,
            ): AppResult<CollaborationSpaceRoomBinding?> = error("No provider lookup")

            override suspend fun retain(
                access: CollaborationSpaceLocalAccess,
                binding: CollaborationSpaceRoomBinding,
            ): AppResult<Boolean> = error("No binding mutation")
        }
    val metadata =
        object : CollaborationSpaceConversationMetadataRepository {
            override suspend fun getById(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
            ): AppResult<CollaborationSpaceConversationMetadata?> {
                assertEquals(f.access, access)
                assertEquals(f.conversationId, conversationId)
                f.beforeMetadata()
                return AppResult.Success(f.metadata)
            }

            override suspend fun findByPeer(
                access: CollaborationSpaceLocalAccess,
                peerAccountId: String,
            ): AppResult<CollaborationSpaceConversationMetadata?> = error("No peer acquisition")

            override suspend fun retain(
                access: CollaborationSpaceLocalAccess,
                conversation: CollaborationSpaceConversation,
            ): AppResult<Boolean> = error("No metadata mutation")
        }
    val commands =
        object : CollaborationSpaceMessageRepository {
            override fun observe(access: CollaborationSpaceLocalAccess, conversationId: String?) =
                f.messages.map { AppResult.Success(it) }

            override fun observeRecord(
                access: CollaborationSpaceLocalAccess,
                id: String,
            ): Flow<AppResult<CollaborationSpaceMessageRecord?>> = flow {
                assertEquals(f.access, access)
                f.recordReaders++
                try {
                    f.beforeRecord()
                    emitAll(
                        combine(f.messages, f.archived) { active, terminal ->
                            f.recordFailure?.let { AppResult.Failed(it) }
                                ?: AppResult.Success(
                                    (active + terminal).firstOrNull { it.intent.id == id }
                                )
                        }
                    )
                } finally {
                    f.recordReaders--
                }
            }

            override suspend fun get(access: CollaborationSpaceLocalAccess, id: String) =
                AppResult.Success(f.messages.value.firstOrNull { it.intent.id == id })

            override suspend fun insert(
                access: CollaborationSpaceLocalAccess,
                intent: CollaborationSpaceMessageIntent,
            ): AppResult<CollaborationMessageInsertion> =
                error("Composer handoff does not run delivery")

            override suspend fun update(
                access: CollaborationSpaceLocalAccess,
                expected: CollaborationSpaceMessageRecord,
                state: CollaborationMessageState,
            ): AppResult<Boolean> = error("No worker state mutation")
        }
    val registrations =
        object : CollaborationSpaceRegistrationCommandRepository {
            override fun observeAll(
                access: CollaborationSpaceLocalAccess
            ): Flow<AppResult<List<CollaborationSpaceRegistrationRecord>>> =
                flowOf(AppResult.Success(emptyList()))

            override fun observe(
                access: CollaborationSpaceLocalAccess,
                nativeSessionId: String,
            ): Flow<AppResult<CollaborationSpaceRegistrationRecord?>> =
                error("No native-parent selection")

            override suspend fun current(
                access: CollaborationSpaceLocalAccess,
                nativeSessionId: String,
            ): AppResult<CollaborationSpaceRegistrationRecord?> =
                error("No native-parent selection")

            override suspend fun insert(
                access: CollaborationSpaceLocalAccess,
                command: CollaborationSpaceRegistrationCommand,
            ): AppResult<CollaborationSetupInsertion> = error("No registration mutation")

            override suspend fun update(
                access: CollaborationSpaceLocalAccess,
                expected: CollaborationSpaceRegistrationRecord,
                outcome: CollaborationSpaceRegistrationOutcome,
                attempts: Int,
            ): AppResult<Boolean> = error("No registration mutation")
        }
    val history =
        object : CollaborationSpaceHistoryRepository {
            override fun observe(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
                roomId: String?,
                after: String?,
                limit: Int,
                before: String?,
                overlap: Int,
            ): Flow<AppResult<CollaborationHistoryPage>> =
                error("Unresolved room cannot invent a history partition")

            override suspend fun getEventFact(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
                roomId: String,
                eventId: String,
            ): AppResult<CollaborationHistoryEventFact?> = error("No original event read")

            override suspend fun getByEvent(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
                roomId: String,
                eventId: String,
            ): AppResult<CollaborationHistoryMessage?> = error("No provider event read")

            override suspend fun getByLogicalId(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
                roomId: String,
                sender: String,
                logicalId: String,
            ): AppResult<CollaborationHistoryMessage?> = error("No provider event read")

            override suspend fun merge(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
                roomId: String,
                changes: List<CollaborationHistoryChange>,
            ): AppResult<Boolean> = error("No history mutation")
        }
}
