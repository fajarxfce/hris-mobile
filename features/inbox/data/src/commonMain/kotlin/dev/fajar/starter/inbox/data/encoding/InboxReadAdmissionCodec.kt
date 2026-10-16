@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.inbox.data.encoding

import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.ReplicaCheckpoint
import dev.fajar.starter.inbox.data.dto.InboxReadAdmissionDto
import dev.fajar.starter.inbox.data.mappers.*
import dev.fajar.starter.inbox.domain.entities.*
import dev.fajar.starter.securestorage.ContentCipher
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json

fun inboxAdmissionPartition(access: InboxAccess) = "inbox/${access.companyId}/read-admission"

fun inboxAdmissionBufferKey(access: InboxAccess) =
    Json.encodeToString(listOf(access.audience, access.accountId, access.companyId))

fun InboxReadAdmissionDto.toReadAdmission(): InboxReadAdmission {
    require(Uuid.parse(revision).toString() == revision)
    require(code == null || code.length in 1..128)
    return InboxReadAdmission(
        revision,
        Failure(FailureKind.valueOf(kind), "Inbox access requires verification.", code = code),
    )
}

suspend fun encodeInboxReadAdmission(
    cipher: ContentCipher,
    key: ByteArray,
    access: InboxAccess,
    dto: InboxReadAdmissionDto,
): String {
    dto.toReadAdmission()
    val bytes = Json.encodeToString(dto).encodeToByteArray()
    return try {
        Base64.encode(
            cipher.encrypt(
                key,
                inboxAssociatedData(access, dto.revision, "read-admission", "", 0),
                bytes,
            )
        )
    } finally {
        bytes.fill(0)
    }
}

suspend fun decodeInboxReadAdmission(
    cipher: ContentCipher,
    key: ByteArray,
    access: InboxAccess,
    raw: ReplicaCheckpoint,
): InboxReadAdmissionDto {
    require(
        raw.handle.scope == inboxReplicaScope(access) &&
            raw.handle.partition == inboxAdmissionPartition(access)
    )
    require(raw.published && raw.revision == 0L && raw.payload.length in 1..4_096)
    val bytes =
        cipher.decrypt(
            key,
            inboxAssociatedData(access, raw.handle.generation, "read-admission", "", 0),
            Base64.decode(raw.payload),
        )
    return try {
        Json.decodeFromString<InboxReadAdmissionDto>(
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
