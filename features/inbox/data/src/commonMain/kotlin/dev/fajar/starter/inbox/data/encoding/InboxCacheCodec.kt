package dev.fajar.starter.inbox.data.encoding

import dev.fajar.starter.database.ReplicaCheckpoint
import dev.fajar.starter.database.ReplicaPage
import dev.fajar.starter.inbox.data.dto.InboxCheckpointDto
import dev.fajar.starter.inbox.data.dto.InboxItemDto
import dev.fajar.starter.inbox.data.mappers.*
import dev.fajar.starter.inbox.domain.entities.*
import dev.fajar.starter.securestorage.ContentCipher
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.Json

/**
 * Decodes an authenticated storage envelope; acquisition and key lifetime belong to the repository.
 */
suspend fun decodeInboxCheckpoint(
    cipher: ContentCipher,
    key: ByteArray,
    access: InboxAccess,
    raw: ReplicaCheckpoint,
): InboxCheckpoint {
    val bytes =
        cipher.decrypt(
            key,
            inboxAssociatedData(
                access,
                raw.handle.generation,
                "checkpoint",
                "",
                raw.revision,
                legacySessionBound = raw.handle.scope.accountKey == null,
            ),
            Base64.decode(raw.payload),
        )
    return try {
        Json.decodeFromString<InboxCheckpointDto>(
                bytes.decodeToString(throwOnInvalidSequence = true)
            )
            .toCheckpoint(raw, access)
    } finally {
        bytes.fill(0)
    }
}

suspend fun decodeInboxPage(
    cipher: ContentCipher,
    key: ByteArray,
    access: InboxAccess,
    page: ReplicaPage,
): InboxCachePage {
    val raw = requireNotNull(page.checkpoint)
    val checkpoint = decodeInboxCheckpoint(cipher, key, access, raw)
    val items =
        page.documents.take(30).map { document ->
            val bytes =
                cipher.decrypt(
                    key,
                    inboxAssociatedData(
                        access,
                        raw.handle.generation,
                        "item",
                        document.id,
                        document.version,
                        legacySessionBound = raw.handle.scope.accountKey == null,
                    ),
                    Base64.decode(requireNotNull(document.payload)),
                )
            val item =
                try {
                    Json.decodeFromString<InboxItemDto>(
                            bytes.decodeToString(throwOnInvalidSequence = true)
                        )
                        .toItem(document.id)
                } finally {
                    bytes.fill(0)
                }
            require(item.version == document.version && item.sortKey() == document.sortKey)
            item
        }
    return InboxCachePage(
        checkpoint,
        items,
        if (page.documents.size > 30) page.documents[29].sortKey else null,
    )
}
