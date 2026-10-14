package dev.fajar.starter.payroll.domain.replica.entities

import dev.fajar.starter.payroll.domain.entities.PayslipView

data class PayrollReplicaDetails(val checkpoint: PayrollCheckpoint?, val view: PayslipView?)
