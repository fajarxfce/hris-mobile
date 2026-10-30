package dev.fajar.starter.documents.domain.metadata.commands.entities

import dev.fajar.starter.common.result.Failure

sealed interface DocumentMetadataCommandOutcome {
    data object Pending : DocumentMetadataCommandOutcome

    /** Persist before HTTP: a missing response cannot establish whether the write committed. */
    data class Unconfirmed(val failure: Failure? = null) : DocumentMetadataCommandOutcome

    data class Rejected(val failure: Failure) : DocumentMetadataCommandOutcome

    /** The original receipt version, never an instruction to replace the current header. */
    data class Confirmed(val version: Long) : DocumentMetadataCommandOutcome
}
