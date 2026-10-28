package dev.fajar.starter.expenses.data.repositories

import dev.fajar.starter.database.*
import dev.fajar.starter.expenses.data.dto.*
import dev.fajar.starter.expenses.data.mappers.*
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.entities.ExpenseAccess
import dev.fajar.starter.expenses.domain.policies.*
import dev.fajar.starter.expenses.domain.repositories.ExpenseCommandRepository
import dev.fajar.starter.securestorage.*
import dev.fajar.starter.storage.*
import kotlin.io.encoding.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.koin.core.annotation.*

@Single
class EncryptedExpenseCommandRepository(
    private val commands: CommandStore,
    private val cipher: ContentCipher,
    @Named("command-content-key") private val credentials: AtomicCredentialStore,
) : ExpenseCommandRepository {
    private val insertions = Mutex()

    override fun observeAll(access: ExpenseAccess) =
        safeStorageFlow(
                kotlinx.coroutines.flow.flow {
                    emitAll(
                        commands.observeAll(
                            expenseCommandScope(access),
                            expenseCommandPartition(access),
                        )
                    )
                },
                mapValue = { rows ->
                    if (rows.isEmpty()) emptyList()
                    else {
                        val key = readContentKey()
                        try {
                            rows.map { decryptRecord(access, it, key) }
                        } finally {
                            key.fill(0)
                        }
                    }
                },
            )
            .flowOn(kotlinx.coroutines.Dispatchers.Default)

    override fun observe(access: ExpenseAccess, slot: ExpenseCommandSlot) =
        safeStorageFlow(
                commands.observe(
                    expenseCommandScope(access),
                    expenseCommandPartition(access),
                    slot.storageKey(),
                ),
                mapValue = { rows ->
                    require(rows.size <= 1)
                    val raw = rows.singleOrNull()
                    if (raw == null) null
                    else {
                        val key = readContentKey()
                        try {
                            decryptRecord(access, raw, key)
                        } finally {
                            key.fill(0)
                        }
                    }
                },
            )
            .flowOn(Dispatchers.Default)

    override suspend fun find(access: ExpenseAccess, id: String) = safeStorageCall {
        withContext(Dispatchers.Default) {
            val raw = commands.get(expenseCommandScope(access), expenseCommandPartition(access), id)
            if (raw == null) null
            else {
                val key = readContentKey()
                try {
                    decryptRecord(access, raw, key)
                } finally {
                    key.fill(0)
                }
            }
        }
    }

    override suspend fun page(access: ExpenseAccess, cursor: String?) = safeStorageCall {
        withContext(Dispatchers.Default) {
            val rows =
                commands
                    .observe(
                        expenseCommandScope(access),
                        expenseCommandPartition(access),
                        after = cursor,
                        limit = 50,
                    )
                    .first()
            require(rows.size <= 50)
            if (rows.isEmpty()) ExpensePage(emptyList(), null)
            else {
                val key = readContentKey()
                try {
                    ExpensePage(
                        rows.map { decryptRecord(access, it, key) },
                        rows.last().sortKey.takeIf { rows.size == 50 },
                    )
                } finally {
                    key.fill(0)
                }
            }
        }
    }

    override suspend fun insert(access: ExpenseAccess, command: ExpenseCommand) = safeStorageCall {
        withContext(Dispatchers.Default) {
            insertions.withLock {
                val dto = command.toDto()
                require(dto.toCommand() == command && command.belongsTo(access))
                val scope = expenseCommandScope(access)
                val partition = expenseCommandPartition(access)
                val previous = commands.get(scope, partition, command.id)
                val key = if (previous == null) acquireContentKey() else readContentKey()
                try {
                    if (previous != null) {
                        require(decryptRecord(access, previous, key).command == command)
                        ExpenseCommandAdmission.STORED
                    } else {
                        val requestBytes = Json.encodeToString(dto).encodeToByteArray()
                        val request =
                            try {
                                require(requestBytes.size <= 65_536)
                                cipher.encrypt(
                                    key,
                                    expenseCommandAssociatedData(
                                        access,
                                        command.id,
                                        command.slot().storageKey(),
                                        "request",
                                        0,
                                    ),
                                    requestBytes,
                                )
                            } finally {
                                requestBytes.fill(0)
                            }
                        val stateBytes =
                            Json.encodeToString(ExpenseCommandOutcome.Pending.toDto(0))
                                .encodeToByteArray()
                        val state =
                            try {
                                require(stateBytes.size <= 4_096)
                                cipher.encrypt(
                                    key,
                                    expenseCommandAssociatedData(
                                        access,
                                        command.id,
                                        command.slot().storageKey(),
                                        "outcome",
                                        0,
                                    ),
                                    stateBytes,
                                )
                            } finally {
                                stateBytes.fill(0)
                            }
                        currentCoroutineContext().ensureActive()
                        when (
                            commands.insert(
                                scope,
                                CommandRecord(
                                    partition,
                                    command.id,
                                    command.slot().storageKey(),
                                    command.orderKey(),
                                    Base64.encode(request),
                                    Base64.encode(state),
                                ),
                            )
                        ) {
                            CommandInsertResult.Stored,
                            CommandInsertResult.AlreadyStored -> ExpenseCommandAdmission.STORED
                            CommandInsertResult.ResourceOccupied -> ExpenseCommandAdmission.OCCUPIED
                            CommandInsertResult.CapacityExceeded ->
                                ExpenseCommandAdmission.CAPACITY_EXCEEDED
                            CommandInsertResult.ScopeChanged ->
                                ExpenseCommandAdmission.SCOPE_CHANGED
                        }
                    }
                } finally {
                    key.fill(0)
                }
            }
        }
    }

    override suspend fun update(
        access: ExpenseAccess,
        expected: ExpenseCommandRecord,
        outcome: ExpenseCommandOutcome,
        attempts: Int,
    ) = safeStorageCall {
        withContext(Dispatchers.Default) {
            require(
                expected.command.toDto().toCommand() == expected.command &&
                    expected.command.belongsTo(access)
            )
            require(expected.revision in 0 until 9_007_199_254_740_991L)
            val encoded = outcome.toDto(attempts)
            encoded.toOutcome(expected.command.expectedReceiptVersion())
            val key = readContentKey()
            val bytes = Json.encodeToString(encoded).encodeToByteArray()
            try {
                require(bytes.size <= 4_096)
                val sealed =
                    cipher.encrypt(
                        key,
                        expenseCommandAssociatedData(
                            access,
                            expected.command.id,
                            expected.command.slot().storageKey(),
                            "outcome",
                            expected.revision + 1,
                        ),
                        bytes,
                    )
                currentCoroutineContext().ensureActive()
                commands.update(
                    expenseCommandScope(access),
                    expenseCommandPartition(access),
                    expected.command.id,
                    expected.revision,
                    Base64.encode(sealed),
                )
            } finally {
                bytes.fill(0)
                key.fill(0)
            }
        }
    }

    override suspend fun remove(access: ExpenseAccess, expected: ExpenseCommandRecord) =
        safeStorageCall {
            withContext(Dispatchers.Default) {
                require(
                    expected.command.toDto().toCommand() == expected.command &&
                        expected.command.belongsTo(access)
                )
                commands.remove(
                    expenseCommandScope(access),
                    expenseCommandPartition(access),
                    expected.command.id,
                    expected.revision,
                )
            }
        }

    /** DTO decoding and both authenticated envelopes stay within the calling storage boundary. */
    private suspend fun decryptRecord(
        access: ExpenseAccess,
        raw: CommandRecord,
        key: ByteArray,
    ): ExpenseCommandRecord {
        require(raw.request.length <= 98_304 && raw.state.length <= 8_192)
        val request =
            cipher.decrypt(
                key,
                expenseCommandAssociatedData(access, raw.id, raw.resourceId, "request", 0),
                Base64.decode(raw.request),
            )
        val command =
            try {
                require(request.size <= 65_536)
                Json.decodeFromString<ExpenseCommandDto>(
                        request.decodeToString(throwOnInvalidSequence = true)
                    )
                    .toCommand()
            } finally {
                request.fill(0)
            }
        val outcome =
            cipher.decrypt(
                key,
                expenseCommandAssociatedData(
                    access,
                    raw.id,
                    raw.resourceId,
                    "outcome",
                    raw.revision,
                ),
                Base64.decode(raw.state),
            )
        return try {
            require(outcome.size <= 4_096)
            Json.decodeFromString<ExpenseCommandOutcomeDto>(
                    outcome.decodeToString(throwOnInvalidSequence = true)
                )
                .toRecord(command, raw, access)
        } finally {
            outcome.fill(0)
        }
    }

    private suspend fun readContentKey(): ByteArray =
        Base64.decode(requireNotNull(credentials.read()) { "Command content key is unavailable." })
            .also { require(it.size == 32) }

    /** Technical storage strategy only; a lost key never replaces retained ciphertext. */
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
