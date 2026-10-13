package dev.fajar.starter.payroll.domain.entities

/** A null year selects the current company-local year on first acquisition. */
data class PayslipSelection(val year: Int? = null, val after: String? = null)
