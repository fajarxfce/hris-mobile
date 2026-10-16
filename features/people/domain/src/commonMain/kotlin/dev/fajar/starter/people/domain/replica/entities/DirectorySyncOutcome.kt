package dev.fajar.starter.people.domain.replica.entities

import dev.fajar.starter.workspace.domain.entities.CompanyDataSyncOutcome

data class DirectorySyncOutcome(override val caughtUp: Boolean, val pollAfterSeconds: Int) :
    CompanyDataSyncOutcome
