package dev.fajar.starter.workforce.domain.overtime.entities

data class OvertimeSyncOutcome(val caughtUp: Boolean, val pollAfterSeconds: Int)
