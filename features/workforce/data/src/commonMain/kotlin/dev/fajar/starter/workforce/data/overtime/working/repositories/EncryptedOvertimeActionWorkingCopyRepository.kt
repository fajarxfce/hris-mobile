@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.workforce.data.overtime.working.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.database.ReplicaStore
import dev.fajar.starter.securestorage.ContentCipher
import dev.fajar.starter.securestorage.CredentialStore
import dev.fajar.starter.storage.safeStorageCall
import dev.fajar.starter.storage.safeStorageFlow
import dev.fajar.starter.workforce.data.overtime.encoding.readOrCreateOvertimeReplicaKey
import dev.fajar.starter.workforce.data.overtime.mappers.overtimeReplicaScope
import dev.fajar.starter.workforce.data.overtime.working.encoding.*
import dev.fajar.starter.workforce.domain.entities.WorkforceAccess
import dev.fajar.starter.workforce.domain.overtime.entities.*
import dev.fajar.starter.workforce.domain.overtime.working.entities.OvertimeActionWorkingCopy
import dev.fajar.starter.workforce.domain.overtime.working.policies.validateOvertimeActionWorkingCopy
import dev.fajar.starter.workforce.domain.overtime.working.repositories.OvertimeActionWorkingCopyRepository
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

@Single
class EncryptedOvertimeActionWorkingCopyRepository(
    private val records: ReplicaStore,
    private val cipher: ContentCipher,
    @Named("overtime-replica-content-key") private val credentials: CredentialStore,
    @Named("overtime-content-key-creation") private val keyCreation: Mutex,
) : OvertimeActionWorkingCopyRepository {
    override fun observePage(access: WorkforceAccess, after: String?) =
        safeStorageFlow(
                flow {
                    emitAll(
                        records.observePublishedCheckpoints(
                            overtimeReplicaScope(access),
                            "overtime-replica/${access.companyId}/working/action/",
                            after,
                            51,
                        )
                    )
                },
                mapValue = { rows ->
                    val prefix = "overtime-replica/${access.companyId}/working/action/"
                    val selected = rows.take(50)
                    val copies =
                        if (selected.isEmpty()) emptyList()
                        else {
                            val key = Base64.decode(requireNotNull(credentials.read()))
                            try {
                                selected.map { raw ->
                                    run {
                                        val parts =
                                            raw.handle.partition.removePrefix(prefix).split('/')
                                        require(parts.size == 3)
                                        decodeOvertimeActionWorkingCopy(
                                            access,
                                            OvertimeRequestSelection(parts[1], parts[0]),
                                            OvertimeAction.valueOf(parts[2]),
                                            raw,
                                            cipher,
                                            key,
                                        )
                                    }
                                }
                            } finally {
                                key.fill(0)
                            }
                        }
                    dev.fajar.starter.workforce.domain.overtime.entities.OvertimePage(
                        copies,
                        selected.lastOrNull()?.handle?.partition?.takeIf { rows.size > 50 },
                    )
                },
            )
            .flowOn(Dispatchers.Default)

    override fun observe(
        access: WorkforceAccess,
        selection: OvertimeRequestSelection,
        action: OvertimeAction,
    ) =
        safeStorageFlow(
                flow {
                    emitAll(
                        records.observeCheckpoint(
                            overtimeReplicaScope(access),
                            overtimeActionWorkingPartition(access, selection, action),
                        )
                    )
                },
                mapValue = { raw ->
                    if (raw == null) null
                    else {
                        val key = Base64.decode(requireNotNull(credentials.read()))
                        try {
                            decodeOvertimeActionWorkingCopy(
                                access,
                                selection,
                                action,
                                raw,
                                cipher,
                                key,
                            )
                        } finally {
                            key.fill(0)
                        }
                    }
                },
            )
            .flowOn(Dispatchers.Default)

    override suspend fun save(
        access: WorkforceAccess,
        expectedRevision: String?,
        copy: OvertimeActionWorkingCopy,
    ): AppResult<OvertimeActionWorkingCopy?> =
        withContext(Dispatchers.Default) {
            safeStorageCall {
                require(validateOvertimeActionWorkingCopy(copy) is AppResult.Success)
                expectedRevision?.let(Uuid::parse)
                val scope = overtimeReplicaScope(access)
                val selection = OvertimeRequestSelection(copy.requestId, copy.employeeId)
                val partition = overtimeActionWorkingPartition(access, selection, copy.action)
                val previous = records.checkpoint(scope, partition)
                val revision = previous?.handle?.generation
                if (revision != expectedRevision && revision != copy.revision)
                    return@safeStorageCall null
                val key =
                    keyCreation.withLock {
                        readOrCreateOvertimeReplicaKey(records, cipher, credentials)
                    }
                try {
                    if (revision == copy.revision) {
                        val stored =
                            decodeOvertimeActionWorkingCopy(
                                access,
                                selection,
                                copy.action,
                                requireNotNull(previous),
                                cipher,
                                key,
                            )
                        return@safeStorageCall stored.takeIf {
                            it.copy(savedAt = copy.savedAt) == copy
                        }
                    }
                    val encoded = encodeOvertimeActionWorkingCopy(access, copy, cipher, key)
                    currentCoroutineContext().ensureActive()
                    if (records.replace(scope, partition, previous, encoded)) copy else null
                } finally {
                    key.fill(0)
                }
            }
        }

    override suspend fun remove(
        access: WorkforceAccess,
        selection: OvertimeRequestSelection,
        action: OvertimeAction,
        expectedRevision: String,
    ) =
        withContext(Dispatchers.Default) {
            safeStorageCall {
                Uuid.parse(expectedRevision)
                val scope = overtimeReplicaScope(access)
                val partition = overtimeActionWorkingPartition(access, selection, action)
                val previous = records.checkpoint(scope, partition)
                if (previous?.handle?.generation != expectedRevision) false
                else records.replace(scope, partition, previous, null)
            }
        }
}
