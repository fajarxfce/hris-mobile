package dev.fajar.starter.collaboration.data.datasources

import dev.fajar.starter.collaboration.data.dto.CollaborationSdkRoomInventoryDto

/**
 * One local page from the caller's existing SDK client. The caller owns the parent lifetime; the
 * datasource releases every invocation-owned room handle before publishing detached metadata. A
 * page is ordered by room ID and starts strictly after its cursor. It does not prove whole-pass
 * coverage while the native room set is changing; durable source continuity belongs to its owner.
 */
interface CollaborationRoomInventorySource {
    suspend fun read(afterRoomId: String?, limit: Int): CollaborationSdkRoomInventoryDto
}
