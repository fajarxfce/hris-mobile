package dev.fajar.starter.workforce.data.repositories

import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.ContentCipher
import dev.fajar.starter.securestorage.CredentialStore
import dev.fajar.starter.storage.*
import dev.fajar.starter.workforce.data.dto.WorkCalendarSnapshotDto
import dev.fajar.starter.workforce.data.mappers.*
import dev.fajar.starter.workforce.domain.entities.*
import dev.fajar.starter.workforce.domain.policies.sameWorkforceScope
import dev.fajar.starter.workforce.domain.repositories.WorkCalendarCacheRepository
import kotlin.io.encoding.Base64
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.koin.core.annotation.*

@Single
class EncryptedWorkCalendarCacheRepository(
    private val replicas: ReplicaStore,
    private val cipher: ContentCipher,
    @Named("calendar-content-key") private val credentials: CredentialStore,
) : WorkCalendarCacheRepository {
    private val keyCreation = Mutex()

    override fun observe(access: WorkforceAccess) =
        safeStorageFlow(
            replicas.observeDocument(
                ReplicaScope(access.sessionId),
                workCalendarPartition(access),
                "calendar",
            ),
            mapValue = { page ->
                val checkpoint = page.checkpoint
                if (checkpoint == null) null
                else {
                    require(checkpoint.published && checkpoint.revision == 0L)
                    val document = page.documents.single()
                    require(
                        document.id == "calendar" &&
                            document.sortKey == "calendar" &&
                            document.version == 0L
                    )
                    val key =
                        Base64.decode(
                            requireNotNull(credentials.read()) { "Calendar key is unavailable." }
                        )
                    try {
                        val header =
                            cipher.decrypt(
                                key,
                                workCalendarAssociatedData(
                                    access,
                                    checkpoint.handle.generation,
                                    "checkpoint",
                                ),
                                Base64.decode(checkpoint.payload),
                            )
                        try {
                            require(header.decodeToString(throwOnInvalidSequence = true) == "v1")
                        } finally {
                            header.fill(0)
                        }
                        val bytes =
                            cipher.decrypt(
                                key,
                                workCalendarAssociatedData(
                                    access,
                                    checkpoint.handle.generation,
                                    "calendar",
                                ),
                                Base64.decode(requireNotNull(document.payload)),
                            )
                        try {
                            Json.decodeFromString<WorkCalendarSnapshotDto>(
                                    bytes.decodeToString(throwOnInvalidSequence = true)
                                )
                                .toSnapshot(access, checkpoint.handle.generation)
                        } finally {
                            bytes.fill(0)
                        }
                    } finally {
                        key.fill(0)
                    }
                }
            },
        )

    override suspend fun generation(access: WorkforceAccess) = safeStorageCall {
        replicas
            .checkpoint(ReplicaScope(access.sessionId), workCalendarPartition(access))
            ?.handle
            ?.generation
    }

    override suspend fun replace(
        access: WorkforceAccess,
        expectedGeneration: String?,
        snapshot: WorkCalendarSnapshot?,
    ) = safeStorageCall {
        val partition = workCalendarPartition(access)
        val previous = replicas.checkpoint(ReplicaScope(access.sessionId), partition)
        if (previous?.handle?.generation != expectedGeneration) return@safeStorageCall false
        if (snapshot == null)
            return@safeStorageCall replicas.replace(
                ReplicaScope(access.sessionId),
                partition,
                previous,
                null,
            )
        require(
            sameWorkforceScope(access, snapshot.access) && snapshot.generation != expectedGeneration
        )
        val dto = snapshot.toDto()
        dto.toSnapshot(access, snapshot.generation)
        val key =
            keyCreation.withLock {
                val saved = credentials.read()
                if (saved != null) Base64.decode(saved)
                else {
                    check(previous == null) { "Existing calendar requires its content key." }
                    val generated = cipher.generateKey()
                    try {
                        withContext(NonCancellable) { credentials.write(Base64.encode(generated)) }
                        generated.copyOf()
                    } finally {
                        generated.fill(0)
                    }
                }
            }
        try {
            val bytes = Json.encodeToString(dto).encodeToByteArray()
            val document =
                try {
                    cipher.encrypt(
                        key,
                        workCalendarAssociatedData(access, snapshot.generation, "calendar"),
                        bytes,
                    )
                } finally {
                    bytes.fill(0)
                }
            val header =
                cipher.encrypt(
                    key,
                    workCalendarAssociatedData(access, snapshot.generation, "checkpoint"),
                    "v1".encodeToByteArray(),
                )
            replicas.replace(
                ReplicaScope(access.sessionId),
                partition,
                previous,
                ReplicaSnapshot(
                    snapshot.generation,
                    Base64.encode(header),
                    listOf(ReplicaDocument("calendar", 0, "calendar", Base64.encode(document))),
                ),
            )
        } finally {
            key.fill(0)
        }
    }
}
