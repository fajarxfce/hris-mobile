package dev.fajar.starter.collaboration.presentation.spacecontacts

import dev.fajar.starter.collaboration.domain.entities.CollaborationSpaceContact
import dev.fajar.starter.common.result.Failure

enum class SpaceContactsSurface {
    Directory,
    Search,
}

data class SpaceContactsState(
    val query: String = "",
    val appliedQuery: String = "",
    val contacts: List<CollaborationSpaceContact> = emptyList(),
    val searchResults: List<CollaborationSpaceContact> = emptyList(),
    val loading: Boolean = true,
    val visible: SpaceContactsSurface? = null,
    val failure: Failure? = null,
    val restriction: Failure? = null,
) {
    override fun toString() = "SpaceContactsState([redacted])"
}

sealed interface SpaceContactsEvent {
    data class Entered(val surface: SpaceContactsSurface) : SpaceContactsEvent

    data class Stopped(val surface: SpaceContactsSurface) : SpaceContactsEvent

    data class QueryChanged(val query: String) : SpaceContactsEvent

    data class ContactSelected(val accountId: String, val surface: SpaceContactsSurface) :
        SpaceContactsEvent
}

sealed interface SpaceContactsEffect {
    data class OpenContact(val accountId: String, val surface: SpaceContactsSurface) :
        SpaceContactsEffect
}
