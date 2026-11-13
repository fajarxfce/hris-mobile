package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.policies.collaborationSpaceComposerIntent
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.ObserveCollaborationSpaceComposerProgress
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.Session
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlin.test.*
import kotlinx.coroutines.flow.*

internal class SpaceComposerProgressFixture {
    val base = SpaceComposerObservationFixture()
    val access = base.access
    val first = base.original
    val second = original(2)
    val requested = MutableStateFlow(listOf(first))
    val records =
        mutableMapOf<String, MutableStateFlow<AppResult<CollaborationSpaceMessageRecord?>>>()
    val customReads =
        mutableMapOf<String, () -> Flow<AppResult<CollaborationSpaceMessageRecord?>>>()
    val starts = mutableMapOf<String, Int>()
    val stops = mutableMapOf<String, Int>()
    val storage = base.storageFailure
    val membership = base.membership
    val identity = base.identity
    val native = base.native
    var currentAdmission: suspend () -> AppResult<CollaborationSpaceDirectoryAdmission> = {
        membership.gate.value
    }
    var currentIdentity: suspend () -> AppResult<Session?> = { identity.value }
    val directory =
        object : CollaborationSpaceDirectoryRepository by base.directory {
            override suspend fun admission(
                access: CollaborationSpaceLocalAccess
            ): AppResult<CollaborationSpaceDirectoryAdmission> {
                assertEquals(this@SpaceComposerProgressFixture.access, access)
                return currentAdmission()
            }
        }
    val sessions =
        object : SessionRepository by base.sessions {
            override suspend fun current() = currentIdentity()
        }
    val messages =
        object : CollaborationSpaceMessageRepository by base.seed.base.messages {
            override fun observeRecord(
                access: CollaborationSpaceLocalAccess,
                id: String,
            ): Flow<AppResult<CollaborationSpaceMessageRecord?>> = flow {
                assertEquals(this@SpaceComposerProgressFixture.access, access)
                starts[id] = starts.getOrElse(id) { 0 } + 1
                try {
                    emitAll(customReads[id]?.invoke() ?: slot(id))
                } finally {
                    stops[id] = stops.getOrElse(id) { 0 } + 1
                }
            }
        }

    fun slot(id: String) = records.getOrPut(id) { MutableStateFlow(AppResult.Success(null)) }

    fun record(original: CollaborationComposerSubmission = first) =
        CollaborationSpaceMessageRecord(
            collaborationSpaceComposerIntent(access, original),
            0,
            CollaborationMessageState.Queued,
        )

    fun original(index: Int): CollaborationComposerSubmission =
        base.original.copy(
            original = base.original.original.copy(revision = id(index), text = "Original $index"),
            nextRevision = id(index + 1000),
        )

    fun id(index: Int) = "f3000000-0000-4000-8000-" + index.toString().padStart(12, '0')

    fun observe() =
        ObserveCollaborationSpaceComposerProgress(messages, directory, sessions, native.server)

    fun assertReadOnly() {
        base.assertReadOnly()
        assertEquals(0, base.seed.base.observations)
        assertEquals(0, base.draftStarts + base.outboxStarts)
    }
}
