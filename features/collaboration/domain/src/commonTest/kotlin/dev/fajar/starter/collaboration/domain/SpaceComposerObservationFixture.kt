package dev.fajar.starter.collaboration.domain

import dev.fajar.starter.collaboration.domain.entities.*
import dev.fajar.starter.collaboration.domain.repositories.*
import dev.fajar.starter.collaboration.domain.usecases.ObserveCollaborationSpaceComposer
import dev.fajar.starter.common.result.*
import dev.fajar.starter.identity.domain.entities.Session
import dev.fajar.starter.identity.domain.repositories.SessionRepository
import kotlin.test.*
import kotlinx.coroutines.flow.*

internal class SpaceComposerObservationFixture {
    val seed = SpaceComposerUseCaseFixture()
    val access = seed.access
    val conversationId = seed.original.conversationId
    val membership = seed.base.base
    val native = seed.base.native
    val identity = seed.base.identity
    val original = seed.submission
    val editor = original.nextDraft.copy(text = "Unsubmitted next message")
    val draft = MutableStateFlow<AppResult<CollaborationDraft?>>(AppResult.Success(editor))
    val entries =
        MutableStateFlow<AppResult<List<CollaborationComposerOutboxEntry>>>(
            AppResult.Success(listOf(CollaborationComposerOutboxEntry(original, false)))
        )
    val storageFailure = Failure(FailureKind.Storage, "Fictional local read failure")
    var readDraft: () -> Flow<AppResult<CollaborationDraft?>> = { draft }
    var readEntries: () -> Flow<AppResult<List<CollaborationComposerOutboxEntry>>> = { entries }
    var currentIdentity: suspend () -> AppResult<Session?> = { identity.value }
    var currentAdmission: suspend () -> AppResult<CollaborationSpaceDirectoryAdmission> = {
        membership.gate.value
    }
    var draftStarts = 0
    var draftStops = 0
    var outboxStarts = 0
    var outboxStops = 0
    val sessions =
        object : SessionRepository by seed.base.sessions {
            override fun observe() = identity

            override suspend fun current() = currentIdentity()
        }
    val directory =
        object : CollaborationSpaceDirectoryRepository by membership.directory {
            override suspend fun admission(
                access: CollaborationSpaceLocalAccess
            ): AppResult<CollaborationSpaceDirectoryAdmission> {
                assertEquals(this@SpaceComposerObservationFixture.access, access)
                return currentAdmission()
            }
        }
    val drafts =
        object : CollaborationSpaceDraftRepository by seed.drafts {
            override fun observe(
                access: CollaborationSpaceLocalAccess,
                conversationId: String,
            ): Flow<AppResult<CollaborationDraft?>> = flow {
                assertEquals(this@SpaceComposerObservationFixture.access, access)
                assertEquals(this@SpaceComposerObservationFixture.conversationId, conversationId)
                draftStarts++
                try {
                    emitAll(readDraft())
                } finally {
                    draftStops++
                }
            }
        }
    val outbox =
        object : CollaborationSpaceComposerOutboxRepository by seed.outbox {
            override fun observe(
                access: CollaborationSpaceLocalAccess
            ): Flow<AppResult<List<CollaborationComposerOutboxEntry>>> = flow {
                assertEquals(this@SpaceComposerObservationFixture.access, access)
                outboxStarts++
                try {
                    emitAll(readEntries())
                } finally {
                    outboxStops++
                }
            }
        }

    fun observe() =
        ObserveCollaborationSpaceComposer(drafts, outbox, directory, sessions, native.server)

    fun snapshot() =
        CollaborationSpaceComposerSnapshot(
            (draft.value as AppResult.Success).value,
            (entries.value as AppResult.Success).value,
        )

    fun assertReadOnly() {
        assertEquals(
            0,
            seed.draftReads + seed.outboxReads + seed.base.reads + seed.base.canonicalReads,
        )
        assertTrue(seed.edits.isEmpty() && seed.handoffs.isEmpty() && seed.base.writes.isEmpty())
        assertTrue(seed.base.schedules.isEmpty())
        assertEquals(0, native.pageCalls)
    }
}
