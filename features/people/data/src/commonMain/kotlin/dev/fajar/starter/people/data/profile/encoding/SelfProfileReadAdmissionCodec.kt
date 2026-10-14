@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.people.data.profile.encoding

import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.ReplicaCheckpoint
import dev.fajar.starter.people.data.profile.dto.SelfProfileReadAdmissionDto
import dev.fajar.starter.people.data.profile.mappers.selfProfileReplicaAssociatedData
import dev.fajar.starter.people.data.profile.mappers.selfProfileReplicaScope
import dev.fajar.starter.people.domain.profile.entities.SelfProfileAccess
import dev.fajar.starter.people.domain.profile.entities.SelfProfileReadAdmission
import dev.fajar.starter.securestorage.ContentCipher
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json

fun selfProfileAdmissionPartition(access: SelfProfileAccess) =
    "self-profile-access/" + access.companyId

fun selfProfileAdmissionBufferKey(access: SelfProfileAccess) =
    Json.encodeToString(listOf(access.audience, access.accountId, access.companyId))

fun SelfProfileReadAdmissionDto.toReadAdmission(): SelfProfileReadAdmission {
    Uuid.parse(revision)
    require(code == null || code.length in 1..128)
    return SelfProfileReadAdmission(
        revision,
        Failure(
            FailureKind.valueOf(kind),
            "Access to your profile requires verification.",
            code = code,
        ),
    )
}

suspend fun encodeSelfProfileReadAdmission(
    cipher: ContentCipher,
    key: ByteArray,
    access: SelfProfileAccess,
    dto: SelfProfileReadAdmissionDto,
): String {
    val bytes = Json.encodeToString(dto).encodeToByteArray()
    return try {
        Base64.encode(
            cipher.encrypt(
                key,
                selfProfileReplicaAssociatedData(access, dto.revision, "read-admission", 0),
                bytes,
            )
        )
    } finally {
        bytes.fill(0)
    }
}

suspend fun decodeSelfProfileReadAdmission(
    cipher: ContentCipher,
    key: ByteArray,
    access: SelfProfileAccess,
    raw: ReplicaCheckpoint,
): SelfProfileReadAdmissionDto {
    require(
        raw.handle.scope == selfProfileReplicaScope(access) &&
            raw.handle.partition == selfProfileAdmissionPartition(access)
    )
    require(raw.revision == 0L)
    val bytes =
        cipher.decrypt(
            key,
            selfProfileReplicaAssociatedData(access, raw.handle.generation, "read-admission", 0),
            Base64.decode(raw.payload),
        )
    return try {
        Json.decodeFromString<SelfProfileReadAdmissionDto>(
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
