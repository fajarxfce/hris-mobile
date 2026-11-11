package dev.fajar.starter.collaboration.domain.policies

import dev.fajar.starter.collaboration.domain.entities.*

/** Exact retained event identity, independent of the owner's admission and pending seek. */
fun matchesCollaborationRetainedOriginal(
    event: CollaborationProviderHistoryEvent,
    fact: CollaborationHistoryEventFact,
): Boolean =
    validCollaborationProviderEvent(event) &&
        fact.eventId == event.eventId &&
        fact.sender == event.sender &&
        (fact.timestamp == event.timestamp || (fact.timestamp == null && fact.redacted))

/** A redacted replacement still retains its own exact provider timestamp. */
fun matchesCollaborationRetainedReplacement(
    event: CollaborationProviderHistoryEvent,
    fact: CollaborationReplacementEventFact,
): Boolean =
    validCollaborationProviderEvent(event) &&
        fact.eventId == event.eventId &&
        fact.sender == event.sender &&
        fact.timestamp == event.timestamp
