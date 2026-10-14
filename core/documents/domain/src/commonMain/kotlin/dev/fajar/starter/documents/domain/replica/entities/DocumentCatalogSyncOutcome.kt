package dev.fajar.starter.documents.domain.replica.entities

data class DocumentCatalogSyncOutcome(val caughtUp: Boolean, val pollAfterSeconds: Int)
