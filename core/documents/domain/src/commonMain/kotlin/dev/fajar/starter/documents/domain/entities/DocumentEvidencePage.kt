package dev.fajar.starter.documents.domain.entities

data class DocumentEvidencePage(val items: List<DocumentEvidence>, val nextCursor: String? = null)
