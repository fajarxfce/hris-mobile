package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.AppResult

interface CollaborationPushPriorityRepository {
    suspend fun current(parent: CollaborationNativeParent): AppResult<CollaborationPushPriority?>

    /** Null is a lost CAS/ownership race. Empty snapshots retain a fresh generation fence. */
    suspend fun replace(
        parent: CollaborationNativeParent,
        expected: CollaborationPushPriority?,
        hints: List<CollaborationPushHint>,
    ): AppResult<CollaborationPushPriority?>
}
