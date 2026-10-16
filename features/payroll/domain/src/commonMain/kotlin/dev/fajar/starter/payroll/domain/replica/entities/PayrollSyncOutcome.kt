package dev.fajar.starter.payroll.domain.replica.entities

import dev.fajar.starter.workspace.domain.entities.CompanyDataSyncOutcome

data class PayrollSyncOutcome(override val caughtUp: Boolean, val pollAfterSeconds: Int) :
    CompanyDataSyncOutcome
