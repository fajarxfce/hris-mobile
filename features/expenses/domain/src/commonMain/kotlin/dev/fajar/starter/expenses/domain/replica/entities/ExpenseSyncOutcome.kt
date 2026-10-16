package dev.fajar.starter.expenses.domain.replica.entities

import dev.fajar.starter.workspace.domain.entities.CompanyDataSyncOutcome

data class ExpenseSyncOutcome(override val caughtUp: Boolean, val pollAfterSeconds: Int) :
    CompanyDataSyncOutcome
