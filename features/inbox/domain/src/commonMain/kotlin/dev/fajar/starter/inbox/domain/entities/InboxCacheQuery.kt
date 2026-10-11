package dev.fajar.starter.inbox.domain.entities

sealed interface InboxCacheQuery {
    data class Page(val after: String? = null) : InboxCacheQuery

    data class Item(val id: String) : InboxCacheQuery
}
