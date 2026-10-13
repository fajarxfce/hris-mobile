package dev.fajar.starter.payroll.domain.entities

data class PayslipPdfMetadata(
    val payslipId: String,
    val language: PayslipDocumentLanguage,
    val size: Long,
    val entityTag: String,
)
