package dev.fajar.starter.workforce.domain.replica.entities

data class WorkforceSyncOutcome(val caughtUp: Boolean, val pollAfterSeconds: Int)
