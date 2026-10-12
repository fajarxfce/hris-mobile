package dev.fajar.starter.documents.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface DocumentUploadStepDto {
    @Serializable
    @SerialName("start")
    data class Start(val operationId: String) : DocumentUploadStepDto

    @Serializable
    @SerialName("chunk")
    data class Chunk(val operationId: String, val offset: Long, val version: Long) :
        DocumentUploadStepDto

    @Serializable
    @SerialName("validate")
    data class Validate(val operationId: String, val version: Long) : DocumentUploadStepDto

    @Serializable
    @SerialName("processing")
    data class Processing(val version: Long) : DocumentUploadStepDto

    @Serializable
    @SerialName("finished")
    data class Finished(val revision: DocumentProgressDto) : DocumentUploadStepDto

    @Serializable
    @SerialName("cancel")
    data class Cancel(val operationId: String, val version: Long, val reason: String) :
        DocumentUploadStepDto
}
