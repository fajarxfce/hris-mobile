package dev.fajar.starter.leave.domain.entities

import dev.fajar.starter.workspace.domain.entities.CompanyDataSyncOutcome

data class LeaveSyncOutcome(override val caughtUp: Boolean, val pollAfterSeconds: Int) :
    CompanyDataSyncOutcome
