@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.workforce.data.overtime.working.encoding

import dev.fajar.starter.database.ReplicaCheckpoint
import dev.fajar.starter.database.ReplicaSnapshot
import dev.fajar.starter.securestorage.ContentCipher
import dev.fajar.starter.workforce.data.overtime.mappers.overtimeReplicaAssociatedData
import dev.fajar.starter.workforce.data.overtime.mappers.overtimeReplicaScope
import dev.fajar.starter.workforce.data.overtime.working.dto.OvertimeActionWorkingCopyDto
import dev.fajar.starter.workforce.data.overtime.working.mappers.*
import dev.fajar.starter.workforce.domain.entities.WorkforceAccess
import dev.fajar.starter.workforce.domain.overtime.entities.*
import dev.fajar.starter.workforce.domain.overtime.working.entities.OvertimeActionWorkingCopy
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json

fun overtimeActionWorkingPartition(
    access: WorkforceAccess,
    selection: OvertimeRequestSelection,
    action: OvertimeAction,
): String {
    Uuid.parse(selection.requestId)
    Uuid.parse(selection.employeeId)
    require(action in setOf(OvertimeAction.SUBMIT_ACTUAL, OvertimeAction.WITHDRAW))
    return "overtime-replica/${access.companyId}/working/action/${selection.employeeId}/${selection.requestId}/${action.name}"
}

suspend fun encodeOvertimeActionWorkingCopy(
    access: WorkforceAccess,
    copy: OvertimeActionWorkingCopy,
    cipher: ContentCipher,
    key: ByteArray,
): ReplicaSnapshot {
    val bytes = Json.encodeToString(copy.toDto()).encodeToByteArray()
    return try {
        require(bytes.size <= 32_768)
        ReplicaSnapshot(
            copy.revision,
            Base64.encode(
                cipher.encrypt(
                    key,
                    overtimeReplicaAssociatedData(
                        access,
                        copy.revision,
                        "working/action/${copy.employeeId}/${copy.requestId}/${copy.action.name}",
                        0,
                    ),
                    bytes,
                )
            ),
            emptyList(),
        )
    } finally {
        bytes.fill(0)
    }
}

suspend fun decodeOvertimeActionWorkingCopy(
    access: WorkforceAccess,
    selection: OvertimeRequestSelection,
    action: OvertimeAction,
    checkpoint: ReplicaCheckpoint,
    cipher: ContentCipher,
    key: ByteArray,
): OvertimeActionWorkingCopy {
    require(
        checkpoint.handle.scope == overtimeReplicaScope(access) &&
            checkpoint.handle.partition == overtimeActionWorkingPartition(access, selection, action)
    )
    require(
        checkpoint.published && checkpoint.revision == 0L && checkpoint.payload.length in 1..65_536
    )
    val bytes =
        cipher.decrypt(
            key,
            overtimeReplicaAssociatedData(
                access,
                checkpoint.handle.generation,
                "working/action/${selection.employeeId}/${selection.requestId}/${action.name}",
                0,
            ),
            Base64.decode(checkpoint.payload),
        )
    return try {
        require(bytes.size <= 32_768)
        Json.decodeFromString<OvertimeActionWorkingCopyDto>(
                bytes.decodeToString(throwOnInvalidSequence = true)
            )
            .toWorkingCopy()
            .also {
                require(
                    it.requestId == selection.requestId &&
                        it.employeeId == selection.employeeId &&
                        it.action == action &&
                        it.revision == checkpoint.handle.generation
                )
            }
    } finally {
        bytes.fill(0)
    }
}
