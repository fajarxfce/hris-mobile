package dev.fajar.starter.collaboration.domain.entities

/** Finite local-pass evidence for one global visit; no metadata cursor or write receipt. */
data class CollaborationHistoryRecoveryVisit(val moreHistoryPages: Boolean, val unresolved: Boolean)
