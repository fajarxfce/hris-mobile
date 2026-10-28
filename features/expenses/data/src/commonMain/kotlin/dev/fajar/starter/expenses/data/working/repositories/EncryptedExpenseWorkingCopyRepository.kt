package dev.fajar.starter.expenses.data.working.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.database.ReplicaStore
import dev.fajar.starter.expenses.data.replica.encoding.readOrCreateExpenseReplicaKey
import dev.fajar.starter.expenses.data.replica.mappers.expenseReplicaScope
import dev.fajar.starter.expenses.data.working.encoding.*
import dev.fajar.starter.expenses.domain.entities.ExpenseAccess
import dev.fajar.starter.expenses.domain.policies.validateExpenseId
import dev.fajar.starter.expenses.domain.working.entities.*
import dev.fajar.starter.expenses.domain.working.policies.validateExpenseWorkingCopy
import dev.fajar.starter.expenses.domain.working.repositories.ExpenseWorkingCopyRepository
import dev.fajar.starter.securestorage.ContentCipher
import dev.fajar.starter.securestorage.CredentialStore
import dev.fajar.starter.storage.safeStorageCall
import dev.fajar.starter.storage.safeStorageFlow
import kotlin.io.encoding.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

@Single
class EncryptedExpenseWorkingCopyRepository(
    private val records: ReplicaStore,
    private val cipher: ContentCipher,
    @Named("expense-replica-content-key") private val credentials: CredentialStore,
    @Named("expense-content-key-creation") private val keyCreation: Mutex,
) : ExpenseWorkingCopyRepository {
    override fun observePage(access: ExpenseAccess, after: String?) =
        safeStorageFlow(
                flow {
                    emitAll(
                        records.observePublishedCheckpoints(
                            expenseReplicaScope(access),
                            "expense-working-copy/${access.companyId}/",
                            after,
                            51,
                        )
                    )
                },
                mapValue = { rows ->
                    val prefix = "expense-working-copy/${access.companyId}/"
                    val selected = rows.take(50)
                    val copies =
                        if (selected.isEmpty()) emptyList()
                        else {
                            val key = Base64.decode(requireNotNull(credentials.read()))
                            try {
                                selected.mapNotNull { raw ->
                                    run {
                                        val parts =
                                            raw.handle.partition.removePrefix(prefix).split('/')
                                        require(parts.size == 2)
                                        val slot =
                                            when (parts[0]) {
                                                "new" -> ExpenseWorkingSlot.Creation(parts[1])
                                                "claim" -> ExpenseWorkingSlot.Claim(parts[1])
                                                else -> error("Invalid expense working slot.")
                                            }
                                        val page =
                                            records
                                                .observe(
                                                    expenseReplicaScope(access),
                                                    raw.handle.partition,
                                                    limit = 5,
                                                )
                                                .first()
                                        decodeExpenseWorkingCopy(access, slot, page, cipher, key)
                                    }
                                }
                            } finally {
                                key.fill(0)
                            }
                        }
                    dev.fajar.starter.expenses.domain.entities.ExpensePage(
                        copies,
                        selected.lastOrNull()?.handle?.partition?.takeIf { rows.size > 50 },
                    )
                },
            )
            .flowOn(Dispatchers.Default)

    override fun observe(access: ExpenseAccess, slot: ExpenseWorkingSlot) =
        safeStorageFlow(
                flow {
                    emitAll(
                        records.observe(
                            expenseReplicaScope(access),
                            expenseWorkingPartition(access, slot),
                            limit = 5,
                        )
                    )
                },
                mapValue = { page ->
                    if (page.checkpoint == null) {
                        require(page.documents.isEmpty())
                        null
                    } else {
                        val key = Base64.decode(requireNotNull(credentials.read()))
                        try {
                            decodeExpenseWorkingCopy(access, slot, page, cipher, key)
                        } finally {
                            key.fill(0)
                        }
                    }
                },
            )
            .flowOn(Dispatchers.Default)

    override suspend fun save(
        access: ExpenseAccess,
        slot: ExpenseWorkingSlot,
        expectedRevision: String?,
        copy: ExpenseWorkingCopy,
    ) =
        withContext(Dispatchers.Default) {
            safeStorageCall {
                require(validateExpenseWorkingCopy(slot, copy) is AppResult.Success)
                require(
                    expectedRevision == null ||
                        validateExpenseId(expectedRevision) is AppResult.Success
                )
                val scope = expenseReplicaScope(access)
                val partition = expenseWorkingPartition(access, slot)
                val previous = records.observe(scope, partition, limit = 5).first()
                val generation = previous.checkpoint?.handle?.generation
                if (generation != expectedRevision && generation != copy.revision)
                    return@safeStorageCall null
                val key =
                    keyCreation.withLock {
                        readOrCreateExpenseReplicaKey(records, cipher, credentials)
                    }
                try {
                    if (generation == copy.revision) {
                        val stored =
                            requireNotNull(
                                decodeExpenseWorkingCopy(access, slot, previous, cipher, key)
                            )
                        return@safeStorageCall stored.takeIf {
                            it.copy(savedAt = copy.savedAt) == copy
                        }
                    }
                    val encoded = encodeExpenseWorkingCopy(access, slot, copy, cipher, key)
                    currentCoroutineContext().ensureActive()
                    if (records.replace(scope, partition, previous.checkpoint, encoded)) copy
                    else null
                } finally {
                    key.fill(0)
                }
            }
        }

    override suspend fun remove(
        access: ExpenseAccess,
        slot: ExpenseWorkingSlot,
        expectedRevision: String,
    ) =
        withContext(Dispatchers.Default) {
            safeStorageCall {
                require(validateExpenseId(expectedRevision) is AppResult.Success)
                val scope = expenseReplicaScope(access)
                val partition = expenseWorkingPartition(access, slot)
                val previous = records.checkpoint(scope, partition)
                if (previous?.handle?.generation != expectedRevision) false
                else records.replace(scope, partition, previous, null)
            }
        }
}
