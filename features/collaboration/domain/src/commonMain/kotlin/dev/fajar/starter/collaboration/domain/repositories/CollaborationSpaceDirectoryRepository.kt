package dev.fajar.starter.collaboration.domain.repositories

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.common.result.*
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow

/**
 * Encrypted local directory. Use cases own native acquisition, admission and refresh scheduling.
 */
interface CollaborationSpaceDirectoryRepository {
    /**
     * Latest staging or published progress, retained across process and local-session replacement.
     */
    suspend fun checkpoint(
        access: CollaborationSpaceLocalAccess
    ): AppResult<CollaborationSpaceDirectoryCheckpoint?>

    /** Only published facts; an incomplete replacement retains the previous visible generation. */
    fun observe(
        access: CollaborationSpaceLocalAccess
    ): Flow<AppResult<CollaborationSpaceDirectorySnapshot>>

    /**
     * Starts a fresh generation only after the previous one completed. Pending work must resume.
     */
    suspend fun begin(
        access: CollaborationSpaceLocalAccess,
        expected: CollaborationSpaceDirectoryCheckpoint?,
        generation: String,
        startedAt: Instant,
    ): AppResult<Boolean>

    /** Commits one unfiltered native page and its cursor together, under exact checkpoint CAS. */
    suspend fun append(
        access: CollaborationSpaceLocalAccess,
        expected: CollaborationSpaceDirectoryCheckpoint,
        page: CollaborationSpaceContactPage,
        acquiredAt: Instant,
    ): AppResult<Boolean>

    fun observeAdmission(
        access: CollaborationSpaceLocalAccess
    ): Flow<AppResult<CollaborationSpaceDirectoryAdmission>>

    suspend fun admission(
        access: CollaborationSpaceLocalAccess
    ): AppResult<CollaborationSpaceDirectoryAdmission>

    suspend fun restrict(
        access: CollaborationSpaceLocalAccess,
        failure: Failure,
    ): AppResult<Boolean>

    suspend fun admit(
        access: CollaborationSpaceLocalAccess,
        expectedRevision: String?,
    ): AppResult<Boolean>
}
