package dev.fajar.starter.documents.domain.repositories

import dev.fajar.starter.common.result.AppResult
import dev.fajar.starter.documents.domain.entities.DocumentFileDeclaration

/** A selected destination has one owner; false is explicit picker cancellation. */
interface DocumentOutputRepository {
    suspend fun select(requestId: String, declaration: DocumentFileDeclaration): AppResult<Boolean>

    suspend fun write(requestId: String, offset: Long, bytes: ByteArray): AppResult<Unit>

    suspend fun complete(requestId: String): AppResult<Unit>

    suspend fun release(requestId: String): AppResult<Unit>
}
