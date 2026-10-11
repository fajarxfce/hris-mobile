@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package dev.fajar.starter.inbox.data.mappers

import dev.fajar.starter.inbox.data.dto.InboxItemDto
import dev.fajar.starter.inbox.domain.entities.InboxItem
import kotlin.time.Instant
import kotlin.uuid.Uuid

fun InboxItemDto.toItem(expectedId: String): InboxItem {
    require(id == expectedId)
    listOf(id, announcementId, publicationId).forEach { require(Uuid.parse(it).toString() == it) }
    require(version in 0..9_007_199_254_740_991L)
    require(title.isNotBlank() && title.length <= 200 && body.length <= 16_000)
    val delivered = Instant.parse(deliveredAt)
    require(delivered.toEpochMilliseconds() in 0 until 253_402_300_800_000L)
    val read = readAt?.let(Instant::parse)
    val acknowledged = acknowledgedAt?.let(Instant::parse)
    require(read == null || read >= delivered)
    require(
        acknowledged == null ||
            (acknowledgementRequired && read != null && acknowledged >= delivered)
    )
    return InboxItem(
        id,
        announcementId,
        publicationId,
        version,
        title,
        body,
        acknowledgementRequired,
        delivered,
        read,
        acknowledged,
    )
}

fun InboxItem.toDto() =
    InboxItemDto(
        id,
        announcementId,
        publicationId,
        version,
        title,
        body,
        acknowledgementRequired,
        deliveredAt.toString(),
        readAt?.toString(),
        acknowledgedAt?.toString(),
    )

fun InboxItem.sortKey(): String =
    (253_402_300_800_000L - deliveredAt.toEpochMilliseconds()).toString().padStart(15, '0') +
        "/" +
        id
