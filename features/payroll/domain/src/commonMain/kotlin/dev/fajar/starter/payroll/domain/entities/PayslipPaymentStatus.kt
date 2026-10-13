package dev.fajar.starter.payroll.domain.entities

enum class PayslipPaymentStatus {
    PREPARED,
    PENDING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
}
