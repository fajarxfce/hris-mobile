package dev.fajar.starter.collaboration.domain.entities

/** A finite preparation result; waiting never authorizes SDK entry or publication. */
enum class CollaborationRoomReadiness {
    READY,
    WAITING,
}
