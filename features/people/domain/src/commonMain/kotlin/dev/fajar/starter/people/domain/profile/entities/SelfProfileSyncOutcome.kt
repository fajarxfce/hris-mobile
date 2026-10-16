package dev.fajar.starter.people.domain.profile.entities

import dev.fajar.starter.workspace.domain.entities.CompanyDataSyncOutcome

data class SelfProfileSyncOutcome(override val caughtUp: Boolean, val pollAfterSeconds: Int) :
    CompanyDataSyncOutcome
