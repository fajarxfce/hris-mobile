package dev.fajar.starter.leave.domain.entities

data class LeaveSyncOutcome(val caughtUp: Boolean, val pollAfterSeconds: Int)
