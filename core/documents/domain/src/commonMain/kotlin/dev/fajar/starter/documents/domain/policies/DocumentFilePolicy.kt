package dev.fajar.starter.documents.domain.policies

import dev.fajar.starter.common.result.*
import dev.fajar.starter.documents.domain.entities.*

val DOCUMENT_INPUT_TYPES = setOf("application/pdf", "image/jpeg", "image/png")
const val DOCUMENT_LOCAL_CAPACITY = 209_715_200L
const val DOCUMENT_LOCAL_FILES = 20

fun admitDocumentImport(access: AppResult<DocumentAccess>): AppResult<DocumentAccess> =
    when (access) {
        is AppResult.Failed -> access
        is AppResult.Success ->
            if ("documents.self.upload" in access.value.permissions) access
            else documentFailure("access_denied", FailureKind.AccessDenied)
    }

fun resolveDocumentFile(selection: DocumentFileSelection): AppResult<DocumentFileDeclaration> {
    val name = selection.name
    if (
        name.isNullOrBlank() ||
            name.length > 180 ||
            name.any { it.code in 0..31 || it.code in 127..159 || it == '/' || it == '\\' }
    )
        return documentFailure("client.document_file_name")
    val inferred =
        when (name.substringAfterLast('.', "").lowercase()) {
            "pdf" -> "application/pdf"
            "jpg",
            "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            else -> return documentFailure("client.document_file_type")
        }
    val declared = selection.mediaType?.lowercase()
    if (!declared.isNullOrBlank() && declared != "application/octet-stream" && declared != inferred)
        return documentFailure("client.document_file_type")
    val size = selection.size ?: return documentFailure("client.document_file_size_unknown")
    if (size !in 1..DOCUMENT_MAXIMUM_BYTES) return documentFailure("client.document_file_size")
    return AppResult.Success(DocumentFileDeclaration(name, inferred, size))
}
