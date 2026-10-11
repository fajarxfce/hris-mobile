package dev.fajar.starter.inbox.domain.entities

data class InboxCommandPage(val items: List<InboxCommandRecord>, val nextCursor: String?)

enum class InboxCommandAdmission {
    STORED,
    OCCUPIED,
    CAPACITY_EXCEEDED,
    SCOPE_CHANGED,
}
