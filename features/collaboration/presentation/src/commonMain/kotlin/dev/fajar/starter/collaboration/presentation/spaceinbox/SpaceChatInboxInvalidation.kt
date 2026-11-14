package dev.fajar.starter.collaboration.presentation.spaceinbox

import dev.fajar.starter.collaboration.domain.entities.CollaborationSpaceInboxVersion
import dev.fajar.starter.common.result.AppResult

/**
 * Local publication order fences a Paging generation; lagging same-generation notices are quiet.
 */
internal fun spaceChatInboxPublicationInvalidates(
    startedAt: Long,
    retained: CollaborationSpaceInboxVersion?,
    notice: SpaceChatInboxPublication,
): Boolean {
    if (notice.sequence <= startedAt) return false
    return when (val result = notice.result) {
        null -> false
        is AppResult.Failed -> true
        is AppResult.Success -> {
            val version = result.value
            when {
                version == null || retained == null -> version != retained
                version.generation != retained.generation -> true
                else -> version.revision > retained.revision
            }
        }
    }
}
