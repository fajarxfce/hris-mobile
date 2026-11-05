package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult
import kotlinx.coroutines.flow.Flow

/** Local visibility only; never cancels delivery, redacts remote events or destroys SDK keys. */
interface CollaborationLocalVisibilityRepository {
    /** Indexed checks for the supplied identities, including messages not imported into history. */
    fun observeHidden(
        access: CollaborationLocalAccess,
        conversationId: String,
        roomId: String,
        messages: List<CollaborationMessageReference>,
    ): Flow<AppResult<Set<CollaborationMessageReference>>>

    /** Atomically hides the whole bounded batch; false is a concurrent checkpoint/owner change. */
    suspend fun hide(
        access: CollaborationLocalAccess,
        conversationId: String,
        roomId: String,
        messages: List<CollaborationMessageReference>,
    ): AppResult<Boolean>
}
