package dev.fajar.starter.documents.data.metadata.commands.repositories

import dev.fajar.starter.database.*
import dev.fajar.starter.documents.data.metadata.commands.encoding.DocumentMetadataCommandCodec
import dev.fajar.starter.documents.data.metadata.commands.mappers.*
import dev.fajar.starter.documents.data.metadata.mappers.requireDocumentMetadataId
import dev.fajar.starter.documents.domain.entities.DocumentAccess
import dev.fajar.starter.documents.domain.metadata.commands.entities.*
import dev.fajar.starter.documents.domain.metadata.commands.policies.belongsTo
import dev.fajar.starter.documents.domain.metadata.commands.repositories.DocumentMetadataCommandRepository
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.storage.*
import kotlin.io.encoding.Base64
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.annotation.*

@Single
class EncryptedDocumentMetadataCommandRepository(
    private val commands: CommandStore,
    private val cipher: ContentCipher,
    @Named("command-content-key") private val credentials: AtomicCredentialStore,
) : DocumentMetadataCommandRepository {
    private val insertions = Mutex()
    private val codec = DocumentMetadataCommandCodec(cipher)

    override fun observeAll(access: DocumentAccess) =
        safeStorageFlow(
                flow {
                    emitAll(
                        commands.observeAll(
                            metadataCommandScope(access),
                            metadataCommandPartition(access),
                        )
                    )
                },
                mapValue = { rows ->
                    require(rows.size <= 128)
                    if (rows.isEmpty()) emptyList()
                    else {
                        val key = readContentKey()
                        try {
                            rows.map { codec.open(access, it, key) }
                        } finally {
                            key.fill(0)
                        }
                    }
                },
            )
            .flowOn(Dispatchers.Default)

    override fun observe(access: DocumentAccess, documentId: String) =
        safeStorageFlow(
                flow {
                    requireDocumentMetadataId(documentId)
                    emitAll(
                        commands.observe(
                            metadataCommandScope(access),
                            metadataCommandPartition(access),
                            documentId,
                        )
                    )
                },
                mapValue = { rows ->
                    require(rows.size <= 1)
                    val raw = rows.singleOrNull()
                    if (raw == null) null
                    else {
                        val key = readContentKey()
                        try {
                            codec.open(access, raw, key).also {
                                require(it.command.change.documentId == documentId)
                            }
                        } finally {
                            key.fill(0)
                        }
                    }
                },
            )
            .flowOn(Dispatchers.Default)

    override suspend fun find(access: DocumentAccess, operationId: String) = safeStorageCall {
        withContext(Dispatchers.Default) {
            requireDocumentMetadataId(operationId)
            val raw =
                commands.get(
                    metadataCommandScope(access),
                    metadataCommandPartition(access),
                    operationId,
                )
            if (raw == null) null
            else {
                val key = readContentKey()
                try {
                    codec.open(access, raw, key).also {
                        require(it.command.change.operationId == operationId)
                    }
                } finally {
                    key.fill(0)
                }
            }
        }
    }

    override suspend fun insert(access: DocumentAccess, command: DocumentMetadataCommand) =
        safeStorageCall {
            withContext(Dispatchers.Default) {
                insertions.withLock {
                    require(command.toDto().toCommand() == command && command.belongsTo(access))
                    val scope = metadataCommandScope(access)
                    val partition = metadataCommandPartition(access)
                    val previous = commands.get(scope, partition, command.change.operationId)
                    val key = if (previous == null) acquireContentKey() else readContentKey()
                    try {
                        if (previous != null) {
                            require(codec.open(access, previous, key).command == command)
                            DocumentMetadataCommandAdmission.STORED
                        } else {
                            val request = codec.sealRequest(access, command, key)
                            val state =
                                codec.sealOutcome(
                                    access,
                                    command,
                                    0,
                                    DocumentMetadataCommandOutcome.Pending,
                                    0,
                                    key,
                                )
                            currentCoroutineContext().ensureActive()
                            when (
                                commands.insert(
                                    scope,
                                    CommandRecord(
                                        partition,
                                        command.change.operationId,
                                        command.change.documentId,
                                        command.orderKey(),
                                        request,
                                        state,
                                    ),
                                )
                            ) {
                                CommandInsertResult.Stored ->
                                    DocumentMetadataCommandAdmission.STORED
                                CommandInsertResult.AlreadyStored -> {
                                    val stored =
                                        requireNotNull(
                                            commands.get(
                                                scope,
                                                partition,
                                                command.change.operationId,
                                            )
                                        )
                                    require(codec.open(access, stored, key).command == command)
                                    DocumentMetadataCommandAdmission.STORED
                                }
                                CommandInsertResult.ResourceOccupied ->
                                    DocumentMetadataCommandAdmission.OCCUPIED
                                CommandInsertResult.CapacityExceeded ->
                                    DocumentMetadataCommandAdmission.CAPACITY_EXCEEDED
                                CommandInsertResult.ScopeChanged ->
                                    DocumentMetadataCommandAdmission.SCOPE_CHANGED
                            }
                        }
                    } finally {
                        key.fill(0)
                    }
                }
            }
        }

    override suspend fun update(
        access: DocumentAccess,
        expected: DocumentMetadataCommandRecord,
        outcome: DocumentMetadataCommandOutcome,
        attempts: Int,
    ) = safeStorageCall {
        withContext(Dispatchers.Default) {
            require(
                expected.command.toDto().toCommand() == expected.command &&
                    expected.command.belongsTo(access)
            )
            require(expected.revision in 0 until 9_007_199_254_740_991L)
            val raw =
                commands.get(
                    metadataCommandScope(access),
                    metadataCommandPartition(access),
                    expected.command.change.operationId,
                ) ?: return@withContext false
            val key = readContentKey()
            try {
                if (codec.open(access, raw, key) != expected) return@withContext false
                if (
                    expected.outcome is DocumentMetadataCommandOutcome.Confirmed ||
                        expected.outcome is DocumentMetadataCommandOutcome.Rejected
                )
                    return@withContext false
                require(attempts >= expected.attempts && attempts <= expected.attempts + 1)
                val sealed =
                    codec.sealOutcome(
                        access,
                        expected.command,
                        expected.revision + 1,
                        outcome,
                        attempts,
                        key,
                    )
                currentCoroutineContext().ensureActive()
                commands.update(
                    metadataCommandScope(access),
                    metadataCommandPartition(access),
                    expected.command.change.operationId,
                    expected.revision,
                    sealed,
                )
            } finally {
                key.fill(0)
            }
        }
    }

    override suspend fun remove(access: DocumentAccess, expected: DocumentMetadataCommandRecord) =
        safeStorageCall {
            withContext(Dispatchers.Default) {
                require(
                    expected.command.toDto().toCommand() == expected.command &&
                        expected.command.belongsTo(access)
                )
                val raw =
                    commands.get(
                        metadataCommandScope(access),
                        metadataCommandPartition(access),
                        expected.command.change.operationId,
                    ) ?: return@withContext false
                val key = readContentKey()
                try {
                    if (codec.open(access, raw, key) != expected) return@withContext false
                    currentCoroutineContext().ensureActive()
                    commands.remove(
                        metadataCommandScope(access),
                        metadataCommandPartition(access),
                        expected.command.change.operationId,
                        expected.revision,
                    )
                } finally {
                    key.fill(0)
                }
            }
        }

    private suspend fun readContentKey(): ByteArray =
        Base64.decode(requireNotNull(credentials.read()) { "Command content key is unavailable." })
            .also { require(it.size == 32) }

    /**
     * A lost shared key never replaces ciphertext retained by this or another command partition.
     */
    private suspend fun acquireContentKey(): ByteArray {
        val existing = credentials.read()
        if (existing != null) return Base64.decode(existing).also { require(it.size == 32) }
        if (commands.containsRecords()) return readContentKey()
        val generated = cipher.generateKey()
        return try {
            Base64.decode(credentials.writeIfAbsent(Base64.encode(generated))).also {
                require(it.size == 32)
            }
        } finally {
            generated.fill(0)
        }
    }
}
