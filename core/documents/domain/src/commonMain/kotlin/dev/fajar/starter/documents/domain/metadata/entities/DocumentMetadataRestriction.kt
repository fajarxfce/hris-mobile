package dev.fajar.starter.documents.domain.metadata.entities

import dev.fajar.starter.common.result.Failure

/** Neutral recovery target; no cached document title, identity number or editable input. */
data class DocumentMetadataRestriction(val documentId: String, val failure: Failure) {
    override fun toString() = "DocumentMetadataRestriction([redacted])"
}
