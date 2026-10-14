package dev.fajar.starter.people.domain.profile.entities

data class SelfProfileSyncOutcome(val caughtUp: Boolean, val pollAfterSeconds: Int)
