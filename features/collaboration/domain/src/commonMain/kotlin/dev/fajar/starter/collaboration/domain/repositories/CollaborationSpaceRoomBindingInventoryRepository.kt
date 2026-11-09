package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult

/**
 * Bounded owned local facts. Delivery admission and persisted sweep progress belong to use cases.
 */
interface CollaborationSpaceRoomBindingInventoryRepository {
    /**
     * Scans at most [limit] raw index entries (1..50), authenticating their immutable pairs. An
     * empty binding list can have a next cursor. Only a null cursor means this scan ended. Appends
     * after the first page are excluded and reported through hasNewerBindings, even when they sort
     * behind the cursor. The owning worker must arrange a later fresh sweep. Same-account
     * reauthentication preserves a cursor; a changed namespace fails explicitly.
     */
    suspend fun page(
        access: CollaborationSpaceLocalAccess,
        after: CollaborationSpaceRoomBindingCursor? = null,
        limit: Int = 25,
    ): AppResult<CollaborationSpaceRoomBindingPage>
}
