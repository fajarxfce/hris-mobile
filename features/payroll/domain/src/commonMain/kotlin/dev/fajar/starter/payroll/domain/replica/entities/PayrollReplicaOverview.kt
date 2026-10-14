package dev.fajar.starter.payroll.domain.replica.entities

import dev.fajar.starter.payroll.domain.entities.PayslipPage

data class PayrollReplicaOverview(val checkpoint: PayrollCheckpoint?, val page: PayslipPage)
