package dev.fajar.starter.documents.domain.entities

import dev.fajar.starter.common.result.Failure
import kotlin.time.Instant

data class DocumentUpload(
    val intent: DocumentUploadIntent,
    val step: DocumentUploadStep,
    val attempts: Int = 0,
    val nextAttemptAt: Instant? = null,
    val failure: Failure? = null,
)
