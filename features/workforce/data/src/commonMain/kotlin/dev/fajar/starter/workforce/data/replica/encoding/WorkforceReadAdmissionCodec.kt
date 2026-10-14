@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.workforce.data.replica.encoding

import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.ReplicaCheckpoint
import dev.fajar.starter.securestorage.ContentCipher
import dev.fajar.starter.workforce.data.replica.dto.WorkforceReadAdmissionDto
import dev.fajar.starter.workforce.data.replica.mappers.workforceReplicaAssociatedData
import dev.fajar.starter.workforce.data.replica.mappers.workforceReplicaScope
import dev.fajar.starter.workforce.domain.entities.WorkforceAccess
import dev.fajar.starter.workforce.domain.replica.entities.WorkforceReadAdmission
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json

fun workforceAdmissionPartition(access: WorkforceAccess) = "workforce-access/" + access.companyId

fun workforceAdmissionBufferKey(access: WorkforceAccess) =
    Json.encodeToString(
        listOf(access.sessionId, access.audience, access.accountId, access.companyId)
    )

fun WorkforceReadAdmissionDto.toReadAdmission(): WorkforceReadAdmission {
    Uuid.parse(revision)
    require(code == null || code.length in 1..128)
    return WorkforceReadAdmission(
        revision,
        Failure(
            FailureKind.valueOf(kind),
            "Access to this work data requires verification.",
            code = code,
        ),
    )
}

suspend fun encodeWorkforceReadAdmission(
    cipher: ContentCipher,
    key: ByteArray,
    access: WorkforceAccess,
    dto: WorkforceReadAdmissionDto,
): String {
    val bytes = Json.encodeToString(dto).encodeToByteArray()
    return try {
        Base64.encode(
            cipher.encrypt(
                key,
                workforceReplicaAssociatedData(access, dto.revision, "read-admission", 0),
                bytes,
            )
        )
    } finally {
        bytes.fill(0)
    }
}

suspend fun decodeWorkforceReadAdmission(
    cipher: ContentCipher,
    key: ByteArray,
    access: WorkforceAccess,
    raw: ReplicaCheckpoint,
): WorkforceReadAdmissionDto {
    require(
        raw.handle.scope == workforceReplicaScope(access) &&
            raw.handle.partition == workforceAdmissionPartition(access)
    )
    require(raw.revision == 0L)
    val bytes =
        cipher.decrypt(
            key,
            workforceReplicaAssociatedData(access, raw.handle.generation, "read-admission", 0),
            Base64.decode(raw.payload),
        )
    return try {
        Json.decodeFromString<WorkforceReadAdmissionDto>(
                bytes.decodeToString(throwOnInvalidSequence = true)
            )
            .also {
                require(it.revision == raw.handle.generation)
                it.toReadAdmission()
            }
    } finally {
        bytes.fill(0)
    }
}
