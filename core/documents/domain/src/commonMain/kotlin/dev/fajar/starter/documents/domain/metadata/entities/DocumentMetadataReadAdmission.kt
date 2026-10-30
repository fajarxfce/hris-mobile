package dev.fajar.starter.documents.domain.metadata.entities

import dev.fajar.starter.common.result.Failure

data class DocumentMetadataReadAdmission(val revision: String?, val denial: Failure?)
