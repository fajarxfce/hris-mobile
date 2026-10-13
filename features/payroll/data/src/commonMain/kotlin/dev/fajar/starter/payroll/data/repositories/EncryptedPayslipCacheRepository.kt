package dev.fajar.starter.payroll.data.repositories

import dev.fajar.starter.database.*
import dev.fajar.starter.payroll.data.dto.*
import dev.fajar.starter.payroll.data.encoding.*
import dev.fajar.starter.payroll.data.mappers.*
import dev.fajar.starter.payroll.domain.entities.*
import dev.fajar.starter.payroll.domain.policies.samePayrollScope
import dev.fajar.starter.payroll.domain.repositories.PayslipCacheRepository
import dev.fajar.starter.securestorage.AtomicCredentialStore
import dev.fajar.starter.securestorage.ContentCipher
import dev.fajar.starter.storage.*
import kotlin.io.encoding.Base64
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import org.koin.core.annotation.*

@Single
class EncryptedPayslipCacheRepository(
    private val replicas: ReplicaStore,
    private val cipher: ContentCipher,
    @Named("workspace-content-key") private val credentials: AtomicCredentialStore,
) : PayslipCacheRepository {
    override fun observe(access: PayrollAccess) =
        safeStorageFlow(
            replicas.observeDocument(
                ReplicaScope(access.sessionId),
                payslipPartition(access, "overview"),
                "overview",
            ),
            mapValue = { page ->
                val checkpoint = page.checkpoint
                if (checkpoint == null) null
                else {
                    val key = Base64.decode(requireNotNull(credentials.read()))
                    try {
                        require(key.size == 32)
                        val bytes = decodePayslipCache(page, access, "overview", cipher, key)
                        try {
                            require(bytes.size <= PAYSLIP_CACHE_MAXIMUM_BYTES)
                            Json.decodeFromString<PayslipSnapshotDto<PayslipOverviewDto>>(
                                    bytes.decodeToString(throwOnInvalidSequence = true)
                                )
                                .toSnapshot(access, checkpoint.handle.generation) {
                                    it.toOverview()
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

    override suspend fun generation(access: PayrollAccess) = safeStorageCall {
        replicas
            .checkpoint(ReplicaScope(access.sessionId), payslipPartition(access, "overview"))
            ?.handle
            ?.generation
    }

    override suspend fun replace(
        access: PayrollAccess,
        expectedGeneration: String?,
        snapshot: PayslipSnapshot<PayslipOverview>?,
    ) = safeStorageCall {
        val partition = payslipPartition(access, "overview")
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
            samePayrollScope(access, snapshot.access) && snapshot.generation != expectedGeneration
        )
        val dto = snapshot.toDto { it.toDto() }
        dto.toSnapshot(access, snapshot.generation) { it.toOverview() }
        val key = Base64.decode(requireNotNull(credentials.read()))
        try {
            require(key.size == 32)
            val bytes = Json.encodeToString(dto).encodeToByteArray()
            val encoded =
                try {
                    encodePayslipCache(bytes, access, snapshot.generation, "overview", cipher, key)
                } finally {
                    bytes.fill(0)
                }
            currentCoroutineContext().ensureActive()
            replicas.replace(ReplicaScope(access.sessionId), partition, previous, encoded)
        } finally {
            key.fill(0)
        }
    }

    override fun observeDetails(access: PayrollAccess) =
        safeStorageFlow(
            replicas.observeDocument(
                ReplicaScope(access.sessionId),
                payslipPartition(access, "details"),
                "details",
            ),
            mapValue = { page ->
                val checkpoint = page.checkpoint
                if (checkpoint == null) null
                else {
                    val key = Base64.decode(requireNotNull(credentials.read()))
                    try {
                        require(key.size == 32)
                        val bytes = decodePayslipCache(page, access, "details", cipher, key)
                        try {
                            require(bytes.size <= PAYSLIP_CACHE_MAXIMUM_BYTES)
                            Json.decodeFromString<PayslipSnapshotDto<PayslipViewDto>>(
                                    bytes.decodeToString(throwOnInvalidSequence = true)
                                )
                                .toSnapshot(access, checkpoint.handle.generation) { it.toView() }
                        } finally {
                            bytes.fill(0)
                        }
                    } finally {
                        key.fill(0)
                    }
                }
            },
        )

    override suspend fun detailsGeneration(access: PayrollAccess) = safeStorageCall {
        replicas
            .checkpoint(ReplicaScope(access.sessionId), payslipPartition(access, "details"))
            ?.handle
            ?.generation
    }

    override suspend fun replaceDetails(
        access: PayrollAccess,
        expectedGeneration: String?,
        snapshot: PayslipSnapshot<PayslipView>?,
    ) = safeStorageCall {
        val partition = payslipPartition(access, "details")
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
            samePayrollScope(access, snapshot.access) && snapshot.generation != expectedGeneration
        )
        val dto = snapshot.toDto { it.toDto() }
        dto.toSnapshot(access, snapshot.generation) { it.toView() }
        val key = Base64.decode(requireNotNull(credentials.read()))
        try {
            require(key.size == 32)
            val bytes = Json.encodeToString(dto).encodeToByteArray()
            val encoded =
                try {
                    encodePayslipCache(bytes, access, snapshot.generation, "details", cipher, key)
                } finally {
                    bytes.fill(0)
                }
            currentCoroutineContext().ensureActive()
            replicas.replace(ReplicaScope(access.sessionId), partition, previous, encoded)
        } finally {
            key.fill(0)
        }
    }
}
