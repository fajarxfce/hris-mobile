package dev.fajar.starter.inbox.domain.entities

import dev.fajar.starter.workspace.domain.entities.CompanyDataSyncOutcome

data class InboxSyncOutcome(override val caughtUp: Boolean, val pollAfterSeconds: Int) :
    CompanyDataSyncOutcome
