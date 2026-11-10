package dev.fajar.starter.collaboration.domain.entities

enum class CollaborationSpaceIncomingScanReason {
    /** A newly admitted content/overflow signal requires a subsequent inventory pass. */
    Refresh,

    /** Foreground entry resumes unfinished work; only an idle queue needs a new pass. */
    Startup,

    /** A warm foreground return wakes existing work without creating an idle inventory pass. */
    Resume,
}
