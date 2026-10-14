@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.expenses.data.replica.encoding

import dev.fajar.starter.common.result.*
import dev.fajar.starter.database.ReplicaCheckpoint
import dev.fajar.starter.expenses.data.replica.dto.ExpenseReadAdmissionDto
import dev.fajar.starter.expenses.data.replica.mappers.expenseReplicaAssociatedData
import dev.fajar.starter.expenses.data.replica.mappers.expenseReplicaScope
import dev.fajar.starter.expenses.domain.entities.ExpenseAccess
import dev.fajar.starter.expenses.domain.replica.entities.ExpenseReadAdmission
import dev.fajar.starter.securestorage.ContentCipher
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json

fun expenseAdmissionPartition(access: ExpenseAccess) = "expense-access/" + access.companyId

fun expenseAdmissionBufferKey(access: ExpenseAccess) =
    Json.encodeToString(
        listOf(access.sessionId, access.audience, access.accountId, access.companyId)
    )

fun ExpenseReadAdmissionDto.toReadAdmission(): ExpenseReadAdmission {
    Uuid.parse(revision)
    require(code == null || code.length in 1..128)
    return ExpenseReadAdmission(
        revision,
        Failure(
            FailureKind.valueOf(kind),
            "Access to these expenses requires verification.",
            code = code,
        ),
    )
}

suspend fun encodeExpenseReadAdmission(
    cipher: ContentCipher,
    key: ByteArray,
    access: ExpenseAccess,
    dto: ExpenseReadAdmissionDto,
): String {
    val bytes = Json.encodeToString(dto).encodeToByteArray()
    return try {
        Base64.encode(
            cipher.encrypt(
                key,
                expenseReplicaAssociatedData(access, dto.revision, "read-admission", 0),
                bytes,
            )
        )
    } finally {
        bytes.fill(0)
    }
}

suspend fun decodeExpenseReadAdmission(
    cipher: ContentCipher,
    key: ByteArray,
    access: ExpenseAccess,
    raw: ReplicaCheckpoint,
): ExpenseReadAdmissionDto {
    require(
        raw.handle.scope == expenseReplicaScope(access) &&
            raw.handle.partition == expenseAdmissionPartition(access)
    )
    require(raw.revision == 0L)
    val bytes =
        cipher.decrypt(
            key,
            expenseReplicaAssociatedData(access, raw.handle.generation, "read-admission", 0),
            Base64.decode(raw.payload),
        )
    return try {
        Json.decodeFromString<ExpenseReadAdmissionDto>(
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
