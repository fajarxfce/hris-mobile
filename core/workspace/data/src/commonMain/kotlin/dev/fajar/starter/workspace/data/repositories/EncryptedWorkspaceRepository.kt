@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.workspace.data.repositories

import dev.fajar.starter.database.*
import dev.fajar.starter.identity.domain.entities.IdentityServer
import dev.fajar.starter.network.safeApiCall
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.storage.*
import dev.fajar.starter.workspace.data.datasources.WorkspaceRemoteDataSource
import dev.fajar.starter.workspace.data.dto.WorkspaceRecord
import dev.fajar.starter.workspace.data.mappers.*
import dev.fajar.starter.workspace.domain.entities.Workspace
import dev.fajar.starter.workspace.domain.repositories.WorkspaceRepository
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import org.koin.core.annotation.*

@Single
class EncryptedWorkspaceRepository(
    private val records: ReplicaStore,
    private val remote: WorkspaceRemoteDataSource,
    private val cipher: ContentCipher,
    @Named("workspace-content-key") private val keys: AtomicCredentialStore,
    private val server: IdentityServer,
) : WorkspaceRepository {
    override fun observe(sessionId: String) =
        safeStorageFlow(
            flow { emitAll(records.observe(ReplicaScope(sessionId), PARTITION, limit = 1)) },
            mapValue = { page -> page.checkpoint?.let { decodeWorkspace(sessionId, it) } },
        )

    override suspend fun current(sessionId: String) = safeStorageCall {
        records.checkpoint(ReplicaScope(sessionId), PARTITION)?.let {
            decodeWorkspace(sessionId, it)
        }
    }

    override suspend fun compareAndSet(
        sessionId: String,
        expected: Workspace?,
        updated: Workspace?,
    ) = safeStorageCall {
        val previous = records.checkpoint(ReplicaScope(sessionId), PARTITION)
        val current = previous?.let { decodeWorkspace(sessionId, it) }
        if (current != expected) return@safeStorageCall false
        if (updated == null)
            return@safeStorageCall records.replace(
                ReplicaScope(sessionId),
                PARTITION,
                previous,
                null,
            )
        require(updated.sessionId == sessionId && updated.audience == server.audience)
        val dto = updated.toRecord()
        require(dto.toWorkspace() == updated)
        val saved = keys.read()
        val key =
            if (saved != null) Base64.decode(saved)
            else {
                check(previous == null) { "Existing workspace requires its content key." }
                val created = cipher.generateKey()
                try {
                    Base64.decode(keys.writeIfAbsent(Base64.encode(created)))
                } finally {
                    created.fill(0)
                }
            }
        try {
            val generation = Uuid.random().toString()
            val bytes = Json.encodeToString(dto).encodeToByteArray()
            try {
                require(bytes.size <= 24_000) { "Workspace snapshot is too large." }
                val encrypted =
                    cipher.encrypt(
                        key,
                        workspaceAssociatedData(server.audience, sessionId, generation),
                        bytes,
                    )
                records.replace(
                    ReplicaScope(sessionId),
                    PARTITION,
                    previous,
                    ReplicaSnapshot(generation, Base64.encode(encrypted), emptyList()),
                )
            } finally {
                bytes.fill(0)
            }
        } finally {
            key.fill(0)
        }
    }

    override suspend fun permissions(sessionId: String, companyId: String) = safeApiCall {
        Uuid.parse(companyId)
        val response = remote.permissions(sessionId, companyId)
        require(response.companyId == companyId && response.permissions.size <= 1000)
        require(response.permissions.all { it.matches(Regex("[a-z][a-z0-9_.:-]{0,99}")) })
        response.permissions.toSet()
    }

    override suspend fun clientPolicy(sessionId: String, companyId: String) = safeApiCall {
        Uuid.parse(companyId)
        remote.clientPolicy(sessionId, companyId).toPolicy()
    }

    private suspend fun decodeWorkspace(sessionId: String, record: ReplicaCheckpoint): Workspace {
        require(record.published && record.revision == 0L)
        require(record.handle.scope.sessionId == sessionId && record.handle.partition == PARTITION)
        val key = Base64.decode(requireNotNull(keys.read()) { "Workspace key is unavailable." })
        val bytes =
            try {
                cipher.decrypt(
                    key,
                    workspaceAssociatedData(server.audience, sessionId, record.handle.generation),
                    Base64.decode(record.payload),
                )
            } finally {
                key.fill(0)
            }
        return try {
            Json.decodeFromString<WorkspaceRecord>(
                    bytes.decodeToString(throwOnInvalidSequence = true)
                )
                .toWorkspace()
                .also { require(it.sessionId == sessionId && it.audience == server.audience) }
        } finally {
            bytes.fill(0)
        }
    }

    private companion object {
        const val PARTITION = "workspace/selected"
    }
}
