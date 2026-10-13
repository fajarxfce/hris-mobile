package dev.fajar.starter.payroll.data.dto

/** Raw headers; protocol validation and domain mapping belong to the repository. */
data class PayslipPdfHeadersDto(
    val entityTags: List<String>,
    val lengths: List<String>,
    val mediaTypes: List<String>,
    val encodings: List<String>,
)
