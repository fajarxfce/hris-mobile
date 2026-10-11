@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.sync.data.mappers

import dev.fajar.starter.sync.data.dto.*
import dev.fajar.starter.sync.domain.entities.*
import kotlin.time.Instant
import kotlin.uuid.Uuid

fun validateSyncCursor(cursor: String) {
    require(cursor.length in 1..8192 && cursor.none { it.isWhitespace() || it.code < 32 })
}

fun SyncResourceDto.toReference(selection: Set<SyncCollection>): SyncReference {
    val kind = SyncCollection.valueOf(collection)
    require(kind in selection && version in 0..9_007_199_254_740_991L)
    require(Uuid.parse(id).toString() == id)
    return SyncReference(kind, id, version)
}

fun SyncBootstrapDto.toPage(
    selection: Set<SyncCollection>,
    previousCursor: String?,
    limit: Int,
): SyncBootstrapPage {
    val effective = collections.map(SyncCollection::valueOf).toSet()
    require(
        effective.isNotEmpty() &&
            effective.size == collections.size &&
            selection.containsAll(effective)
    )
    require(
        items.size <= limit && items.map { it.collection to it.id }.distinct().size == items.size
    )
    require((nextCursor == null) != (changesCursor == null))
    nextCursor?.let {
        validateSyncCursor(it)
        require(it != previousCursor && items.isNotEmpty())
    }
    changesCursor?.let(::validateSyncCursor)
    return SyncBootstrapPage(
        effective,
        items.map { it.toReference(effective) },
        nextCursor,
        changesCursor,
        Instant.parse(serverTime),
    )
}

fun SyncChangesDto.toPage(
    selection: Set<SyncCollection>,
    previousCursor: String,
    limit: Int,
): SyncChangePage {
    require(items.size <= limit && pollAfterSeconds in 1..3600)
    validateSyncCursor(cursor)
    require(!hasMore || (cursor != previousCursor && items.isNotEmpty()))
    return SyncChangePage(
        items.map {
            SyncChange(
                SyncResourceDto(it.collection, it.id, it.version).toReference(selection),
                SyncOperation.valueOf(it.operation),
            )
        },
        cursor,
        hasMore,
        pendingPublication,
        pollAfterSeconds,
        Instant.parse(serverTime),
    )
}
