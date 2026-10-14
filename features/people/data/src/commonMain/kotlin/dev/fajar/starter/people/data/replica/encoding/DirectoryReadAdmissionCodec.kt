@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.people.data.replica.encoding

import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.ReplicaCheckpoint
import dev.fajar.starter.people.data.replica.dto.DirectoryReadAdmissionDto
import dev.fajar.starter.people.data.replica.mappers.directoryReplicaAssociatedData
import dev.fajar.starter.people.data.replica.mappers.directoryReplicaScope
import dev.fajar.starter.people.domain.replica.entities.DirectoryAccess
import dev.fajar.starter.people.domain.replica.entities.DirectoryReadAdmission
import dev.fajar.starter.securestorage.ContentCipher
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json

fun directoryAdmissionPartition(access: DirectoryAccess) = "directory-access/" + access.companyId

fun directoryAdmissionBufferKey(access: DirectoryAccess) =
    Json.encodeToString(listOf(access.audience, access.accountId, access.companyId))

fun DirectoryReadAdmissionDto.toReadAdmission(): DirectoryReadAdmission {
    Uuid.parse(revision)
    require(code == null || code.length in 1..128)
    return DirectoryReadAdmission(
        revision,
        Failure(
            FailureKind.valueOf(kind),
            "Access to the employee directory requires verification.",
            code = code,
        ),
    )
}

suspend fun encodeDirectoryReadAdmission(
    cipher: ContentCipher,
    key: ByteArray,
    access: DirectoryAccess,
    dto: DirectoryReadAdmissionDto,
): String {
    val bytes = Json.encodeToString(dto).encodeToByteArray()
    return try {
        Base64.encode(
            cipher.encrypt(
                key,
                directoryReplicaAssociatedData(access, dto.revision, "read-admission", 0),
                bytes,
            )
        )
    } finally {
        bytes.fill(0)
    }
}

suspend fun decodeDirectoryReadAdmission(
    cipher: ContentCipher,
    key: ByteArray,
    access: DirectoryAccess,
    raw: ReplicaCheckpoint,
): DirectoryReadAdmissionDto {
    require(
        raw.handle.scope == directoryReplicaScope(access) &&
            raw.handle.partition == directoryAdmissionPartition(access)
    )
    require(raw.revision == 0L)
    val bytes =
        cipher.decrypt(
            key,
            directoryReplicaAssociatedData(access, raw.handle.generation, "read-admission", 0),
            Base64.decode(raw.payload),
        )
    return try {
        Json.decodeFromString<DirectoryReadAdmissionDto>(
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
