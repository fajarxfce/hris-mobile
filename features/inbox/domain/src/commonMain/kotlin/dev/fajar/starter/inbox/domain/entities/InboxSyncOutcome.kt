package dev.fajar.starter.inbox.domain.entities

data class InboxSyncOutcome(val caughtUp: Boolean, val pollAfterSeconds: Int)
