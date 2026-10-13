package dev.fajar.starter.workforce.data.overtime.repositories

import dev.fajar.starter.database.*
import dev.fajar.starter.securestorage.AtomicCredentialStore
import dev.fajar.starter.securestorage.ContentCipher
import dev.fajar.starter.storage.*
import dev.fajar.starter.workforce.data.overtime.dto.*
import dev.fajar.starter.workforce.data.overtime.encoding.*
import dev.fajar.starter.workforce.data.overtime.mappers.*
import dev.fajar.starter.workforce.domain.entities.WorkforceAccess
import dev.fajar.starter.workforce.domain.overtime.entities.*
import dev.fajar.starter.workforce.domain.overtime.repositories.OvertimeCacheRepository
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.Json
import org.koin.core.annotation.*

@Single
class EncryptedOvertimeCacheRepository(
    private val replicas: ReplicaStore,
    private val cipher: ContentCipher,
    @Named("workspace-content-key") private val credentials: AtomicCredentialStore,
) : OvertimeCacheRepository {

    override fun observe(access: WorkforceAccess) =
        safeStorageFlow(
            replicas.observeDocument(
                ReplicaScope(access.sessionId),
                overtimePartition(access, "overview"),
                "overview",
            ),
            mapValue = { page ->
                val checkpoint = page.checkpoint
                if (checkpoint == null) null
                else {
                    val key =
                        Base64.decode(requireNotNull(credentials.read())).also {
                            require(it.size == 32)
                        }
                    try {
                        val bytes = decodeOvertimeCache(page, access, "overview", cipher, key)
                        try {
                            require(bytes.size <= 96_000)
                            Json.decodeFromString<OvertimeSnapshotDto<OvertimeOverviewDto>>(
                                    bytes.decodeToString(throwOnInvalidSequence = true)
                                )
                                .toSnapshot(access, checkpoint.handle.generation) { value, owner ->
                                    value.toOverview(owner)
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
}
