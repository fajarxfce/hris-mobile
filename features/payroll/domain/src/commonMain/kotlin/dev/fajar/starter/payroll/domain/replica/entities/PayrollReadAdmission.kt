package dev.fajar.starter.payroll.domain.replica.entities

import dev.fajar.starter.common.result.Failure

/** A confirmed projection denial is separate from cursor retention and data freshness. */
data class PayrollReadAdmission(val revision: String?, val denial: Failure?)
