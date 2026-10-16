package dev.fajar.starter.workforce.domain.replica.entities

import dev.fajar.starter.workspace.domain.entities.CompanyDataSyncOutcome

data class WorkforceSyncOutcome(override val caughtUp: Boolean, val pollAfterSeconds: Int) :
    CompanyDataSyncOutcome
