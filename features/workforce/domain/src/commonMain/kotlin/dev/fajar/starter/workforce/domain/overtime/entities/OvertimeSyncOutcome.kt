package dev.fajar.starter.workforce.domain.overtime.entities

import dev.fajar.starter.workspace.domain.entities.CompanyDataSyncOutcome

data class OvertimeSyncOutcome(override val caughtUp: Boolean, val pollAfterSeconds: Int) :
    CompanyDataSyncOutcome
