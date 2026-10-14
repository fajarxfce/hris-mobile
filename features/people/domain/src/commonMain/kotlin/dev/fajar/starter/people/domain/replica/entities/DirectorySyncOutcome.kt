package dev.fajar.starter.people.domain.replica.entities

data class DirectorySyncOutcome(val caughtUp: Boolean, val pollAfterSeconds: Int)
