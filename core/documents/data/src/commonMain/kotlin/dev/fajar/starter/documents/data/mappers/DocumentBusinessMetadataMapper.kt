package dev.fajar.starter.documents.data.mappers

import dev.fajar.starter.documents.data.dto.DocumentBusinessMetadataDto
import dev.fajar.starter.documents.domain.entities.DocumentBusinessMetadata
import dev.fajar.starter.documents.domain.entities.DocumentCategory
import kotlinx.datetime.LocalDate

fun DocumentBusinessMetadataDto.toBusinessMetadata(): DocumentBusinessMetadata {
    require(
        identityNumber == null ||
            (identityNumber.isNotBlank() &&
                identityNumber.length <= 120 &&
                identityNumber == identityNumber.trim() &&
                identityNumber.none { it.code in 0..31 || it.code in 127..159 })
    )
    require(
        issuingCountry == null ||
            (issuingCountry.length == 2 && issuingCountry.all { it in 'A'..'Z' })
    )
    val issued = issuedOn?.let(::documentBusinessDate)
    val until = validUntil?.let(::documentBusinessDate)
    require(issued == null || until == null || until >= issued)
    return DocumentBusinessMetadata(
        DocumentCategory.valueOf(category),
        identityNumber,
        issuingCountry,
        issued,
        until,
    )
}

fun DocumentBusinessMetadata.toBusinessMetadataDto() =
    DocumentBusinessMetadataDto(
        category.name,
        identityNumber,
        issuingCountry,
        issuedOn?.toString(),
        validUntil?.toString(),
    )

private fun documentBusinessDate(value: String): LocalDate {
    require(value.length == 10)
    return LocalDate.parse(value).also { require(it.year in 1900..2200 && it.toString() == value) }
}
