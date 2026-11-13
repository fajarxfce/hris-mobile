package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.RecoverCollaborationSpaceComposer
import dev.fajar.starter.common.result.*
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.*

internal class SpaceComposerRecoveryFixture {
    val composer = SpaceComposerUseCaseFixture()
    val base = composer.base
    val access = base.access
    val retained = composer.retained
    val held = mutableSetOf<String>()
    val records = mutableMapOf<String, CollaborationSpaceMessageRecord>()
    val inserted = mutableListOf<CollaborationSpaceMessageIntent>()
    val retired = mutableListOf<CollaborationComposerSubmission>()
    val positions = mutableListOf<String?>()
    var root: CollaborationSpaceRoot? = CollaborationSpaceRoot(access.spaceId)
    var cursor: CollaborationComposerRecoveryCursor? = null
    var cursorReads = 0
    var cursorWrites = 0
    var outboxReads = 0
    var messageReads = 0
    var inventoryReads = 0
    var inventoryStops = 0
    var readInventory: () -> Flow<AppResult<List<CollaborationSpaceMessageRecord>>> = {
        flowOf(AppResult.Success(records.values.toList()))
    }
    var metadataReads = 0
    var beforeRoot: suspend () -> Unit = {}
    var beforeCursor: suspend () -> Unit = {}
    var beforeOutboxRead: suspend (String) -> Unit = {}
    var beforeMessageRead: suspend (String) -> Unit = {}
    var beforeMetadata: suspend (String) -> Unit = {}
    var readFailure: Failure? = null
    var pendingOverride: AppResult<CollaborationComposerSubmissionPage>? = null
    val missingConversations = mutableSetOf<String>()
    var onInsert:
        suspend (CollaborationSpaceMessageIntent) -> AppResult<CollaborationMessageInsertion> =
        {
            records[it.id] =
                CollaborationSpaceMessageRecord(it, 0, CollaborationMessageState.Queued)
            AppResult.Success(CollaborationMessageInsertion.STORED)
        }
    var onRemove: suspend (CollaborationComposerSubmission) -> AppResult<Boolean> = {
        if (retained[it.id] != it) AppResult.Success(false)
        else {
            retained.remove(it.id)
            AppResult.Success(true)
        }
    }
    val roots =
        object : CollaborationSpaceRootRepository {
            override suspend fun current(
                access: CollaborationSpaceAccountAccess
            ): AppResult<CollaborationSpaceRoot?> {
                beforeRoot()
                return AppResult.Success(root)
            }

            override fun observe(
                access: CollaborationSpaceAccountAccess
            ): Flow<AppResult<CollaborationSpaceRoot?>> = error("No collector")

            override suspend fun retain(
                access: CollaborationSpaceAccountAccess,
                root: CollaborationSpaceRoot,
            ): AppResult<Boolean> = error("No discovery mutation")
        }
    val cursors =
        object : CollaborationSpaceComposerRecoveryCursorRepository {
            override suspend fun current(
                access: CollaborationSpaceLocalAccess
            ): AppResult<CollaborationComposerRecoveryCursor?> {
                cursorReads++
                beforeCursor()
                return AppResult.Success(cursor)
            }

            override suspend fun advance(
                access: CollaborationSpaceLocalAccess,
                expected: CollaborationComposerRecoveryCursor?,
                submissionId: String,
            ): AppResult<CollaborationComposerRecoveryCursor?> {
                if (cursor != expected) return AppResult.Success(null)
                cursorWrites++
                cursor =
                    CollaborationComposerRecoveryCursor(
                        deliveryId(4000 + cursorWrites),
                        submissionId,
                    )
                return AppResult.Success(cursor)
            }
        }
    val outbox =
        object : CollaborationSpaceComposerOutboxRepository by composer.outbox {
            override suspend fun get(
                access: CollaborationSpaceLocalAccess,
                id: String,
            ): AppResult<CollaborationComposerSubmission?> {
                outboxReads++
                beforeOutboxRead(id)
                return AppResult.Success(retained[id])
            }

            override suspend fun pending(
                access: CollaborationSpaceLocalAccess,
                after: String?,
                limit: Int,
            ): AppResult<CollaborationComposerSubmissionPage> {
                check(limit == 8)
                positions += after
                pendingOverride?.let {
                    return it
                }
                val values =
                    retained.values
                        .filter { it.id !in held && (after == null || it.id > after) }
                        .sortedBy { it.id }
                return AppResult.Success(
                    CollaborationComposerSubmissionPage(
                        values.take(limit),
                        if (values.size > limit) values[limit - 1].id else null,
                    )
                )
            }

            override suspend fun hold(
                access: CollaborationSpaceLocalAccess,
                expected: CollaborationComposerSubmission,
            ): AppResult<Boolean> {
                if (retained[expected.id] != expected) return AppResult.Success(false)
                held += expected.id
                return AppResult.Success(true)
            }

            override suspend fun remove(
                access: CollaborationSpaceLocalAccess,
                expected: CollaborationComposerSubmission,
            ): AppResult<Boolean> {
                retired += expected
                return onRemove(expected)
            }
        }
    val messages =
        object : CollaborationSpaceMessageRepository by base.messages {
            override fun observe(
                access: CollaborationSpaceLocalAccess,
                conversationId: String?,
            ): Flow<AppResult<List<CollaborationSpaceMessageRecord>>> = flow {
                check(
                    access.messageOwner() == this@SpaceComposerRecoveryFixture.access.messageOwner()
                )
                check(conversationId == null)
                inventoryReads++
                try {
                    emitAll(readInventory())
                } finally {
                    inventoryStops++
                }
            }

            override suspend fun get(
                access: CollaborationSpaceLocalAccess,
                id: String,
            ): AppResult<CollaborationSpaceMessageRecord?> {
                messageReads++
                beforeMessageRead(id)
                return readFailure?.let { AppResult.Failed(it) } ?: AppResult.Success(records[id])
            }

            override suspend fun insert(
                access: CollaborationSpaceLocalAccess,
                intent: CollaborationSpaceMessageIntent,
            ): AppResult<CollaborationMessageInsertion> {
                inserted += intent
                return onInsert(intent)
            }
        }
    val metadata =
        object : CollaborationSpaceConversationMetadataRepository by base.metadata {
            override suspend fun getById(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
            ): AppResult<CollaborationSpaceConversationMetadata?> {
                metadataReads++
                beforeMetadata(conversationId)
                return AppResult.Success(
                    if (conversationId in missingConversations) null
                    else base.canonical?.copy(id = conversationId)
                )
            }
        }

    fun recover() =
        RecoverCollaborationSpaceComposer(
            roots,
            outbox,
            cursors,
            messages,
            metadata,
            base.base.directory,
            base.sessions,
            base.native.server,
        )

    fun original(index: Int = 0) =
        composer.submission.copy(
            original =
                composer.original.copy(
                    revision = deliveryId(2000 + index),
                    text = " Original $index\n ",
                ),
            nextRevision = deliveryId(3000 + index),
            submittedAt = composer.submission.submittedAt + index.milliseconds,
        )

    fun retain(count: Int): List<CollaborationComposerSubmission> =
        (0 until count).map(::original).also { values -> values.forEach { retained[it.id] = it } }
}
