package dev.fajar.starter.documents.presentation.editor

import dev.fajar.starter.common.result.Failure
import dev.fajar.starter.documents.domain.entities.DocumentMetadataInput
import dev.fajar.starter.documents.domain.metadata.working.entities.DocumentMetadataWorkingCopy
import kotlin.time.Instant

/** UI edit sequence is independent of the serial storage revision. */
data class DocumentMetadataEditPersistence(
    val stored: DocumentMetadataWorkingCopy,
    val input: DocumentMetadataInput = stored.input,
    val reason: String = stored.reason,
    val sequence: Long = 0,
    val acknowledged: Long = 0,
    val pending: DocumentMetadataLocalMutation? = null,
    val writing: Boolean = false,
    val failure: Failure? = null,
) {
    val saved
        get() = sequence == acknowledged && pending == null && failure == null

    fun edit(input: DocumentMetadataInput = this.input, reason: String = this.reason) =
        if (input == this.input && reason == this.reason) this
        else copy(input = input, reason = reason, sequence = sequence + 1)

    fun mutation(now: Instant) =
        pending
            ?: DocumentMetadataLocalMutation(
                stored,
                stored.copy(
                    revision = stored.revision + 1,
                    input = input,
                    reason = reason,
                    updatedAt = now,
                ),
                sequence,
            )

    fun acknowledge(value: DocumentMetadataWorkingCopy, mutation: DocumentMetadataLocalMutation) =
        if (pending != mutation || value != mutation.next) this
        else
            copy(
                stored = value,
                acknowledged = mutation.sequence,
                pending = null,
                writing = false,
                failure = failure?.takeIf { it.code == "client.document_working_copy_changed" },
            )

    override fun toString() = "DocumentMetadataEditPersistence([redacted])"
}

data class DocumentMetadataLocalMutation(
    val expected: DocumentMetadataWorkingCopy,
    val next: DocumentMetadataWorkingCopy,
    val sequence: Long,
)
