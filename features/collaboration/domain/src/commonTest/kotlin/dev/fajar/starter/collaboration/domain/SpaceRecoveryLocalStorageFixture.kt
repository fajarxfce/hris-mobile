package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.common.result.*
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlinx.coroutines.flow.*

/** Local storage boundaries for worker tests; all remote acquisition and mutation are forbidden. */
internal class SpaceRecoveryLocalStorageFixture(val owner: CollaborationSpaceOwner) {
    val access = owner.localDirectoryAccess()
    val records = linkedMapOf<String, CollaborationSpaceMessageRecord>()
    val conversations = linkedMapOf<String, CollaborationSpaceConversationMetadata>()
    var gate: AppResult<CollaborationSpaceDirectoryAdmission> =
        AppResult.Success(CollaborationSpaceDirectoryAdmission(null, null))
    var failure: Failure? = null
    var beforeObserve: suspend () -> Unit = {}
    var beforeGet: suspend () -> Unit = {}
    var beforeMetadata: suspend () -> Unit = {}
    var beforeAdmission: suspend () -> Unit = {}
    var observations = 0
    var reads = 0
    var metadataReads = 0
    val messages =
        object : CollaborationSpaceMessageRepository {
            override fun observe(access: CollaborationSpaceLocalAccess, conversationId: String?) =
                flow {
                    assertEquals(this@SpaceRecoveryLocalStorageFixture.access, access)
                    observations++
                    beforeObserve()
                    emit(
                        failure?.let { AppResult.Failed(it) }
                            ?: AppResult.Success(
                                records.values.filter {
                                    conversationId == null ||
                                        it.intent.conversationId == conversationId
                                }
                            )
                    )
                }

            override fun observeRecord(access: CollaborationSpaceLocalAccess, id: String) = flow {
                emit(get(access, id))
            }

            override suspend fun get(
                access: CollaborationSpaceLocalAccess,
                id: String,
            ): AppResult<CollaborationSpaceMessageRecord?> {
                assertEquals(this@SpaceRecoveryLocalStorageFixture.access, access)
                reads++
                beforeGet()
                return failure?.let { AppResult.Failed(it) } ?: AppResult.Success(records[id])
            }

            override suspend fun insert(
                access: CollaborationSpaceLocalAccess,
                intent: CollaborationSpaceMessageIntent,
            ): AppResult<CollaborationMessageInsertion> = error("No command authoring")

            override suspend fun update(
                access: CollaborationSpaceLocalAccess,
                expected: CollaborationSpaceMessageRecord,
                state: CollaborationMessageState,
            ): AppResult<Boolean> = error("Own staging/delivery callbacks")
        }
    val metadata =
        object : CollaborationSpaceConversationMetadataRepository {
            override suspend fun getById(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
            ): AppResult<CollaborationSpaceConversationMetadata?> {
                assertEquals(this@SpaceRecoveryLocalStorageFixture.access, access)
                metadataReads++
                beforeMetadata()
                return failure?.let { AppResult.Failed(it) }
                    ?: AppResult.Success(conversations[conversationId])
            }

            override suspend fun findByPeer(
                access: CollaborationSpaceLocalAccess,
                peerAccountId: String,
            ): AppResult<CollaborationSpaceConversationMetadata?> =
                error("Exact canonical lookup only")

            override suspend fun retain(
                access: CollaborationSpaceLocalAccess,
                conversation: CollaborationSpaceConversation,
            ): AppResult<Boolean> = error("No metadata write")
        }
    val directory =
        object : CollaborationSpaceDirectoryRepository {
            override suspend fun admission(
                access: CollaborationSpaceLocalAccess
            ): AppResult<CollaborationSpaceDirectoryAdmission> {
                assertEquals(this@SpaceRecoveryLocalStorageFixture.access, access)
                beforeAdmission()
                return gate
            }

            override fun observeAdmission(access: CollaborationSpaceLocalAccess) = flow {
                emit(admission(access))
            }

            override suspend fun checkpoint(
                access: CollaborationSpaceLocalAccess
            ): AppResult<CollaborationSpaceDirectoryCheckpoint?> = error("No directory acquisition")

            override fun observe(
                access: CollaborationSpaceLocalAccess
            ): Flow<AppResult<CollaborationSpaceDirectorySnapshot>> =
                error("No directory acquisition")

            override suspend fun begin(
                access: CollaborationSpaceLocalAccess,
                expected: CollaborationSpaceDirectoryCheckpoint?,
                generation: String,
                startedAt: Instant,
            ): AppResult<Boolean> = error("No directory write")

            override suspend fun append(
                access: CollaborationSpaceLocalAccess,
                expected: CollaborationSpaceDirectoryCheckpoint,
                page: CollaborationSpaceContactPage,
                acquiredAt: Instant,
            ): AppResult<Boolean> = error("No directory write")

            override suspend fun restrict(
                access: CollaborationSpaceLocalAccess,
                failure: Failure,
            ): AppResult<Boolean> = error("No directory write")

            override suspend fun admit(
                access: CollaborationSpaceLocalAccess,
                expectedRevision: String?,
            ): AppResult<Boolean> = error("No directory write")
        }
}
