@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.leave.data.working.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.database.ReplicaStore
import dev.fajar.starter.leave.data.encoding.readOrCreateLeaveReplicaKey
import dev.fajar.starter.leave.data.mappers.leaveReplicaScope
import dev.fajar.starter.leave.data.working.encoding.*
import dev.fajar.starter.leave.domain.entities.LeaveAccess
import dev.fajar.starter.leave.domain.working.entities.LeaveWorkingCopy
import dev.fajar.starter.leave.domain.working.policies.validateLeaveWorkingCopy
import dev.fajar.starter.leave.domain.working.repositories.LeaveWorkingCopyRepository
import dev.fajar.starter.securestorage.ContentCipher
import dev.fajar.starter.securestorage.CredentialStore
import dev.fajar.starter.storage.safeStorageCall
import dev.fajar.starter.storage.safeStorageFlow
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

@Single
class EncryptedLeaveWorkingCopyRepository(
    private val records: ReplicaStore,
    private val cipher: ContentCipher,
    @Named("leave-replica-content-key") private val credentials: CredentialStore,
    @Named("leave-content-key-creation") private val keyCreation: Mutex,
) : LeaveWorkingCopyRepository {
    override fun observePage(access: LeaveAccess, after: String?) =
        safeStorageFlow(
                flow {
                    emitAll(
                        records.observePublishedCheckpoints(
                            leaveReplicaScope(access),
                            "leave-replica/${access.companyId}/working/",
                            after,
                            51,
                        )
                    )
                },
                mapValue = { rows ->
                    val prefix = "leave-replica/${access.companyId}/working/"
                    val selected = rows.take(50)
                    val copies =
                        if (selected.isEmpty()) emptyList()
                        else {
                            val key = Base64.decode(requireNotNull(credentials.read()))
                            try {
                                selected.map { raw ->
                                    decodeLeaveWorkingCopy(
                                        access,
                                        raw.handle.partition.removePrefix(prefix),
                                        raw,
                                        cipher,
                                        key,
                                    )
                                }
                            } finally {
                                key.fill(0)
                            }
                        }
                    dev.fajar.starter.leave.domain.entities.LeavePage(
                        copies,
                        selected.lastOrNull()?.handle?.partition?.takeIf { rows.size > 50 },
                    )
                },
            )
            .flowOn(Dispatchers.Default)

    override fun observe(access: LeaveAccess, employeeId: String) =
        safeStorageFlow(
                flow {
                    emitAll(
                        records.observeCheckpoint(
                            leaveReplicaScope(access),
                            leaveWorkingPartition(access, employeeId),
                        )
                    )
                },
                mapValue = { raw ->
                    if (raw == null) null
                    else {
                        val key = Base64.decode(requireNotNull(credentials.read()))
                        try {
                            decodeLeaveWorkingCopy(access, employeeId, raw, cipher, key)
                        } finally {
                            key.fill(0)
                        }
                    }
                },
            )
            .flowOn(Dispatchers.Default)

    override suspend fun save(
        access: LeaveAccess,
        expectedRevision: String?,
        copy: LeaveWorkingCopy,
    ): AppResult<LeaveWorkingCopy?> {
        val snapshot =
            copy.copy(input = copy.input.copy(attachments = copy.input.attachments.toList()))
        return withContext(Dispatchers.Default) {
            safeStorageCall {
                require(validateLeaveWorkingCopy(snapshot) is AppResult.Success)
                expectedRevision?.let(Uuid::parse)
                val scope = leaveReplicaScope(access)
                val partition = leaveWorkingPartition(access, snapshot.employeeId)
                val previous = records.checkpoint(scope, partition)
                val revision = previous?.handle?.generation
                if (revision != expectedRevision && revision != snapshot.revision)
                    return@safeStorageCall null
                val key =
                    keyCreation.withLock {
                        readOrCreateLeaveReplicaKey(records, cipher, credentials)
                    }
                try {
                    if (revision == snapshot.revision) {
                        val stored =
                            decodeLeaveWorkingCopy(
                                access,
                                snapshot.employeeId,
                                requireNotNull(previous),
                                cipher,
                                key,
                            )
                        return@safeStorageCall stored.takeIf {
                            it.copy(savedAt = snapshot.savedAt) == snapshot
                        }
                    }
                    val encoded = encodeLeaveWorkingCopy(access, snapshot, cipher, key)
                    currentCoroutineContext().ensureActive()
                    if (records.replace(scope, partition, previous, encoded)) snapshot else null
                } finally {
                    key.fill(0)
                }
            }
        }
    }

    override suspend fun remove(access: LeaveAccess, employeeId: String, expectedRevision: String) =
        withContext(Dispatchers.Default) {
            safeStorageCall {
                Uuid.parse(expectedRevision)
                val scope = leaveReplicaScope(access)
                val partition = leaveWorkingPartition(access, employeeId)
                val previous = records.checkpoint(scope, partition)
                if (previous?.handle?.generation != expectedRevision) false
                else records.replace(scope, partition, previous, null)
            }
        }
}
