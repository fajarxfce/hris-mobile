package dev.fajar.starter.expenses.data.repositories

import dev.fajar.starter.database.*
import dev.fajar.starter.expenses.data.dto.*
import dev.fajar.starter.expenses.data.encoding.*
import dev.fajar.starter.expenses.data.mappers.*
import dev.fajar.starter.expenses.domain.entities.*
import dev.fajar.starter.expenses.domain.policies.sameExpenseScope
import dev.fajar.starter.expenses.domain.repositories.ExpenseCacheRepository
import dev.fajar.starter.securestorage.AtomicCredentialStore
import dev.fajar.starter.securestorage.ContentCipher
import dev.fajar.starter.storage.*
import kotlin.io.encoding.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.koin.core.annotation.*

@Single
class EncryptedExpenseCacheRepository(
    private val replicas: ReplicaStore,
    private val cipher: ContentCipher,
    @Named("workspace-content-key") private val credentials: AtomicCredentialStore,
) : ExpenseCacheRepository {
    override fun observe(access: ExpenseAccess) =
        safeStorageFlow(
                replicas.observe(
                    access.sessionId,
                    expensePartition(access, "overview"),
                    limit = EXPENSE_CACHE_MAXIMUM_CHUNKS + 1,
                ),
                mapValue = { page ->
                    val checkpoint = page.checkpoint
                    if (checkpoint == null) null
                    else {
                        val key = Base64.decode(requireNotNull(credentials.read()))
                        try {
                            require(key.size == 32)
                            val bytes = decodeExpenseCache(page, access, "overview", cipher, key)
                            try {
                                require(bytes.size <= EXPENSE_CACHE_MAXIMUM_BYTES)
                                Json.decodeFromString<ExpenseSnapshotDto<ExpenseOverviewDto>>(
                                        bytes.decodeToString(throwOnInvalidSequence = true)
                                    )
                                    .toSnapshot(access, checkpoint.handle.generation) {
                                        it.toOverview(access)
                                    }
                            } finally {
                                bytes.fill(0)
                            }
                        } finally {
                            key.fill(0)
                        }
                    }
                },
            )
            .flowOn(Dispatchers.Default)

    override suspend fun generation(access: ExpenseAccess) = safeStorageCall {
        replicas
            .checkpoint(access.sessionId, expensePartition(access, "overview"))
            ?.handle
            ?.generation
    }

    override suspend fun replace(
        access: ExpenseAccess,
        expectedGeneration: String?,
        snapshot: ExpenseSnapshot<ExpenseOverview>?,
    ) = safeStorageCall {
        withContext(Dispatchers.Default) {
            val partition = expensePartition(access, "overview")
            val previous = replicas.checkpoint(access.sessionId, partition)
            if (previous?.handle?.generation != expectedGeneration) return@withContext false
            if (snapshot == null)
                return@withContext replicas.replace(access.sessionId, partition, previous, null)
            require(
                sameExpenseScope(access, snapshot.access) &&
                    snapshot.generation != expectedGeneration &&
                    snapshot.expiresAt <= snapshot.access.readUntil
            )
            val dto = snapshot.toDto { it.toDto() }
            dto.toSnapshot(access, snapshot.generation) { it.toOverview(access) }
            val key = Base64.decode(requireNotNull(credentials.read()))
            try {
                require(key.size == 32)
                val bytes = Json.encodeToString(dto).encodeToByteArray()
                val encoded =
                    try {
                        encodeExpenseCache(
                            bytes,
                            access,
                            snapshot.generation,
                            "overview",
                            cipher,
                            key,
                        )
                    } finally {
                        bytes.fill(0)
                    }
                currentCoroutineContext().ensureActive()
                replicas.replace(access.sessionId, partition, previous, encoded)
            } finally {
                key.fill(0)
            }
        }
    }

    override fun observeDetails(access: ExpenseAccess) =
        safeStorageFlow(
                replicas.observe(
                    access.sessionId,
                    expensePartition(access, "details"),
                    limit = EXPENSE_CACHE_MAXIMUM_CHUNKS + 1,
                ),
                mapValue = { page ->
                    val checkpoint = page.checkpoint
                    if (checkpoint == null) null
                    else {
                        val key = Base64.decode(requireNotNull(credentials.read()))
                        try {
                            require(key.size == 32)
                            val bytes = decodeExpenseCache(page, access, "details", cipher, key)
                            try {
                                require(bytes.size <= EXPENSE_CACHE_MAXIMUM_BYTES)
                                Json.decodeFromString<ExpenseSnapshotDto<ExpenseViewDto>>(
                                        bytes.decodeToString(throwOnInvalidSequence = true)
                                    )
                                    .toSnapshot(access, checkpoint.handle.generation) {
                                        it.toView()
                                    }
                            } finally {
                                bytes.fill(0)
                            }
                        } finally {
                            key.fill(0)
                        }
                    }
                },
            )
            .flowOn(Dispatchers.Default)

    override suspend fun detailsGeneration(access: ExpenseAccess) = safeStorageCall {
        replicas
            .checkpoint(access.sessionId, expensePartition(access, "details"))
            ?.handle
            ?.generation
    }

    override suspend fun replaceDetails(
        access: ExpenseAccess,
        expectedGeneration: String?,
        snapshot: ExpenseSnapshot<ExpenseView>?,
    ) = safeStorageCall {
        withContext(Dispatchers.Default) {
            val partition = expensePartition(access, "details")
            val previous = replicas.checkpoint(access.sessionId, partition)
            if (previous?.handle?.generation != expectedGeneration) return@withContext false
            if (snapshot == null)
                return@withContext replicas.replace(access.sessionId, partition, previous, null)
            require(
                sameExpenseScope(access, snapshot.access) &&
                    snapshot.generation != expectedGeneration &&
                    snapshot.expiresAt <= snapshot.access.readUntil
            )
            val dto = snapshot.toDto { it.toDto() }
            dto.toSnapshot(access, snapshot.generation) { it.toView() }
            val key = Base64.decode(requireNotNull(credentials.read()))
            try {
                require(key.size == 32)
                val bytes = Json.encodeToString(dto).encodeToByteArray()
                val encoded =
                    try {
                        encodeExpenseCache(
                            bytes,
                            access,
                            snapshot.generation,
                            "details",
                            cipher,
                            key,
                        )
                    } finally {
                        bytes.fill(0)
                    }
                currentCoroutineContext().ensureActive()
                replicas.replace(access.sessionId, partition, previous, encoded)
            } finally {
                key.fill(0)
            }
        }
    }
}
