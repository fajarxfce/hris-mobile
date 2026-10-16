package dev.fajar.starter.documents.domain.replica.entities

import dev.fajar.starter.workspace.domain.entities.CompanyDataSyncOutcome

data class DocumentCatalogSyncOutcome(override val caughtUp: Boolean, val pollAfterSeconds: Int) :
    CompanyDataSyncOutcome
