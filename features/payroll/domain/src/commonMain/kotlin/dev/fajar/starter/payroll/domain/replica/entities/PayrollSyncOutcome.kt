package dev.fajar.starter.payroll.domain.replica.entities

data class PayrollSyncOutcome(val caughtUp: Boolean, val pollAfterSeconds: Int)
