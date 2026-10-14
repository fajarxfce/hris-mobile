package dev.fajar.starter.documents.domain.replica.entities

import dev.fajar.starter.common.result.Failure

data class DocumentCatalogReadAdmission(val revision: String?, val denial: Failure?)
