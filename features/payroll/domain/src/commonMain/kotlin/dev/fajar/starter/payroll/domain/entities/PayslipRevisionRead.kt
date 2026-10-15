package dev.fajar.starter.payroll.domain.entities

/** A known current slip can exist before the requested historical statement is acquired. */
data class PayslipRevisionRead(
    val currentRevision: Long,
    val snapshot: PayslipSnapshot<PayslipView>?,
)
