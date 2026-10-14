package dev.fajar.starter.people.domain.profile.entities

import dev.fajar.starter.sync.domain.entities.SyncChange

data class SelfProfileSyncPage(
    val changes: List<SyncChange>,
    val phase: SelfProfileSyncPhase,
    val cursor: String,
    val hasMore: Boolean,
    val pendingPublication: Boolean,
    val pollAfterSeconds: Int,
)
