package dev.fajar.starter.collaboration.presentation.spacecontacts

import dev.fajar.starter.collaboration.domain.entities.CollaborationSpaceContact
import dev.fajar.starter.common.result.Failure

data class SpaceContactsState(
    val query: String = "",
    val appliedQuery: String = "",
    val contacts: List<CollaborationSpaceContact> = emptyList(),
    val loading: Boolean = true,
    val visible: Boolean = false,
    val failure: Failure? = null,
    val restriction: Failure? = null,
) {
    override fun toString() = "SpaceContactsState([redacted])"
}

sealed interface SpaceContactsEvent {
    data object Entered : SpaceContactsEvent

    data object Stopped : SpaceContactsEvent

    data class QueryChanged(val query: String) : SpaceContactsEvent

    data class ContactSelected(val accountId: String) : SpaceContactsEvent
}

sealed interface SpaceContactsEffect {
    data class OpenContact(val accountId: String) : SpaceContactsEffect
}
