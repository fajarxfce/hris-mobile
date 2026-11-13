@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.collaboration.data.repositories

import dev.fajar.starter.collaboration.data.drafts.*
import dev.fajar.starter.collaboration.data.history.CollaborationHistoryKeys
import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.validCollaborationMessageId
import dev.fajar.starter.collaboration.domain.repositories.CollaborationComposerRecoveryCursorRepository
import dev.fajar.starter.collaboration.domain.repositories.CollaborationSpaceComposerRecoveryCursorRepository
import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.storage.safeStorageCall
import kotlin.uuid.Uuid
import org.koin.core.annotation.*

@Single
class EncryptedCollaborationComposerRecoveryCursorRepository(
    private val replicas: ReplicaStore,
    private val cipher: ContentCipher,
    @Named("collaboration-history-content-key") private val credentials: AtomicCredentialStore,
) :
    CollaborationComposerRecoveryCursorRepository,
    CollaborationSpaceComposerRecoveryCursorRepository {
    private val keys = CollaborationHistoryKeys(replicas, cipher, credentials)

    override suspend fun current(access: CollaborationLocalAccess) =
        current(CollaborationComposerCoordinates.Company(access))

    override suspend fun current(access: CollaborationSpaceLocalAccess) =
        current(CollaborationComposerCoordinates.Space(access))

    override suspend fun advance(
        access: CollaborationLocalAccess,
        expected: CollaborationComposerRecoveryCursor?,
        submissionId: String,
    ) = advance(CollaborationComposerCoordinates.Company(access), expected, submissionId)

    override suspend fun advance(
        access: CollaborationSpaceLocalAccess,
        expected: CollaborationComposerRecoveryCursor?,
        submissionId: String,
    ) = advance(CollaborationComposerCoordinates.Space(access), expected, submissionId)

    private suspend fun current(coordinates: CollaborationComposerCoordinates) = safeStorageCall {
        val raw = replicas.checkpoint(coordinates.scope, coordinates.recoveryPartition)
        raw?.let {
            keys.use { content, _ ->
                CollaborationComposerRecoveryCursorCodec(coordinates, cipher, content).decode(it)
            }
        }
    }

    private suspend fun advance(
        coordinates: CollaborationComposerCoordinates,
        expected: CollaborationComposerRecoveryCursor?,
        submissionId: String,
    ) = safeStorageCall {
        require(validCollaborationMessageId(submissionId))
        require(
            expected == null ||
                (validCollaborationMessageId(expected.generation) &&
                    validCollaborationMessageId(expected.afterSubmissionId))
        )
        val scope = coordinates.scope
        val partition = coordinates.recoveryPartition
        val raw = replicas.checkpoint(scope, partition)
        if (raw == null && expected != null) return@safeStorageCall null
        keys.use(create = raw == null && expected == null) { content, _ ->
            val codec = CollaborationComposerRecoveryCursorCodec(coordinates, cipher, content)
            val current = raw?.let { codec.decode(it) }
            if (current != expected) return@use null
            val value = CollaborationComposerRecoveryCursor(Uuid.random().toString(), submissionId)
            if (
                replicas.replace(
                    scope,
                    partition,
                    raw,
                    ReplicaSnapshot(
                        value.generation,
                        codec.encode(value.generation, value),
                        emptyList(),
                    ),
                )
            )
                value
            else null
        }
    }
}
